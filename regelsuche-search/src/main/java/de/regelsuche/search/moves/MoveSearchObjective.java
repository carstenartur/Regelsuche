package de.regelsuche.search.moves;

import java.util.List;
import java.util.Objects;

/** A per-run incumbent, updated only for the trusted input and mathematically admitted edges. */
final class MoveSearchObjective<S,M,V> implements de.regelsuche.retention.RetainedGraph.View {
    @Override public void retainedReferences(de.regelsuche.retention.RetainedGraph.Visitor v){v.reference(objective);v.reference(incumbent);v.reference(path);v.reference(witness);}
    private final java.util.function.Function<S,MoveSearch.ObjectiveScore> objective;
    private final long maximumOutputScore;
    private final boolean stopAtQuality;
    private S incumbent;
    private MoveWitnessPath<S,M,V> path = MoveWitnessPath.root();
    private long inputScore;
    private long outputScore;
    private long objectiveWork;
    private List<SearchExecution.Step<S,M,V>> witness = List.of();

    MoveSearchObjective(java.util.function.Function<S,MoveSearch.ObjectiveScore> objective, long maximumOutputScore) {
        this(objective,maximumOutputScore,true);
    }
    MoveSearchObjective(java.util.function.Function<S,MoveSearch.ObjectiveScore> objective,long maximumOutputScore,boolean stopAtQuality) {
        this.stopAtQuality=stopAtQuality;
        this.objective = Objects.requireNonNull(objective, "objective");
        this.maximumOutputScore = maximumOutputScore;
    }
    long observe(S state, MoveWitnessPath<S,M,V> candidatePath) {
        var assessment = Objects.requireNonNull(objective.apply(state), "objective assessment");
        objectiveWork = Math.addExact(objectiveWork, assessment.work());
        if (incumbent == null) inputScore = assessment.value();
        if (incumbent == null || assessment.value() < outputScore) {
            incumbent = state;
            outputScore = assessment.value();
            path = candidatePath;
        }
        return assessment.work();
    }
    boolean satisfied() { return stopAtQuality && incumbent != null && outputScore <= maximumOutputScore; }
    long finish(boolean materializationPaidByEnvironment) {
        witness = path.steps();
        // An unrelated outer observer cannot pay the historical search's own ledger.
        return materializationPaidByEnvironment ? 0 : witness.size() + 1L;
    }
    S incumbent() { return incumbent; }
    long inputScore() { return inputScore; }
    long outputScore() { return outputScore; }
    long objectiveWork() { return objectiveWork; }
    List<SearchExecution.Step<S,M,V>> witness() { return witness; }
}
