package de.regelsuche.benchmark;

import de.regelsuche.mining.CandidateProofStatus;

public record SearchBenchmarkResult(
    String strategyName,
    String expression,
    int exploredStates,
    int bestImprovement,
    int shortestImprovingDepth,
    int expandedSteps,
    int distinctRules,
    long elapsedMillis,
    CandidateProofStatus proofStatus
) {
    public SearchBenchmarkResult {
        if (proofStatus == null) {
            proofStatus = CandidateProofStatus.OBSERVED;
        }
    }

    /**
     * Legacy constructor preserved for backwards compatibility with tests
     * that don't care about runtime / proof status. Defaults
     * {@code elapsedMillis} to zero and {@code proofStatus} to
     * {@link CandidateProofStatus#OBSERVED}.
     */
    public SearchBenchmarkResult(
        String strategyName,
        String expression,
        int exploredStates,
        int bestImprovement,
        int shortestImprovingDepth,
        int expandedSteps,
        int distinctRules
    ) {
        this(strategyName, expression, exploredStates, bestImprovement,
            shortestImprovingDepth, expandedSteps, distinctRules,
            0L, CandidateProofStatus.OBSERVED);
    }

    /** Did the search produce at least one improving state? */
    public boolean found() {
        return bestImprovement > 0;
    }
}

