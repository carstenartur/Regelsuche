package de.regelsuche.search.telemetry;

import java.util.Map;
import java.util.TreeMap;

/** Aggregated counters derived from runtime search telemetry events. */
public record SearchTelemetrySummary(
    long totalEvents,
    long visitedStates,
    long generatedTransformations,
    long enqueuedStates,
    long prunedDuplicates,
    long prunedTranspositions,
    long prunedByDepth,
    long prunedByBudget,
    int maxDepthReached,
    int maxFrontierSize,
    int finalFrontierSize,
    int finalVisitedCount,
    int exploredStates,
    String targetCanonicalHash,
    boolean targetReached,
    long targetNearStates,
    Map<Integer, Long> depthHistogram,
    Map<String, Long> ruleUsage
) {
    public SearchTelemetrySummary {
        targetCanonicalHash = targetCanonicalHash == null ? "" : targetCanonicalHash;
        depthHistogram = Map.copyOf(new TreeMap<>(depthHistogram == null ? Map.of() : depthHistogram));
        ruleUsage = Map.copyOf(new TreeMap<>(ruleUsage == null ? Map.of() : ruleUsage));
    }
}
