package de.regelsuche.search.moves;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.ToDoubleFunction;

/** A frontier of states AND suspended expansions. Old mechanical v1/v2 replay remains unchanged. */
public final class MoveSearch {
    public enum Mode { FAST, COMPLETE_BOUNDED_REFERENCE }
    public enum Scheduling { EAGER_CONTROL, STAGED, INCREMENTAL_NATIVE_ORDER }
    public enum Outcome { TARGET_REACHED, BOUNDED_EXHAUSTED, INCONCLUSIVE, WORK_EXHAUSTED, STATE_LIMIT, QUALITY_REACHED }
    public enum Decision { ENQUEUED, DUPLICATE, PATH_BOUND, ASSUMPTION_REJECTED, PROOF_REJECTED, COMPLEXITY_BOUND, WORK_LIMIT, DOMINATED }
    public record Budget(int maxPrimitiveSteps, int maxSearchDepth, long maxTheoryWork, int maxStates, long totalWork, int maxComplexityDebt) {
        public Budget(int primitive, int depth, long theory, int states, long work) { this(primitive, depth, theory, states, work, Integer.MAX_VALUE); }
        public Budget {
            if (maxPrimitiveSteps < 0 || maxSearchDepth < 0 || maxTheoryWork < 0 || maxStates < 1 || totalWork < 1 || maxComplexityDebt < 0)
                throw new IllegalArgumentException("invalid move search budget");
        }
    }
    public record Problem(String source, MoveContext context, List<MoveProvider> providers, MovePriorityPolicy policy,
            MoveVerifier verifier, ToDoubleFunction<MoveState> stateScore, Mode mode, Scheduling scheduling, Budget budget, StateValue stateValue) {
        public Problem(String source, MoveContext context, List<MoveProvider> providers, MovePriorityPolicy policy,
                MoveVerifier verifier, ToDoubleFunction<MoveState> score, Mode mode, Scheduling scheduling, Budget budget) {
            this(source, context, providers, policy, verifier, score, mode, scheduling, budget, StateValue.NONE);
        }
        public Problem {
            java.util.Objects.requireNonNull(context, "context");
            java.util.Objects.requireNonNull(providers, "providers");
            providers = List.copyOf(providers);
            java.util.Objects.requireNonNull(verifier); java.util.Objects.requireNonNull(policy);
            java.util.Objects.requireNonNull(stateScore); java.util.Objects.requireNonNull(mode);
            java.util.Objects.requireNonNull(scheduling); java.util.Objects.requireNonNull(budget); java.util.Objects.requireNonNull(stateValue);
            if (source == null || source.isBlank() || context.phase() == MoveContext.Phase.PRODUCTION)
                throw new IllegalArgumentException("experimental scheduling is not production-qualified (#745)");
            validateScheduling(scheduling, providers, policy);
        }
    }

