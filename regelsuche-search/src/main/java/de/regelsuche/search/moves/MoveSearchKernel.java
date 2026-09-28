package de.regelsuche.search.moves;

import de.regelsuche.retention.RetainedGraph;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.ToDoubleFunction;

import static de.regelsuche.search.moves.MoveSearch.*;

/** The single frontier implementation, shared by legacy and object-native facades. */
final class MoveSearchKernel<E,S extends SearchExecution.Position<E>,M extends SearchExecution.Edge<E,M>,A extends SearchExecution.Assessment<E>,V extends SearchExecution.Verification> implements RetainedGraph.View {
    @Override public void retainedReferences(RetainedGraph.Visitor v) {}
    private final class Node implements RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(MoveSearchKernel.this);v.reference(state);v.reference(value);v.reference(path);v.reference(picker);}
        final S state;
        final A value;
        final long theoryWork;
        final MoveWitnessPath<S,M,V> path;
        SearchExecution.Picker<M> picker;
        long measured;
        long measuredMathematics;
        int generated;
        int capturedBatchReceipts;
        int enqueued;
        int pulls;
        Node(S state, long theoryWork, MoveWitnessPath<S,M,V> path, A value) { this.state = state; this.theoryWork = theoryWork; this.path = path; this.value = value; }
    }
    private final class Ticket implements RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(MoveSearchKernel.this);v.reference(node);}
        private final Node node; private final double priority; private final long serial;
        Ticket(Node node,double priority,long serial) { this.node=node;this.priority=priority;this.serial=serial; }
        Node node(){return node;} double priority(){return priority;} long serial(){return serial;}
    }
    private final class Ledger implements RetainedGraph.View {
        SearchExecution.Environment<E,S,M,A,V> workOwner;
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(MoveSearchKernel.this);v.reference(matches);v.reference(objective);v.reference(workOwner);v.reference(batchReceipts);}
        long primitive, search, verification, consumed, discarded, duplicates, deadEnds, explored, expanded, generated;
        final Map<String, Long> matches = new TreeMap<>();
        final List<IncrementalProviderContract.Snapshot> batchReceipts=new ArrayList<>();
        MoveSearchObjective<S,M,V> objective;
        void observe(S state, MoveWitnessPath<S,M,V> path) {
            if (objective != null) search = Math.addExact(search, objective.observe(state, path));
        }
        boolean qualityReached() { return objective != null && objective.satisfied(); }
        long total() { return Math.addExact(Math.addExact(Math.addExact(primitive, search), verification),workOwner==null?0:workOwner.additionalWork()); }
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
            var receipts=node.picker.batchCursorReceipts();
            for(int i=node.capturedBatchReceipts;i<receipts.size();i++) {
                batchReceipts.add(receipts.get(i));
                de.regelsuche.retention.RetainedOperation.work(1);
            }
            node.capturedBatchReceipts=receipts.size();
        }
    }

    SearchExecution.Result<S,M,V,A> search(SearchExecution.Environment<E,S,M,A,V> problem,
            SearchContinuationContract contract, MoveSearchObjective<S,M,V> objective) {
        java.util.Objects.requireNonNull(contract);
        if (contract != SearchContinuationContract.PATH_SENSITIVE && problem.mode() != Mode.FAST)
            throw new IllegalArgumentException("continuation dominance requires FAST mode");
        return new SearchRun(problem,contract,objective).run();
    }

    /** Mutable state of one invocation of the existing frontier, never shared between searches. */
    private final class LedgerCharge implements java.util.function.LongConsumer,RetainedGraph.View {
        private final Ledger ledger;
        LedgerCharge(Ledger ledger){this.ledger=ledger;}
        @Override public void accept(long work){ledger.search=Math.addExact(ledger.search,work);}
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(MoveSearchKernel.this);v.reference(ledger);}
    }
    private final class TicketOrder implements Comparator<Ticket>,RetainedGraph.View {
        @Override public int compare(Ticket a,Ticket b){int priority=Double.compare(a.priority(),b.priority());return priority==0?Long.compare(a.serial(),b.serial()):priority;}
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(MoveSearchKernel.this);}
    }
    private final class SearchRun implements RetainedGraph.View {
        private Node active;
        @Override public void retainedReferences(RetainedGraph.Visitor v){
            v.reference(MoveSearchKernel.this);v.reference(problem);v.reference(contract);v.reference(ledger);
            v.reference(events);v.reference(deadEnds);v.reference(reached);v.reference(visited);v.reference(assessments);
            v.reference(frontier);v.reference(budget);v.reference(serial);v.reference(opened);v.reference(witness);v.reference(outcome);v.reference(active);
        }
        private final SearchExecution.Environment<E,S,M,A,V> problem;
        private final SearchContinuationContract contract;
        private final Budget budget;
        private final Ledger ledger = new Ledger();
        private final List<SearchExecution.Event<S,M,V>> events = new ArrayList<>();
        private final List<S> deadEnds = new ArrayList<>();
        private final Set<S> reached = new HashSet<>();
        private final MoveSearchVisits<S> visited;
        private final Map<S, A> assessments = new java.util.HashMap<>();
        private final PriorityQueue<Ticket> frontier = new PriorityQueue<>(
            new TicketOrder());
        private final long[] serial = {0};
        private final List<Node> opened = new ArrayList<>();
        private boolean complete;
        private boolean stopped;
        private Outcome outcome = Outcome.BOUNDED_EXHAUSTED;
        private List<SearchExecution.Step<S,M,V>> witness = List.of();
        private int hit = -1;
        private int primitiveHit = -1;

        SearchRun(SearchExecution.Environment<E,S,M,A,V> problem, SearchContinuationContract contract, MoveSearchObjective<S,M,V> objective) {
            this.problem = problem;
            this.contract = contract;
            budget = problem.budget();
            ledger.objective = objective;ledger.workOwner=problem;
            visited = new MoveSearchVisits<>(contract, new LedgerCharge(ledger));
            complete = contract == SearchContinuationContract.PATH_SENSITIVE;
        }
        private void initialize() {
            problem.ownership(this);problem.checkpoint();
            var root = problem.state(problem.source(), 0, 0, "", problem.initialAssumptions(), Set.of(), 0);
            var rootValue = inspect(problem, root, ledger);
            root = problem.state(root.expression(), 0, 0, "", root.assumptions(), rootValue.capabilities().keySet(), 0);
            assessments.put(root, rootValue);
            ledger.observe(root, MoveWitnessPath.root());
            frontier.add(new Ticket(new Node(root, 0, MoveWitnessPath.root(), rootValue), 0, serial[0]++));
            visited.add(root, 0);
            problem.checkpoint();
        }

        SearchExecution.Result<S,M,V,A> run() {
            try {
                initialize();
                while (!stopped && !frontier.isEmpty()) {
                    advance();problem.checkpoint();
                    if(!problem.ownershipComplete())stop(Outcome.INCONCLUSIVE);
                }
            } catch(SearchExecution.ResourceLimit exhausted) {
                var unreturned=exhausted.takeWork();
                ledger.search=Math.addExact(ledger.search,unreturned.mechanical());
                ledger.primitive=Math.addExact(ledger.primitive,unreturned.mathematics().canonicalWorkUnits());
                ledger.verification=Math.addExact(ledger.verification,unreturned.verification());
                stop(Outcome.INCONCLUSIVE);
            }
            finally {
                closeIncremental(opened, ledger);
                if(active!=null && active.picker!=null && !active.picker.incremental())ledger.collect(active);
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
            var node = frontier.remove().node();active=node;
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
            if (node.state.expression().equals(problem.goal())) {
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
            node.picker.initialize();
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

        private SearchExecution.Result<S,M,V,A> finish() {
            var objective = ledger.objective;
            if (objective != null) {
                ledger.search = Math.addExact(ledger.search, objective.finish());
                if (outcome == Outcome.QUALITY_REACHED) witness = objective.witness();
            }
            if (cleanupOverrun(problem, ledger) || ((objective != null || problem.additionalWork()>0) && ledger.total() > budget.totalWork())) {
                stop(Outcome.WORK_EXHAUSTED);
                witness = List.of();
                hit = -1;
                primitiveHit = -1;
            }
            if(!problem.ownershipComplete()) { stop(Outcome.INCONCLUSIVE);witness=List.of();hit=-1;primitiveHit=-1; }
            var receipts = opened.stream().map(node -> node.picker.executionReceipt()).toList();
            boolean accountingComplete = problem.ownershipComplete() && opened.stream().allMatch(node -> node.picker.accountingComplete())
                && ledger.batchReceipts.stream().allMatch(receipt->receipt.accountingComplete() && receipt.status()!=IncrementalProviderContract.Status.FAILED);
            if (!accountingComplete && outcome != Outcome.WORK_EXHAUSTED) {
                stop(Outcome.INCONCLUSIVE); witness = List.of(); hit = -1; primitiveHit = -1;
            }
            if (outcome == Outcome.BOUNDED_EXHAUSTED && !complete) outcome = Outcome.INCONCLUSIVE;
            return new SearchExecution.Result<>(outcome, witness, events, reached, deadEnds, new Metrics(ledger.generated, ledger.consumed, ledger.discarded,
                ledger.generated - ledger.consumed, ledger.duplicates, ledger.deadEnds, ledger.explored, ledger.expanded,
                ledger.primitive, ledger.search, ledger.verification, hit, primitiveHit, ledger.matches), complete, assessments,
                receipts,ledger.batchReceipts);
        }
    }

    private SearchExecution.Picker<M> picker(SearchExecution.Environment<E,S,M,A,V> problem,S state) { return problem.picker(state); }
    private void retainIncremental(Node node,List<Node> opened) { if (node.picker.incremental()) opened.add(node); }
    private void closeIncremental(List<Node> opened,Ledger ledger) {
        for (var node:opened) { node.picker.close(); ledger.collect(node); }
    }
    private boolean cleanupOverrun(SearchExecution.Environment<E,S,M,A,V> problem,Ledger ledger) {
        return (problem.scheduling()==Scheduling.INCREMENTAL_NATIVE_ORDER || problem.scheduling()==Scheduling.STAGED_INCREMENTAL)
            && ledger.total()>problem.budget().totalWork();
    }
    private enum Expansion { MORE, EXHAUSTED, REJECTED_PROOF, WORK_LIMIT }
    private Expansion expand(SearchExecution.Environment<E,S,M,A,V> problem, Node node, Ledger ledger, List<SearchExecution.Event<S,M,V>> events,
            MoveSearchVisits<S> visited, PriorityQueue<Ticket> frontier, long[] serial, Map<S, A> assessments) {
        if (problem.scheduling() == Scheduling.STAGED_INCREMENTAL) ledger.collect(node);
        var next = node.picker.incremental()
            ? node.picker.next(Math.max(0, problem.budget().totalWork() - ledger.total())) : node.picker.next();
        ledger.collect(node); node.pulls++;
        // Atomic providers report actual overrun; such runs cannot claim a budget-respecting success.
        if (ledger.total() > problem.budget().totalWork()) return Expansion.WORK_LIMIT;
        if (node.picker.incremental() && node.picker.workExhausted()) return Expansion.WORK_LIMIT;
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
            var path = node.path.append(new SearchExecution.Step<>(node.state, child, move, verification));
            frontier.add(new Ticket(new Node(child, admission.theoryWork(), path, admission.value()),
                child.expression().equals(problem.goal()) ? -Double.MAX_VALUE : priority(problem, child, admission.value()), serial[0]++));
            ledger.observe(child, path);
        }
        if (decision != Decision.ENQUEUED) ledger.discarded++;
        events.add(new SearchExecution.Event<>(node.state, child, move, decision, verification));
        if (decision == Decision.WORK_LIMIT) return Expansion.WORK_LIMIT;
        if (ledger.qualityReached()) return Expansion.MORE;
        int stage = node.picker.nextStage();
        // Parent widening stays on the frontier, so promising children can finish before later stages open.
        double continuation = problem.mode() == Mode.COMPLETE_BOUNDED_REFERENCE ? node.state.searchDepth()
            : priority(problem, node.state, node.value) + 1 + stage + node.pulls / 2.0;
        frontier.add(new Ticket(node, continuation, serial[0]++)); ledger.search++;
        return proofRejected ? Expansion.REJECTED_PROOF : Expansion.MORE;
    }
    private final class Admission {
        private final S child; private final long theoryWork; private final Decision decision; private final V verification; private final A value;
        Admission(S child,long theoryWork,Decision decision,V verification,A value){this.child=child;this.theoryWork=theoryWork;this.decision=decision;this.verification=verification;this.value=value;}
        S child(){return child;} long theoryWork(){return theoryWork;} Decision decision(){return decision;} V verification(){return verification;} A value(){return value;}
    }
    private Admission admit(SearchExecution.Environment<E,S,M,A,V> problem, Node node, M move, MoveSearchVisits<S> visited, Ledger ledger) {
        var step = move;
        long depth = (long) node.state.primitiveDepth() + step.primitiveStepCount();
        long theory = Math.addExact(node.theoryWork, step.executionWork().exactTheoryWorkUnits());
        var child = problem.state(step.targetExpression(), node.state.searchDepth() + 1,
            (int) Math.min(Integer.MAX_VALUE, depth), move.ruleId(), node.state.assumptions(), Set.of(), 0);
        var value = inspect(problem, child, ledger);
        long debt = Math.max(0L, (long) node.state.complexityDebt() + value.complexity() - node.value.complexity());
        child = problem.state(child.expression(), child.searchDepth(), child.primitiveDepth(), child.previousRule(), child.assumptions(),
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
        var verification = problem.verify(node.state, move);
        ledger.verification = Math.addExact(ledger.verification, verification.work());
        decision = ledger.total() > problem.budget().totalWork() ? Decision.WORK_LIMIT
            : verification.accepted() ? Decision.ENQUEUED : Decision.PROOF_REJECTED;
        return new Admission(child, theory, decision, verification, value);
    }

    /** A rejected bound must not trigger a paid visited lookup or a proof attempt. */
    private Decision rejectBounds(SearchExecution.Environment<E,S,M,A,V> problem, Node node, M move,
            long depth, long theory, long debt, Ledger ledger) {
        if (ledger.total() > problem.budget().totalWork()) return Decision.WORK_LIMIT;
        if (debt > problem.budget().maxComplexityDebt()) return Decision.COMPLEXITY_BOUND;
        if (depth > problem.budget().maxPrimitiveSteps() || theory > problem.budget().maxTheoryWork()) return Decision.PATH_BOUND;
        if (!problem.carries(move.assumptions(), node.state)) return Decision.ASSUMPTION_REJECTED;
        return null;
    }

    private A inspect(SearchExecution.Environment<E,S,M,A,V> problem, S state, Ledger ledger) {
        var value = problem.inspect(state);
        if (value.capabilities().values().stream().anyMatch(capability -> !capability.sourceExpression().equals(state.expression())))
            throw new IllegalArgumentException("capability evidence differs from source state");
        ledger.search = Math.addExact(ledger.search, value.searchWork());
        ledger.primitive = Math.addExact(ledger.primitive, value.primitiveWork());
        return value;
    }
    private double priority(SearchExecution.Environment<E,S,M,A,V> problem, S state, A value) {
        double score = problem.mode() == Mode.COMPLETE_BOUNDED_REFERENCE ? state.searchDepth() : problem.score(state) - value.value();
        if (!Double.isFinite(score)) throw new IllegalArgumentException("nonfinite state priority");
        return score;
    }
}
