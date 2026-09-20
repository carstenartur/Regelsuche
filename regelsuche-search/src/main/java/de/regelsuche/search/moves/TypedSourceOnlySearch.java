package de.regelsuche.search.moves;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Source-only incumbent selection over the existing typed frontier; no second search algorithm.
 * Charges objective inspections, event/path scans and an additional full selected-path replay.
 * The caller supplies the measured objective; these logical units do not represent total CPU.
 */
public final class TypedSourceOnlySearch {
    public record Score(long value, long work) {
        public Score {
            if (work < 1) throw new IllegalArgumentException("objective inspection must report positive work");
        }
    }
    @FunctionalInterface public interface Objective { Score evaluate(TypedMoveSearch.State state); }
    public record Result(TypedMoveSearch.State incumbent, long inputScore, long outputScore,
            List<TypedMoveSearch.WitnessStep> witness, TypedMoveSearch.Result search,
            long selectionWork, long replayWork, long workBudget) {
        public Result { witness = List.copyOf(witness); }
        public long queryWork() { return Math.addExact(search.metrics().totalWork(), selectionWork); }
        public long totalWork() { return Math.addExact(queryWork(), replayWork); }
        public boolean withinBudget() { return totalWork() <= workBudget; }
        public boolean improved() { return outputScore < inputScore; }
    }
    /** Failed independent checking still owns the completed search and attempted replay work. */
    public static final class FinalCheckFailure extends IllegalStateException {
        private final Result attempted;
        private final MoveVerifier.Verification rejected;
        private FinalCheckFailure(Result attempted, ReplayFailure cause) {
            super("independent selected-path replay differs", cause); this.attempted = attempted; rejected = cause.rejected;
        }
        public Result attempted() { return attempted; }
        public MoveVerifier.Verification rejected() { return rejected; }
    }
    private static final class ReplayFailure extends IllegalStateException {
        private final long work;
        private final MoveVerifier.Verification rejected;
        private ReplayFailure(long work, MoveVerifier.Verification rejected) {
            super("independent selected-path replay differs"); this.work = work; this.rejected = rejected;
        }
    }
    /**
     * Online quality control in the same frontier. Objective and path materialization work is
     * already part of search.metrics(); final independent replay remains an additional charge.
     * A quality outcome is eligible only when withinBudget() also holds after that replay.
     */
    public Result searchUntil(TypedMoveSearch.Problem problem, Objective objective, long maximumOutputScore,
            SearchContinuationContract contract) {
        var online = new TypedMoveSearch().searchUntil(problem, objective, maximumOutputScore, contract);
        return finish(problem, online.incumbent(), online.inputScore(), online.outputScore(), online.witness(), online.search(), 0);
    }

    public Result search(TypedMoveSearch.Problem problem, Objective objective) {
        Objects.requireNonNull(problem, "problem");
        Objects.requireNonNull(objective, "objective");
        if (!problem.context().sourceOnly()) throw new IllegalArgumentException("source-only context required");
        var search = new TypedMoveSearch().search(problem);
        if (search.reached()) throw new IllegalStateException("source-only search reported a target hit");
        var root = search.initialState();
        var initial = Objects.requireNonNull(objective.evaluate(root), "initial objective");
        var incumbent = root;
        long best = initial.value(), selectionWork = initial.work();
        var parents = new HashMap<TypedMoveSearch.State, TypedMoveSearch.Event>();
        for (var event : search.events()) {
            selectionWork = Math.addExact(selectionWork, 1);
            if (event.decision() != MoveSearch.Decision.ENQUEUED) continue;
            if (event.verification() == null || !event.verification().accepted()) {
                throw new IllegalStateException("unverified state admitted to typed frontier");
            }
            parents.putIfAbsent(event.target(), event);
            var score = Objects.requireNonNull(objective.evaluate(event.target()), "candidate objective");
            selectionWork = Math.addExact(selectionWork, score.work());
            // Stable first-minimum selection: an equally good path never displaces the input.
            if (score.value() < best) {
                incumbent = event.target();
                best = score.value();
            }
        }
        var witness = new ArrayList<TypedMoveSearch.WitnessStep>();
        var cursor = incumbent;
        while (cursor.searchDepth() > 0) {
            selectionWork = Math.addExact(selectionWork, 1);
            var edge = parents.get(cursor);
            if (edge == null || edge.source().searchDepth() >= cursor.searchDepth()) {
                throw new IllegalStateException("incumbent has no acyclic retained lineage");
            }
            witness.add(new TypedMoveSearch.WitnessStep(edge.source(), edge.target(), edge.move(), edge.verification()));
            cursor = edge.source();
        }
        if (!cursor.equals(root)) throw new IllegalStateException("incumbent lineage does not start at the input");
        Collections.reverse(witness);
        return finish(problem, incumbent, initial.value(), best, witness, search, selectionWork);
    }

    private static Result finish(TypedMoveSearch.Problem problem, TypedMoveSearch.State incumbent, long inputScore,
            long outputScore, List<TypedMoveSearch.WitnessStep> witness, TypedMoveSearch.Result search, long selectionWork) {
        try {
            long work = replay(problem, search.initialState(), incumbent, witness);
            return new Result(incumbent, inputScore, outputScore, witness, search, selectionWork, work, problem.budget().totalWork());
        } catch (ReplayFailure failure) {
            throw new FinalCheckFailure(new Result(incumbent, inputScore, outputScore, witness, search, selectionWork,
                failure.work, problem.budget().totalWork()), failure);
        }
    }

    private static long replay(TypedMoveSearch.Problem problem, TypedMoveSearch.State root,
            TypedMoveSearch.State incumbent, List<TypedMoveSearch.WitnessStep> witness) {
        long replayWork = 0;
        var cursor = root;
        for (var step : witness) {
            if (!cursor.equals(step.source())) throw new IllegalStateException("broken incumbent lineage");
            var replay = problem.verifier().verify(cursor, step.move(), problem.context());
            replayWork = Math.addExact(replayWork, replay.work());
            if (!replay.accepted() || !replay.equals(step.verification())) {
                throw new ReplayFailure(replayWork, replay);
            }
            cursor = step.target();
        }
        if (!cursor.equals(incumbent)) throw new IllegalStateException("incumbent replay endpoint differs");
        return replayWork;
    }

}