    private static void validateScheduling(Scheduling scheduling, List<MoveProvider> providers, MovePriorityPolicy policy) {
        if (scheduling == Scheduling.INCREMENTAL_NATIVE_ORDER) {
            if (policy != MovePriorityPolicy.INVENTORY_ORDER || providers.stream().anyMatch(provider -> !(provider instanceof IncrementalMoveProvider)))
                throw new IllegalArgumentException("incremental native ordering requires native providers and INVENTORY_ORDER");
        } else if (providers.stream().anyMatch(provider -> provider instanceof IncrementalMoveProvider)) {
            throw new IllegalArgumentException("incremental providers require INCREMENTAL_NATIVE_ORDER scheduling");
        }
    }
    /** The full attempted target identity is retained even when admission rejects it. */
    public record Event(MoveState source, MoveState target, SearchMove move, Decision decision, MoveVerifier.Verification verification) {
        /** Empty means NOT_PERFORMED, not a failed or free mathematical verification. JSON retains explicit null. */
        public java.util.Optional<MoveVerifier.Verification> verificationResult() { return java.util.Optional.ofNullable(verification); }
    }
    public record WitnessStep(MoveState source, MoveState target, SearchMove move, MoveVerifier.Verification verification) {}
    public record Metrics(long generatedSuccessors, long consumedSuccessors, long discardedSuccessors, long unconsumedSuccessors,
            long duplicates, long deadEnds, long exploredStates, long expandedStates, long primitiveWork, long searchWork,
            long verificationWork, int firstHitDepth, int firstHitPrimitiveDepth, Map<String, Long> familyMatches) {
        public Metrics { familyMatches = java.util.Collections.unmodifiableMap(new TreeMap<>(familyMatches)); }
        public long totalWork() { return Math.addExact(Math.addExact(primitiveWork, searchWork), verificationWork); }
        /** Explicit nonoverlapping charged dimensions; historical totals and serializers are unchanged. */
        public Map<String, Long> chargedComponents() {
            return Map.of("primitive", primitiveWork, "search", searchWork, "verification", verificationWork);
        }
        public double effectiveBranchingFactor() { return expandedStates == 0 ? 0 : (double) (consumedSuccessors - discardedSuccessors) / expandedStates; }
    }
    public record Result(Outcome outcome, List<WitnessStep> witness, List<Event> events, Set<MoveState> reachedStates, List<MoveState> deadEndStates,
            Metrics metrics, boolean completeBoundedRelation, Map<MoveState, StateValue.Assessment> stateAssessments,
            IncrementalMoveExecution incrementalExecution) {
        /** Historical constructor and exports retain their original work contract. */
        public Result(Outcome outcome, List<WitnessStep> witness, List<Event> events, Set<MoveState> reachedStates,
                List<MoveState> deadEndStates, Metrics metrics, boolean completeBoundedRelation,
                Map<MoveState, StateValue.Assessment> stateAssessments) {
            this(outcome, witness, events, reachedStates, deadEndStates, metrics, completeBoundedRelation, stateAssessments, null);
        }
        public Result { witness = List.copyOf(witness); events = List.copyOf(events); reachedStates = Set.copyOf(reachedStates); deadEndStates = List.copyOf(deadEndStates); stateAssessments = Map.copyOf(stateAssessments); }
        public boolean reached() { return outcome == Outcome.TARGET_REACHED; }
    }
    /** A caller-supplied quality objective, not a desired expression or mathematical authority. */
    public record ObjectiveScore(long value, long work) {
        public ObjectiveScore {
            if (work < 1) throw new IllegalArgumentException("objective inspection must report positive work");
        }
    }
    @FunctionalInterface public interface Objective { ObjectiveScore evaluate(MoveState state); }
    public record QualityResult(Result search, MoveState incumbent, long inputScore, long outputScore,
            List<WitnessStep> witness, long objectiveWork) {
        public QualityResult { witness = List.copyOf(witness); }
    }

    private static final class Node {
        final MoveState state;
        final StateValue.Assessment value;
        final long theoryWork;
        final MoveWitnessPath path;
        MovePicker picker;
        long measured;
        long measuredMathematics;
        int generated;
        int enqueued;
        int pulls;
        Node(MoveState state, long theoryWork, MoveWitnessPath path, StateValue.Assessment value) { this.state = state; this.theoryWork = theoryWork; this.path = path; this.value = value; }
    }
    private record Ticket(Node node, double priority, long serial) {}
    private static final class Ledger {
        long primitive, search, verification, consumed, discarded, duplicates, deadEnds, explored, expanded, generated;
        final Map<String, Long> matches = new TreeMap<>();
        MoveSearchObjective objective;
        void observe(MoveState state, MoveWitnessPath path) {
            if (objective != null) search = Math.addExact(search, objective.observe(state, path));
        }
        boolean qualityReached() { return objective != null && objective.satisfied(); }
        long total() { return Math.addExact(Math.addExact(primitive, search), verification); }
        void collect(Node node) {
            long now = node.picker.workMetrics().totalWorkUnits();
            search = Math.addExact(search, now - node.measured); node.measured = now;
            long mathematicalWork = node.picker.workMetrics().candidateWork().canonicalWorkUnits();
            primitive = Math.addExact(primitive, mathematicalWork - node.measuredMathematics); node.measuredMathematics = mathematicalWork;
            var moves = node.picker.generatedMoves();
            for (int i = node.generated; i < moves.size(); i++) {
                var move = moves.get(i);
                matches.merge(move.ruleFamily(), 1L, Long::sum); generated++;
            }
            node.generated = moves.size();
        }
    }

