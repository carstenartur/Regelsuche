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
    Reason stopReason
) {

    /** Why saturation stopped. */
    public enum Reason {
        /** No rule fired in the last iteration — fix-point reached. */
        FIX_POINT,
        /** Hit the configured iteration budget. */
        ITERATION_BUDGET,
        /** Hit the configured node-count guard. */
        NODE_BUDGET
    }

    public SaturationStats {
        appliedRules = appliedRules == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(appliedRules));
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
