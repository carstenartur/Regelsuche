package de.regelsuche.benchmark;

import de.regelsuche.validation.CandidateProofStatus;

/**
 * Per-row result of a single benchmark scenario.
 *
 * <p>In addition to the bare search metrics ({@code exploredStates},
 * {@code bestImprovement}, …) every row now carries a small set of
 * <em>quality</em> metrics that the dashboard groups under "Qualität" so a
 * single glance at the benchmark report tells you whether a strategy is
 * actually useful, not just fast:</p>
 *
 * <ul>
 *   <li>{@link #expectedResultMatched()} — did the produced best expression
 *       match the scenario's expected normal form? ({@code null} means the
 *       scenario doesn't pin an expectation.)</li>
 *   <li>{@link #prunedStates()} — number of states the strategy pruned
 *       (transposition table, dominance, …); higher = less wasted work.</li>
 *   <li>{@link #eGraphClasses()} / {@link #eGraphNodes()} —
 *       size of the e-graph for the EQUALITY_SATURATION strategy.</li>
 *   <li>{@link #saturationSavings()} — fraction of duplicated rewrites the
 *       e-graph collapsed (only meaningful for saturation runs).</li>
 *   <li>{@link #learnedRuleUsed()} — true if at least one applied rule came
 *       from the learned macro inventory.</li>
 *   <li>{@link #exportBundleValid()} — did the export bundle for this row
 *       round-trip through the export pipeline successfully?</li>
 * </ul>
 */
public record SearchBenchmarkResult(
    String strategyName,
    String expression,
    int exploredStates,
    int bestImprovement,
    int shortestImprovingDepth,
    int expandedSteps,
    int distinctRules,
    long elapsedMillis,
    CandidateProofStatus proofStatus,
    Boolean expectedResultMatched,
    int prunedStates,
    int eGraphClasses,
    int eGraphNodes,
    double saturationSavings,
    long classesScanned,
    long nodesScanned,
    long candidateClassesSkipped,
    long matchesFound,
    long matcherCacheHits,
    long matcherCacheMisses,
    int saturationIterations,
    int rulesFired,
    boolean learnedRuleUsed,
    boolean exportBundleValid
) {
    public SearchBenchmarkResult {
        if (proofStatus == null) {
            proofStatus = CandidateProofStatus.OBSERVED;
        }
    }

    /**
     * Reduced-arg constructor (pre-quality-metrics) preserved for the math-domain
     * scenarios. Fills the quality fields with neutral defaults.
     */
    public SearchBenchmarkResult(
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
        this(strategyName, expression, exploredStates, bestImprovement,
            shortestImprovingDepth, expandedSteps, distinctRules,
            elapsedMillis, proofStatus,
            /* expectedResultMatched */ null,
            /* prunedStates          */ 0,
            /* eGraphClasses         */ 0,
            /* eGraphNodes           */ 0,
            /* saturationSavings     */ 0.0,
            /* classesScanned        */ 0L,
            /* nodesScanned          */ 0L,
            /* candidateClassesSkipped */ 0L,
            /* matchesFound          */ 0L,
            /* matcherCacheHits      */ 0L,
            /* matcherCacheMisses    */ 0L,
            /* saturationIterations  */ 0,
            /* rulesFired            */ 0,
            /* learnedRuleUsed       */ false,
            /* exportBundleValid     */ true);
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

    /** Alias for {@link #exploredStates()} matching the spec's vocabulary. */
    public int visitedStates() {
        return exploredStates;
    }

    /**
     * Convenience accessor for the dashboard's "Ampel" column. Translates the
     * quality fields into a single OK/WARN/FAIL label.
     */
    public String qualityLabel() {
        if (!found()) return "FAIL";
        if (Boolean.FALSE.equals(expectedResultMatched)) return "FAIL";
        if (!exportBundleValid) return "WARN";
        if (Boolean.TRUE.equals(expectedResultMatched)) return "OK";
        return "OK";
    }
}
