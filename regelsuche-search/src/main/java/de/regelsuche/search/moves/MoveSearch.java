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
    public enum Scheduling { EAGER_CONTROL, STAGED }
    public enum Outcome { TARGET_REACHED, BOUNDED_EXHAUSTED, INCONCLUSIVE, WORK_EXHAUSTED, STATE_LIMIT }
    public enum Decision { ENQUEUED, DUPLICATE, PATH_BOUND, ASSUMPTION_REJECTED, PROOF_REJECTED, COMPLEXITY_BOUND, WORK_LIMIT }
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
            providers = List.copyOf(providers);
            java.util.Objects.requireNonNull(verifier); java.util.Objects.requireNonNull(policy);
            java.util.Objects.requireNonNull(stateScore); java.util.Objects.requireNonNull(mode);
            java.util.Objects.requireNonNull(scheduling); java.util.Objects.requireNonNull(budget); java.util.Objects.requireNonNull(stateValue);
            if (source == null || source.isBlank() || context.phase() == MoveContext.Phase.PRODUCTION)
                throw new IllegalArgumentException("experimental scheduling is not production-qualified (#745)");
        }
    }
    public record Event(MoveState source, SearchMove move, Decision decision, MoveVerifier.Verification verification) {}
    public record WitnessStep(MoveState source, MoveState target, SearchMove move, MoveVerifier.Verification verification) {}
    public record Metrics(long generatedSuccessors, long consumedSuccessors, long discardedSuccessors, long unconsumedSuccessors,
            long duplicates, long deadEnds, long exploredStates, long expandedStates, long primitiveWork, long searchWork,
            long verificationWork, int firstHitDepth, int firstHitPrimitiveDepth, Map<String, Long> familyMatches) {
        public Metrics { familyMatches = java.util.Collections.unmodifiableMap(new TreeMap<>(familyMatches)); }
        public long totalWork() { return Math.addExact(Math.addExact(primitiveWork, searchWork), verificationWork); }
        public double effectiveBranchingFactor() { return expandedStates == 0 ? 0 : (double) (consumedSuccessors - discardedSuccessors) / expandedStates; }
    }
    public record Result(Outcome outcome, List<WitnessStep> witness, List<Event> events, Set<MoveState> reachedStates, List<MoveState> deadEndStates,
            Metrics metrics, boolean completeBoundedRelation, Map<MoveState, StateValue.Assessment> stateAssessments) {
        public Result { witness = List.copyOf(witness); events = List.copyOf(events); reachedStates = Set.copyOf(reachedStates); deadEndStates = List.copyOf(deadEndStates); stateAssessments = Map.copyOf(stateAssessments); }
        public boolean reached() { return outcome == Outcome.TARGET_REACHED; }
    }
    private static final class Node {
        final MoveState state;
        final StateValue.Assessment value;
        final long theoryWork;
        final List<WitnessStep> path;
        MovePicker picker;
        long measured;
        int generated;
        int enqueued;
        int pulls;
        Node(MoveState state, long theoryWork, List<WitnessStep> path, StateValue.Assessment value) { this.state = state; this.theoryWork = theoryWork; this.path = path; this.value = value; }
    }
    private record Ticket(Node node, double priority, long serial) {}
    private record Identity(MoveState state, long theoryWork) {}
    private static final class Ledger {
        long primitive, search, verification, consumed, discarded, duplicates, deadEnds, explored, expanded, generated;
        final Map<String, Long> matches = new TreeMap<>();
        long total() { return Math.addExact(Math.addExact(primitive, search), verification); }
        void collect(Node node) {
            long now = node.picker.workMetrics().totalWorkUnits();
            search = Math.addExact(search, now - node.measured); node.measured = now;
            var moves = node.picker.generatedMoves();
            for (int i = node.generated; i < moves.size(); i++) {
                var move = moves.get(i);
                primitive = Math.addExact(primitive, move.applicationCost());
                matches.merge(move.ruleFamily(), 1L, Long::sum); generated++;
            }
            node.generated = moves.size();
        }
    }

    public Result search(Problem problem) {
        var budget = problem.budget();
        var ledger = new Ledger();
        var events = new ArrayList<Event>();
        var deadEnds = new ArrayList<MoveState>();
        var reached = new HashSet<MoveState>();
        var visited = new HashSet<Identity>();
        var assessments = new java.util.HashMap<MoveState, StateValue.Assessment>();
        var root = new MoveState(problem.source(), 0, 0, "", problem.context().initialAssumptions(), Set.of(), 0);
        var rootValue = inspect(problem, root, ledger);
        root = new MoveState(root.expression(), 0, 0, "", root.assumptions(), rootValue.capabilities().keySet(), 0);
        assessments.put(root, rootValue);
        var frontier = new PriorityQueue<Ticket>(Comparator.comparingDouble(Ticket::priority).thenComparingLong(Ticket::serial));
        long[] serial = {0};
        frontier.add(new Ticket(new Node(root, 0, List.of(), rootValue), 0, serial[0]++)); visited.add(new Identity(root, 0));
        boolean complete = true;
        var outcome = Outcome.BOUNDED_EXHAUSTED;
        List<WitnessStep> witness = List.of();
        int hit = -1, primitiveHit = -1;
        while (!frontier.isEmpty()) {
            if (ledger.total() >= budget.totalWork()) { outcome = Outcome.WORK_EXHAUSTED; complete = false; break; }
            var node = frontier.remove().node(); ledger.search++;
            if (node.picker == null) {
                if (ledger.explored >= budget.maxStates()) { outcome = Outcome.STATE_LIMIT; complete = false; break; }
                ledger.explored++; reached.add(node.state);
                if (node.state.expression().equals(problem.context().goal())) {
                    outcome = Outcome.TARGET_REACHED; witness = node.path;
                    hit = node.state.searchDepth(); primitiveHit = node.state.primitiveDepth(); complete = false; break;
                }
                if (node.state.searchDepth() == budget.maxSearchDepth()) continue;
                ledger.expanded++;
                node.picker = problem.scheduling() == Scheduling.STAGED
                    ? new StagedMovePicker(problem.providers(), problem.policy(), node.state, problem.context())
                    : new EagerMovePicker(problem.providers(), problem.policy(), node.state, problem.context());
            }
            var expansion = expand(problem, node, ledger, events, visited, frontier, serial, assessments);
            if (expansion == Expansion.WORK_LIMIT) { outcome = Outcome.WORK_EXHAUSTED; complete = false; break; }
            if (expansion == Expansion.REJECTED_PROOF) complete = false;
            if (expansion == Expansion.EXHAUSTED) {
                complete &= node.picker.complete();
                if (node.enqueued == 0 && node.picker.complete()) { ledger.deadEnds++; deadEnds.add(node.state);  }
            }
        }
        if (outcome == Outcome.BOUNDED_EXHAUSTED && !complete) outcome = Outcome.INCONCLUSIVE;
        return new Result(outcome, witness, events, reached, deadEnds, new Metrics(ledger.generated, ledger.consumed, ledger.discarded,
            ledger.generated - ledger.consumed, ledger.duplicates, ledger.deadEnds, ledger.explored, ledger.expanded,
            ledger.primitive, ledger.search, ledger.verification, hit, primitiveHit, ledger.matches), complete, assessments);
    }
    private enum Expansion { MORE, EXHAUSTED, REJECTED_PROOF, WORK_LIMIT }
    private static Expansion expand(Problem problem, Node node, Ledger ledger, List<Event> events,
            Set<Identity> visited, PriorityQueue<Ticket> frontier, long[] serial, Map<MoveState, StateValue.Assessment> assessments) {
        var next = node.picker.next(); ledger.collect(node); node.pulls++;
        // Atomic providers report actual overrun; such runs cannot claim a budget-respecting success.
        if (ledger.total() > problem.budget().totalWork()) return Expansion.WORK_LIMIT;
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
            visited.add(new Identity(child, admission.theoryWork())); node.enqueued++; assessments.put(child, admission.value());
            var path = new ArrayList<>(node.path); path.add(new WitnessStep(node.state, child, move, verification));
            frontier.add(new Ticket(new Node(child, admission.theoryWork(), List.copyOf(path), admission.value()),
                child.expression().equals(problem.context().goal()) ? -Double.MAX_VALUE : priority(problem, child, admission.value()), serial[0]++));
        }
        if (decision != Decision.ENQUEUED) ledger.discarded++;
        events.add(new Event(node.state, move, decision, verification));
        if (decision == Decision.WORK_LIMIT) return Expansion.WORK_LIMIT;
        int stage = node.picker instanceof StagedMovePicker staged ? staged.nextStage() : 0;
        // Parent widening stays on the frontier, so promising children can finish before later stages open.
        double continuation = problem.mode() == Mode.COMPLETE_BOUNDED_REFERENCE ? node.state.searchDepth()
            : priority(problem, node.state, node.value) + 1 + stage + node.pulls / 2.0;
        frontier.add(new Ticket(node, continuation, serial[0]++)); ledger.search++;
        return proofRejected ? Expansion.REJECTED_PROOF : Expansion.MORE;
    }
    private record Admission(MoveState child, long theoryWork, Decision decision, MoveVerifier.Verification verification, StateValue.Assessment value) {}
    private static Admission admit(Problem problem, Node node, SearchMove move, Set<Identity> visited, Ledger ledger) {
        var step = move.transformation();
        long depth = (long) node.state.primitiveDepth() + step.primitiveStepCount();
        long theory = Math.addExact(node.theoryWork, step.executionWork().exactTheoryWorkUnits());
        var child = new MoveState(step.transformedExpression(), node.state.searchDepth() + 1,
            (int) Math.min(Integer.MAX_VALUE, depth), move.ruleId(), node.state.assumptions(), Set.of(), 0);
        var value = inspect(problem, child, ledger);
        long debt = Math.max(0L, (long) node.state.complexityDebt() + value.complexity() - node.value.complexity());
        child = new MoveState(child.expression(), child.searchDepth(), child.primitiveDepth(), child.previousRule(), child.assumptions(),
            value.capabilities().keySet(), (int) Math.min(Integer.MAX_VALUE, debt));
        Decision decision;
        MoveVerifier.Verification verification = null;
        if (ledger.total() > problem.budget().totalWork()) decision = Decision.WORK_LIMIT;
        else if (debt > problem.budget().maxComplexityDebt()) decision = Decision.COMPLEXITY_BOUND;
        else if (depth > problem.budget().maxPrimitiveSteps() || theory > problem.budget().maxTheoryWork()) decision = Decision.PATH_BOUND;
        else if (!problem.context().carries(move.assumptions(), node.state)) decision = Decision.ASSUMPTION_REJECTED;
        else if (visited.contains(new Identity(child, theory))) { decision = Decision.DUPLICATE; ledger.duplicates++; }
        else if (ledger.total() >= problem.budget().totalWork()) decision = Decision.WORK_LIMIT;
        else {
            verification = problem.verifier().verify(node.state, move, problem.context());
            ledger.verification = Math.addExact(ledger.verification, verification.work());
            decision = ledger.total() > problem.budget().totalWork() ? Decision.WORK_LIMIT
                : verification.accepted() ? Decision.ENQUEUED : Decision.PROOF_REJECTED;
        }
        return new Admission(child, theory, decision, verification, value);
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
