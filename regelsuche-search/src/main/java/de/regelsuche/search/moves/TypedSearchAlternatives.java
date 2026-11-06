package de.regelsuche.search.moves;

import de.regelsuche.ast.Expr;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Materializes alternative, independently replayed paths from one existing source-only search. */
public final class TypedSearchAlternatives {
    private TypedSearchAlternatives() { }

    /** Created only for an admitted state with a complete independent replay. */
    public static final class Candidate {
        private final TypedMoveSearch.State state;
        private final long score;
        private final List<TypedMoveSearch.WitnessStep> witness;
        private Candidate(TypedMoveSearch.State state, long score, List<TypedMoveSearch.WitnessStep> witness) {
            this.state = state; this.score = score; this.witness = List.copyOf(witness);
        }
        public TypedMoveSearch.State state() { return state; }
        public long score() { return score; }
        public List<TypedMoveSearch.WitnessStep> witness() { return witness; }
    }

    /** Search, selection and every additional replay share the original task's budget. */
    public static final class Result {
        private final TypedSourceOnlySearch.Result search;
        private final List<Candidate> candidates;
        private final long selectionWork, replayWork, workBudget;
        private final boolean truncated;
        private Result(TypedSourceOnlySearch.Result search, List<Candidate> candidates, long selectionWork,
                long replayWork, long workBudget, boolean truncated) {
            this.search = search; this.candidates = List.copyOf(candidates); this.selectionWork = selectionWork;
            this.replayWork = replayWork; this.workBudget = workBudget; this.truncated = truncated;
        }
        public TypedSourceOnlySearch.Result search() { return search; }
        public List<Candidate> candidates() { return candidates; }
        public long selectionWork() { return selectionWork; }
        public long replayWork() { return replayWork; }
        public long workBudget() { return workBudget; }
        public boolean truncated() { return truncated; }
        public long totalWork() { return Math.addExact(Math.addExact(search.totalWork(), selectionWork), replayWork); }
        public boolean accountingComplete() { return search.accountingComplete(); }
        public boolean withinBudget() { return accountingComplete() && totalWork() <= workBudget; }
    }

    /** A failing callback does not authorize a result or claim that its unfinished work is known. */
    public static final class Failure extends IllegalStateException {
        private final long knownWork;
        private Failure(long knownWork, RuntimeException cause) {
            super("alternative selection or independent replay failed; unfinished work may be unknown", cause);
            this.knownWork = knownWork;
        }
        public long knownWork() { return knownWork; }
        public boolean accountingComplete() { return false; }
    }

    static Result collect(TypedMoveSearch.Problem problem, TypedSourceOnlySearch.Objective objective,
            long threshold, SearchContinuationContract contract, long reserve, int maximum) {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(objective, "objective");
        Objects.requireNonNull(contract, "contract");
        long budget = problem.budget().totalWork();
        if (reserve < 1 || reserve >= budget || maximum < 1)
            throw new IllegalArgumentException("a positive in-budget reserve and alternative limit are required");
        var b = problem.budget();
        var searchBudget = new MoveSearch.Budget(b.maxPrimitiveSteps(), b.maxSearchDepth(), b.maxTheoryWork(),
            b.maxStates(), budget - reserve, b.maxComplexityDebt());
        var bounded = new TypedMoveSearch.Problem(problem.source(), problem.context(), problem.providers(), problem.policy(),
            problem.verifier(), problem.stateScore(), problem.mode(), problem.scheduling(), searchBudget, problem.stateValue());
        var search = new TypedSourceOnlySearch().searchBest(bounded, objective, contract);
        return new Collector(problem, objective, threshold, maximum, search).run();
    }

