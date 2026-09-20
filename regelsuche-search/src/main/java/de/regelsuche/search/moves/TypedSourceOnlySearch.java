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
        public long totalWork() { return Math.addExact(search.metrics().totalWork(), Math.addExact(selectionWork, replayWork)); }
        public boolean withinBudget() { return totalWork() <= workBudget; }
        public boolean improved() { return outputScore < inputScore; }
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
        long replayWork = 0;
        cursor = root;
        for (var step : witness) {
            if (!cursor.equals(step.source())) throw new IllegalStateException("broken incumbent lineage");
            var replay = problem.verifier().verify(cursor, step.move(), problem.context());
            replayWork = Math.addExact(replayWork, replay.work());
            if (!replay.accepted() || !replay.equals(step.verification())) {
                throw new IllegalStateException("independent selected-path replay differs");
            }
            cursor = step.target();
        }
        if (!cursor.equals(incumbent)) throw new IllegalStateException("incumbent replay endpoint differs");
        // Extra inspections and replay can exceed the search budget. Retain that overrun,
        // never clamp it or convert an over-budget anytime candidate into a success.
        return new Result(incumbent, initial.value(), best, witness, search, selectionWork, replayWork, problem.budget().totalWork());
    }
}