    public Result search(Problem problem) {
        return search(problem, SearchContinuationContract.PATH_SENSITIVE);
    }

    /** Opt-in work replacement under an explicit continuation-locality contract. */
    public Result search(Problem problem, SearchContinuationContract contract) {
        return search(problem, contract, null);
    }

    /** Stop at sufficient source-only quality; no global optimality or target hit is implied. */
    public QualityResult searchUntil(Problem problem, Objective objective, long maximumOutputScore,
            SearchContinuationContract contract) {
        if (!problem.context().goal().isEmpty() || problem.mode() != Mode.FAST) {
            throw new IllegalArgumentException("quality stopping requires source-only FAST search");
        }
        var online = new MoveSearchObjective(objective, maximumOutputScore);
        var result = search(problem, contract, online);
        return new QualityResult(result, online.incumbent(), online.inputScore(), online.outputScore(),
            online.witness(), online.objectiveWork());
    }

    private Result search(Problem problem, SearchContinuationContract contract, MoveSearchObjective objective) {
        java.util.Objects.requireNonNull(contract, "contract");
        if (contract != SearchContinuationContract.PATH_SENSITIVE && problem.mode() != Mode.FAST) {
            throw new IllegalArgumentException("continuation dominance requires FAST mode");
        }
        return new SearchRun(problem, contract, objective).run();
    }

    /** Mutable state of one invocation of the existing frontier, never shared between searches. */
    private static final class SearchRun {
        private final Problem problem;
        private final SearchContinuationContract contract;
        private final Budget budget;
        private final Ledger ledger = new Ledger();
        private final List<Event> events = new ArrayList<>();
        private final List<MoveState> deadEnds = new ArrayList<>();
        private final Set<MoveState> reached = new HashSet<>();
        private final MoveSearchVisits visited;
        private final Map<MoveState, StateValue.Assessment> assessments = new java.util.HashMap<>();
        private final PriorityQueue<Ticket> frontier = new PriorityQueue<>(
            Comparator.comparingDouble(Ticket::priority).thenComparingLong(Ticket::serial));
        private final long[] serial = {0};
        private final List<Node> opened = new ArrayList<>();
        private boolean complete;
        private boolean stopped;
        private Outcome outcome = Outcome.BOUNDED_EXHAUSTED;
        private List<WitnessStep> witness = List.of();
        private int hit = -1;
        private int primitiveHit = -1;

        SearchRun(Problem problem, SearchContinuationContract contract, MoveSearchObjective objective) {
            this.problem = problem;
            this.contract = contract;
            budget = problem.budget();
            ledger.objective = objective;
            visited = new MoveSearchVisits(contract, work -> ledger.search = Math.addExact(ledger.search, work));
            var root = new MoveState(problem.source(), 0, 0, "", problem.context().initialAssumptions(), Set.of(), 0);
            var rootValue = inspect(problem, root, ledger);
            root = new MoveState(root.expression(), 0, 0, "", root.assumptions(), rootValue.capabilities().keySet(), 0);
            assessments.put(root, rootValue);
            ledger.observe(root, MoveWitnessPath.ROOT);
            frontier.add(new Ticket(new Node(root, 0, MoveWitnessPath.ROOT, rootValue), 0, serial[0]++));
            visited.add(root, 0);
            complete = contract == SearchContinuationContract.PATH_SENSITIVE;
        }

