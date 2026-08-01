package de.regelsuche.evolution;

import java.util.Objects;

/** Complete paired evidence for one frozen VALIDATION case. */
public record EvolutionValidationCaseEvidence(
    String caseId,
    String family,
    boolean baselineReached,
    boolean candidateReached,
    EvolutionCorrectnessStatus baselineCorrectness,
    EvolutionCorrectnessStatus candidateCorrectness,
    String baselineTerminalReason,
    String candidateTerminalReason,
    int baselineDepth,
    int candidateDepth,
    long baselineExploredStates,
    long candidateExploredStates,
    long baselineCandidateEvaluations,
    long candidateCandidateEvaluations,
    boolean newlySolved,
    boolean reachabilityRegression,
    boolean correctnessFailure,
    boolean correctnessRegression
) {
    public EvolutionValidationCaseEvidence {
        EvolutionValidationArtifactSupport.requireText(caseId, "caseId");
        EvolutionValidationArtifactSupport.requireText(family, "family");
        Objects.requireNonNull(baselineCorrectness, "baselineCorrectness");
        Objects.requireNonNull(candidateCorrectness, "candidateCorrectness");
        EvolutionValidationArtifactSupport.requireText(
            baselineTerminalReason, "baselineTerminalReason");
        EvolutionValidationArtifactSupport.requireText(
            candidateTerminalReason, "candidateTerminalReason");
        if (baselineDepth < -1 || candidateDepth < -1
                || baselineExploredStates < 0
                || candidateExploredStates < 0
                || baselineCandidateEvaluations < 0
                || candidateCandidateEvaluations < 0) {
            throw new IllegalArgumentException(
                "validation case measurements are outside bounded ranges");
        }
        requireReachabilityConsistency(
            baselineReached, baselineDepth, baselineCorrectness, "baseline");
        requireReachabilityConsistency(
            candidateReached, candidateDepth, candidateCorrectness, "candidate");
        boolean expectedNewlySolved = !baselineReached
            && candidateReached
            && candidateCorrectness == EvolutionCorrectnessStatus.CONFIRMED;
        if (newlySolved != expectedNewlySolved) {
            throw new IllegalArgumentException(
                "newlySolved differs from retained reachability/correctness");
        }
        if (reachabilityRegression
                != (baselineReached && !candidateReached)) {
            throw new IllegalArgumentException(
                "reachabilityRegression differs from retained reachability");
        }
        boolean expectedCorrectnessFailure = candidateReached
            && candidateCorrectness == EvolutionCorrectnessStatus.REFUTED;
        if (correctnessFailure != expectedCorrectnessFailure) {
            throw new IllegalArgumentException(
                "correctnessFailure differs from retained correctness evidence");
        }
        boolean expectedCorrectnessRegression = baselineReached
            && baselineCorrectness == EvolutionCorrectnessStatus.CONFIRMED
            && candidateReached
            && candidateCorrectness == EvolutionCorrectnessStatus.REFUTED;
        if (correctnessRegression != expectedCorrectnessRegression) {
            throw new IllegalArgumentException(
                "correctnessRegression differs from retained correctness evidence");
        }
    }

    private static void requireReachabilityConsistency(
        boolean reached,
        int depth,
        EvolutionCorrectnessStatus correctness,
        String side
    ) {
        if (reached && depth < 0) {
            throw new IllegalArgumentException(
                side + " reached target but has no path depth");
        }
        if (!reached && depth != -1) {
            throw new IllegalArgumentException(
                side + " did not reach target but retains a path depth");
        }
        if (reached
                && correctness == EvolutionCorrectnessStatus.NOT_EVALUATED) {
            throw new IllegalArgumentException(
                side + " reached target without correctness evidence");
        }
        if (!reached
                && correctness != EvolutionCorrectnessStatus.NOT_EVALUATED) {
            throw new IllegalArgumentException(
                side + " has correctness evidence without reaching target");
        }
    }
}
