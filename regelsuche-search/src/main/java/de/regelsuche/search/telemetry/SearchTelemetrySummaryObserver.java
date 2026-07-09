package de.regelsuche.search.telemetry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Observer that aggregates live/replay counters from deterministic search telemetry events. */
public final class SearchTelemetrySummaryObserver implements SearchObserver {
    private final String targetCanonicalHash;
    private final Map<Integer, Long> depthHistogram = new LinkedHashMap<>();
    private final Map<String, Long> ruleUsage = new LinkedHashMap<>();

    private long totalEvents;
    private long visitedStates;
    private long generatedTransformations;
    private long enqueuedStates;
    private long prunedDuplicates;
    private long prunedTranspositions;
    private long prunedByDepth;
    private long prunedByBudget;
    private int maxDepthReached;
    private int maxFrontierSize;
    private int finalFrontierSize;
    private int finalVisitedCount;
    private int exploredStates;
    private long targetNearStates;
    private boolean targetReached;

    public SearchTelemetrySummaryObserver() {
        this("");
    }

    public SearchTelemetrySummaryObserver(String targetCanonicalHash) {
        this.targetCanonicalHash = targetCanonicalHash == null ? "" : targetCanonicalHash;
    }

    @Override
    public synchronized void onEvent(SearchEvent event) {
        Objects.requireNonNull(event, "event");
        totalEvents++;
        maxDepthReached = Math.max(maxDepthReached, event.depth());
        maxFrontierSize = Math.max(maxFrontierSize, event.frontierSize());
        depthHistogram.merge(event.depth(), 1L, Long::sum);
        if (!targetCanonicalHash.isBlank() && targetCanonicalHash.equals(event.canonicalHash())) {
            targetNearStates++;
            if (event.type() == SearchEventType.STATE_VISITED || event.type() == SearchEventType.STATE_ENQUEUED) {
                targetReached = true;
            }
        }

        switch (event.type()) {
            case STATE_VISITED -> visitedStates++;
            case TRANSFORMATION_GENERATED -> {
                generatedTransformations++;
                if (!event.ruleId().isBlank()) {
                    ruleUsage.merge(event.ruleId(), 1L, Long::sum);
                }
            }
            case STATE_ENQUEUED -> enqueuedStates++;
            case STATE_PRUNED_DUPLICATE -> prunedDuplicates++;
            case STATE_PRUNED_TRANSPOSITION -> prunedTranspositions++;
            case STATE_PRUNED_DEPTH -> prunedByDepth++;
            case STATE_PRUNED_BUDGET -> prunedByBudget++;
            case SEARCH_FINISHED -> {
                finalFrontierSize = event.frontierSize();
                finalVisitedCount = event.visitedCount();
                exploredStates = event.generatedCount();
            }
            default -> {
                // No-op.
            }
        }
    }

    public synchronized SearchTelemetrySummary summary() {
        return new SearchTelemetrySummary(
            totalEvents,
            visitedStates,
            generatedTransformations,
            enqueuedStates,
            prunedDuplicates,
            prunedTranspositions,
            prunedByDepth,
            prunedByBudget,
            maxDepthReached,
            maxFrontierSize,
            finalFrontierSize,
            finalVisitedCount,
            exploredStates,
            targetCanonicalHash,
            targetReached,
            targetNearStates,
            depthHistogram,
            ruleUsage
        );
    }
}