        Result run() {
            try {
                while (!stopped && !frontier.isEmpty()) advance();
            } finally {
                closeIncremental(opened, ledger);
            }
            return finish();
        }

        /** Preserve the ordering of quality, work, dominance and state-limit checks. */
        private void advance() {
            if (ledger.qualityReached()) {
                stop(ledger.total() <= budget.totalWork() ? Outcome.QUALITY_REACHED : Outcome.WORK_EXHAUSTED);
                return;
            }
            if (ledger.total() >= budget.totalWork()) {
                stop(Outcome.WORK_EXHAUSTED);
                return;
            }
            var node = frontier.remove().node();
            ledger.search++;
            boolean current = visited.current(node.state, node.theoryWork);
            if (contract != SearchContinuationContract.PATH_SENSITIVE && ledger.total() > budget.totalWork()) {
                stop(Outcome.WORK_EXHAUSTED);
                return;
            }
            if (!current) return;
            if (node.picker == null && !open(node)) return;
            recordExpansion(node, expand(problem, node, ledger, events, visited, frontier, serial, assessments));
        }

        /** False also covers a depth-bound leaf; only terminal outcomes stop the whole run. */
        private boolean open(Node node) {
            if (ledger.explored >= budget.maxStates()) {
                stop(Outcome.STATE_LIMIT);
                return false;
            }
            ledger.explored++;
            reached.add(node.state);
            if (node.state.expression().equals(problem.context().goal())) {
                stop(Outcome.TARGET_REACHED);
                witness = node.path.steps();
                hit = node.state.searchDepth();
                primitiveHit = node.state.primitiveDepth();
                return false;
            }
            if (node.state.searchDepth() == budget.maxSearchDepth()) return false;
            ledger.expanded++;
            node.picker = picker(problem, node.state);
            retainIncremental(node, opened);
            return true;
        }

        private void recordExpansion(Node node, Expansion expansion) {
            if (expansion == Expansion.WORK_LIMIT) {
                stop(Outcome.WORK_EXHAUSTED);
                return;
            }
            if (expansion == Expansion.REJECTED_PROOF) complete = false;
            if (expansion == Expansion.EXHAUSTED) {
                complete &= node.picker.complete();
                if (node.enqueued == 0 && node.picker.complete()) {
                    ledger.deadEnds++;
                    deadEnds.add(node.state);
                }
            }
        }

        private void stop(Outcome terminalOutcome) {
            outcome = terminalOutcome;
            complete = false;
            stopped = true;
        }

        private Result finish() {
            var objective = ledger.objective;
            if (objective != null) {
                ledger.search = Math.addExact(ledger.search, objective.finish());
                if (outcome == Outcome.QUALITY_REACHED) witness = objective.witness();
            }
            if (cleanupOverrun(problem, ledger) || (objective != null && ledger.total() > budget.totalWork())) {
                stop(Outcome.WORK_EXHAUSTED);
                witness = List.of();
                hit = -1;
                primitiveHit = -1;
            }
            if (outcome == Outcome.BOUNDED_EXHAUSTED && !complete) outcome = Outcome.INCONCLUSIVE;
            return new Result(outcome, witness, events, reached, deadEnds, new Metrics(ledger.generated, ledger.consumed, ledger.discarded,
                ledger.generated - ledger.consumed, ledger.duplicates, ledger.deadEnds, ledger.explored, ledger.expanded,
                ledger.primitive, ledger.search, ledger.verification, hit, primitiveHit, ledger.matches), complete, assessments,
                incrementalExecution(problem, opened));
        }
    }

