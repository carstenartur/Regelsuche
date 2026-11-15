package de.regelsuche.egraph;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-run statistics collected by {@link EqualitySaturation}, exposed so
 * the search strategy can report them through the UI / report layer.
 *
 * <p>The fields mirror the bookkeeping callers of the egg library are
 * used to seeing — number of e-classes / e-nodes in the final graph,
 * how many union/merge operations were performed, how many saturation
 * iterations were run, which rules fired and how often, and the textual
 * form of the cheapest extracted expression (also surfaced as {@link
 * #extractedBest()} so report renderers do not need to re-extract).</p>
 */
public record SaturationStats(
    int eclasses,
    int enodes,
    int merges,
    int iterations,
    Map<String, Integer> appliedRules,
    String extractedBest,
    boolean saturated,
    Reason stopReason,
    long classesScanned,
    long nodesScanned,
    long candidateClassesSkipped,
    long matchesFound,
    long matcherCacheHits,
    long matcherCacheMisses,
    int saturationIterations,
    int rulesFired
) {

    /** Why saturation stopped. */
    public enum Reason {
        /** All active native rules reached a relative fix-point without failures or incomplete matches. */
        FIX_POINT,
        /** Hit the configured iteration budget. */
        ITERATION_BUDGET,
        /** Hit the configured node-count guard. */
        NODE_BUDGET,
        /** No further progress, but rule failures prevented a certified fix-point. */
        RULE_FAILURES,
        /** No further progress, but an owned concrete matcher exhausted its search bound. */
        MATCHER_LIMIT,
        /** A guard remained undecided in the run's fixed assumption context. */
        UNDECIDED_GUARDS,
        /** A conditional application would exceed the retained guard decision budget. */
        GUARD_BUDGET,
        /** Concrete callbacks inspect only selected child representatives. Closure is unverified. */
        INCOMPLETE_REFERENCE_BRIDGE
    }

    public SaturationStats {
        appliedRules = appliedRules == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(appliedRules));
    }

    public SaturationStats(
        int eclasses,
        int enodes,
        int merges,
        int iterations,
        Map<String, Integer> appliedRules,
        String extractedBest,
        boolean saturated,
        Reason stopReason
    ) {
        this(
            eclasses,
            enodes,
            merges,
            iterations,
            appliedRules,
            extractedBest,
            saturated,
            stopReason,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            iterations,
            appliedRules == null ? 0 : appliedRules.values().stream().mapToInt(Integer::intValue).sum()
        );
    }

    /** Total number of rule applications across all iterations. */
    public int totalApplications() {
        return appliedRules.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Distinct rules that fired at least once. */
    public int distinctRulesFired() {
        return appliedRules.size();
    }
}
