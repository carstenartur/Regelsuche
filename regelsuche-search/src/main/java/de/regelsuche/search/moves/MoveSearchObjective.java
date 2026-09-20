package de.regelsuche.search.moves;

import java.util.List;
import java.util.Objects;

/** A per-run incumbent, updated only for the trusted input and mathematically admitted edges. */
final class MoveSearchObjective {
    private final MoveSearch.Objective objective;
    private final long maximumOutputScore;
    private MoveState incumbent;
    private MoveWitnessPath path = MoveWitnessPath.ROOT;
    private long inputScore;
    private long outputScore;
    private long objectiveWork;
    private List<MoveSearch.WitnessStep> witness = List.of();

    MoveSearchObjective(MoveSearch.Objective objective, long maximumOutputScore) {
        this.objective = Objects.requireNonNull(objective, "objective");
        this.maximumOutputScore = maximumOutputScore;
    }
    long observe(MoveState state, MoveWitnessPath candidatePath) {
        var assessment = Objects.requireNonNull(objective.evaluate(state), "objective assessment");
        objectiveWork = Math.addExact(objectiveWork, assessment.work());
        if (incumbent == null) inputScore = assessment.value();
        if (incumbent == null || assessment.value() < outputScore) {
            incumbent = state;
            outputScore = assessment.value();
            path = candidatePath;
        }
        return assessment.work();
    }
    boolean satisfied() { return incumbent != null && outputScore <= maximumOutputScore; }
    long finish() {
        witness = path.steps();
        return witness.size() + 1L;
    }
    MoveState incumbent() { return incumbent; }
    long inputScore() { return inputScore; }
    long outputScore() { return outputScore; }
    long objectiveWork() { return objectiveWork; }
    List<MoveSearch.WitnessStep> witness() { return witness; }
}