    private static MovePicker picker(Problem problem, MoveState state) {
        return switch (problem.scheduling()) {
            case STAGED -> new StagedMovePicker(problem.providers(), problem.policy(), state, problem.context());
            case EAGER_CONTROL -> new EagerMovePicker(problem.providers(), problem.policy(), state, problem.context());
            case INCREMENTAL_NATIVE_ORDER -> new IncrementalMovePicker(problem.providers(), state, problem.context());
        };
    }
    private static void retainIncremental(Node node, List<Node> opened) {
        if (node.picker instanceof IncrementalMovePicker) opened.add(node);
    }
    private static void closeIncremental(List<Node> opened, Ledger ledger) {
        for (var node : opened) if (node.picker instanceof IncrementalMovePicker incremental) {
            incremental.close(); ledger.collect(node);
        }
    }
    private static boolean cleanupOverrun(Problem problem, Ledger ledger) {
        return problem.scheduling() == Scheduling.INCREMENTAL_NATIVE_ORDER && ledger.total() > problem.budget().totalWork();
    }
    private static IncrementalMoveExecution incrementalExecution(Problem problem, List<Node> opened) {
        if (problem.scheduling() != Scheduling.INCREMENTAL_NATIVE_ORDER) return null;
        var providers = problem.providers().stream().map(provider -> {
            var incremental = (IncrementalMoveProvider) provider;
            return new IncrementalMoveExecution.Provider(provider.descriptor(), incremental.definition());
        }).toList();
        return new IncrementalMoveExecution(IncrementalMoveExecution.WORK_REVISION, IncrementalMoveExecution.ORDER_REVISION,
            providers, opened.stream().map(node -> ((IncrementalMovePicker) node.picker).receipt()).toList());
    }
    private enum Expansion { MORE, EXHAUSTED, REJECTED_PROOF, WORK_LIMIT }
    private static Expansion expand(Problem problem, Node node, Ledger ledger, List<Event> events,
            MoveSearchVisits visited, PriorityQueue<Ticket> frontier, long[] serial, Map<MoveState, StateValue.Assessment> assessments) {
        var next = node.picker instanceof IncrementalMovePicker incremental
            ? incremental.next(Math.max(0, problem.budget().totalWork() - ledger.total())) : node.picker.next();
        ledger.collect(node); node.pulls++;
        // Atomic providers report actual overrun; such runs cannot claim a budget-respecting success.
        if (ledger.total() > problem.budget().totalWork()) return Expansion.WORK_LIMIT;
        if (node.picker instanceof IncrementalMovePicker incremental && incremental.workExhausted()) return Expansion.WORK_LIMIT;
        if (next.isEmpty()) return Expansion.EXHAUSTED;
        var move = next.orElseThrow(); ledger.consumed++; ledger.search++;
        var admission = admit(problem, node, move, visited, ledger);
        var decision = admission.decision();
        var verification = admission.verification();
        var child = admission.child();
        var delta = new HashSet<>(child.capabilities()); delta.removeAll(node.state.capabilities());
        move = move.withCapabilityDelta(delta);
        boolean proofRejected = decision == Decision.PROOF_REJECTED || decision == Decision.ASSUMPTION_REJECTED;
        if (decision == Decision.ENQUEUED) {
            visited.add(child, admission.theoryWork()); node.enqueued++; assessments.put(child, admission.value());
            var path = node.path.append(new WitnessStep(node.state, child, move, verification));
            frontier.add(new Ticket(new Node(child, admission.theoryWork(), path, admission.value()),
                child.expression().equals(problem.context().goal()) ? -Double.MAX_VALUE : priority(problem, child, admission.value()), serial[0]++));
            ledger.observe(child, path);
        }
        if (decision != Decision.ENQUEUED) ledger.discarded++;
        events.add(new Event(node.state, child, move, decision, verification));
        if (decision == Decision.WORK_LIMIT) return Expansion.WORK_LIMIT;
        if (ledger.qualityReached()) return Expansion.MORE;
        int stage = node.picker instanceof StagedMovePicker staged ? staged.nextStage() : 0;
        // Parent widening stays on the frontier, so promising children can finish before later stages open.
        double continuation = problem.mode() == Mode.COMPLETE_BOUNDED_REFERENCE ? node.state.searchDepth()
            : priority(problem, node.state, node.value) + 1 + stage + node.pulls / 2.0;
        frontier.add(new Ticket(node, continuation, serial[0]++)); ledger.search++;
        return proofRejected ? Expansion.REJECTED_PROOF : Expansion.MORE;
    }
    private record Admission(MoveState child, long theoryWork, Decision decision, MoveVerifier.Verification verification, StateValue.Assessment value) {}
    private static Admission admit(Problem problem, Node node, SearchMove move, MoveSearchVisits visited, Ledger ledger) {
        var step = move.transformation();
        long depth = (long) node.state.primitiveDepth() + step.primitiveStepCount();
        long theory = Math.addExact(node.theoryWork, step.executionWork().exactTheoryWorkUnits());
        var child = new MoveState(step.transformedExpression(), node.state.searchDepth() + 1,
            (int) Math.min(Integer.MAX_VALUE, depth), move.ruleId(), node.state.assumptions(), Set.of(), 0);
        var value = inspect(problem, child, ledger);
        long debt = Math.max(0L, (long) node.state.complexityDebt() + value.complexity() - node.value.complexity());
        child = new MoveState(child.expression(), child.searchDepth(), child.primitiveDepth(), child.previousRule(), child.assumptions(),
            value.capabilities().keySet(), (int) Math.min(Integer.MAX_VALUE, debt));
        Decision decision = rejectBounds(problem, node, move, depth, theory, debt, ledger);
        if (decision != null) return new Admission(child, theory, decision, null, value);

        decision = visited.rejection(child, theory);
        if (decision == Decision.DUPLICATE) ledger.duplicates++;
        if (ledger.total() > problem.budget().totalWork()) decision = Decision.WORK_LIMIT;
        if (decision != null) return new Admission(child, theory, decision, null, value);
        if (ledger.total() >= problem.budget().totalWork()) {
            return new Admission(child, theory, Decision.WORK_LIMIT, null, value);
        }
        var verification = problem.verifier().verify(node.state, move, problem.context());
        ledger.verification = Math.addExact(ledger.verification, verification.work());
        decision = ledger.total() > problem.budget().totalWork() ? Decision.WORK_LIMIT
            : verification.accepted() ? Decision.ENQUEUED : Decision.PROOF_REJECTED;
        return new Admission(child, theory, decision, verification, value);
    }