    private static final class Collector {
        /** Immutable admitted prefixes preserve the path resource omitted from MoveState. */
        private record Lineage(MoveSearch.Event edge, Lineage parent, long theoryWork) { }
        private final TypedMoveSearch.Problem problem;
        private final TypedSourceOnlySearch.Objective objective;
        private final long threshold, budget;
        private final int maximum;
        private final TypedSourceOnlySearch.Result search;
        private final List<Candidate> candidates = new ArrayList<>();
        private long selectionWork, replayWork;
        private Collector(TypedMoveSearch.Problem problem, TypedSourceOnlySearch.Objective objective, long threshold,
                int maximum, TypedSourceOnlySearch.Result search) {
            this.problem = problem; this.objective = objective; this.threshold = threshold; this.maximum = maximum;
            this.search = search; this.budget = problem.budget().totalWork();
        }
        private long total() { return Math.addExact(Math.addExact(search.totalWork(), selectionWork), replayWork); }
        private boolean spend(long work) {
            if (work < 0 || total() > budget || work > budget - total()) return false;
            selectionWork = Math.addExact(selectionWork, work);
            return true;
        }
        private Result result(boolean truncated) {
            return new Result(search, candidates, selectionWork, replayWork, budget, truncated);
        }
        private Result run() {
            try {
                if (!search.accountingComplete() || total() > budget) return result(true);
                var root = root();
                if (root == null) return result(true);
                var parents = parents(root);
                if (parents == null) return result(true);
                return result(!collect(root, parents));
            } catch (RuntimeException failure) {
                throw new Failure(total(), failure);
            }
        }
        private MoveState root() {
            MoveState root = null;
            // Inspect the full map: iteration order must not change charged work across JVMs.
            for (var state : search.search().encodedResult().stateAssessments().keySet()) {
                if (!spend(1)) return null;
                if (state.searchDepth() == 0) root = state;
            }
            if (root == null) throw new IllegalStateException("search has no root state");
            return root;
        }
        private HashMap<MoveState, Lineage> parents(MoveState root) {
            var parents = new HashMap<MoveState, Lineage>();
            parents.put(root, new Lineage(null, null, 0));
            for (var edge : search.search().encodedResult().events()) {
                if (!spend(1)) return null;
                if (edge.decision() != MoveSearch.Decision.ENQUEUED) continue;
                if (edge.verification() == null || !edge.verification().accepted())
                    throw new IllegalStateException("unverified state admitted to frontier");
                index(parents, edge);
            }
            return parents;
        }
        private void index(HashMap<MoveState, Lineage> parents, MoveSearch.Event edge) {
            var prefix = Objects.requireNonNull(parents.get(edge.source()), "admitted source lineage");
            long theory = Math.addExact(prefix.theoryWork(), edge.move().executionWork().exactTheoryWorkUnits());
            if (theory > problem.budget().maxTheoryWork())
                throw new IllegalStateException("admitted lineage exceeds the theory bound");
            var previous = parents.get(edge.target());
            if (previous == null || theory < previous.theoryWork())
                parents.put(edge.target(), new Lineage(edge, prefix, theory));
        }
        private boolean collect(MoveState root, HashMap<MoveState, Lineage> parents) {
            var seen = new HashSet<Expr>();
            for (var edge : search.search().encodedResult().events()) {
                if (!spend(1)) return false;
                if (edge.decision() != MoveSearch.Decision.ENQUEUED || edge.target().expression().equals(root.expression())) continue;
                if (!collect(edge, root, parents, seen) || candidates.size() == maximum) return false;
            }
            return true;
        }
        private boolean collect(MoveSearch.Event edge, MoveState root,
                HashMap<MoveState, Lineage> parents, HashSet<Expr> seen) {
            if (!spend(edge.target().expression().length())) return false;
            var state = TypedMoveSearch.State.decode(edge.target());
            if (seen.contains(state.expression())) return true;
            var score = Objects.requireNonNull(objective.evaluate(state), "objective assessment");
            selectionWork = Math.addExact(selectionWork, score.work());
            if (total() > budget) return false;
            if (score.value() > threshold) return true;
            var path = path(root, edge.target(), parents);
            if (path == null) return false;
            var witness = replay(path);
            if (witness == null) return false;
            seen.add(state.expression());
            candidates.add(new Candidate(state, score.value(), witness));
            return true;
        }
        private List<MoveSearch.Event> path(MoveState root, MoveState target, HashMap<MoveState, Lineage> parents) {
            var result = new ArrayList<MoveSearch.Event>();
            var cursor = target;
            var lineage = Objects.requireNonNull(parents.get(target), "candidate lineage");
            while (lineage.edge() != null) {
                if (!spend(1)) return null;
                var edge = lineage.edge();
                if (edge == null || !edge.target().equals(cursor) || edge.source().searchDepth() >= cursor.searchDepth())
                    throw new IllegalStateException("candidate has no acyclic admitted lineage");
                result.add(edge);
                cursor = edge.source();
                lineage = lineage.parent();
            }
            if (!cursor.equals(root)) throw new IllegalStateException("candidate lineage does not start at input");
            Collections.reverse(result);
            return result;
        }
        private List<TypedMoveSearch.WitnessStep> replay(List<MoveSearch.Event> path) {
            var result = new ArrayList<TypedMoveSearch.WitnessStep>();
            for (var edge : path) {
                long decodeWork = Math.addExact((long) edge.source().expression().length(), edge.target().expression().length());
                if (!spend(Math.addExact(decodeWork, 1))) return null;
                var source = TypedMoveSearch.State.decode(edge.source());
                var target = TypedMoveSearch.State.decode(edge.target());
                var receipt = Objects.requireNonNull(problem.verifier().verify(source, edge.move(), problem.context()), "verification");
                replayWork = Math.addExact(replayWork, receipt.work());
                if (total() > budget) return null;
                var original = edge.verification();
                if (!receipt.accepted() || !receipt.receipts().equals(original.receipts()) || !receipt.reason().equals(original.reason()))
                    throw new IllegalStateException("independent alternative replay differs");
                result.add(new TypedMoveSearch.WitnessStep(source, target, edge.move(), receipt));
            }
            return result;
        }
    }
}