    /** A rejected bound must not trigger a paid visited lookup or a proof attempt. */
    private static Decision rejectBounds(Problem problem, Node node, SearchMove move,
            long depth, long theory, long debt, Ledger ledger) {
        if (ledger.total() > problem.budget().totalWork()) return Decision.WORK_LIMIT;
        if (debt > problem.budget().maxComplexityDebt()) return Decision.COMPLEXITY_BOUND;
        if (depth > problem.budget().maxPrimitiveSteps() || theory > problem.budget().maxTheoryWork()) return Decision.PATH_BOUND;
        if (!problem.context().carries(move.assumptions(), node.state)) return Decision.ASSUMPTION_REJECTED;
        return null;
    }

    private static StateValue.Assessment inspect(Problem problem, MoveState state, Ledger ledger) {
        var value = problem.stateValue().evaluate(state, problem.context());
        if (value.capabilities().values().stream().anyMatch(capability -> !capability.sourceExpression().equals(state.expression())))
            throw new IllegalArgumentException("capability evidence differs from source state");
        ledger.search = Math.addExact(ledger.search, value.searchWork());
        ledger.primitive = Math.addExact(ledger.primitive, value.primitiveWork());
        return value;
    }
    private static double priority(Problem problem, MoveState state, StateValue.Assessment value) {
        double score = problem.mode() == Mode.COMPLETE_BOUNDED_REFERENCE ? state.searchDepth() : problem.stateScore().applyAsDouble(state) - value.value();
        if (!Double.isFinite(score)) throw new IllegalArgumentException("nonfinite state priority");
        return score;
    }
}
