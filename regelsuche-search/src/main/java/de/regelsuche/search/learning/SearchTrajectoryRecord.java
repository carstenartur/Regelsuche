package de.regelsuche.search.learning;

import de.regelsuche.search.learning.SearchTrajectoryContext.DatasetSplit;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalStatus;
import de.regelsuche.search.telemetry.SearchEventType;
import de.regelsuche.transform.RewriteKind;
import java.util.List;
import java.util.Objects;

/** One post-labelled deterministic search event in the learning dataset. */
public record SearchTrajectoryRecord(
    String schema,
    String producerVersion,
    String runId,
    String family,
    DatasetSplit split,
    String ruleInventoryHash,
    long sequence,
    SearchEventType eventType,
    ExpressionFingerprint expression,
    ExpressionFingerprint parent,
    ExpressionFingerprint target,
    ExpressionFeatures features,
    ExpressionFeatures parentFeatures,
    int depth,
    int score,
    int parentScore,
    int frontierSize,
    int visitedCount,
    int generatedCount,
    String ruleId,
    RewriteKind rewriteKind,
    List<String> applicableRuleIds,
    List<String> assumptions,
    String pruningReason,
    boolean eventualSuccess,
    boolean selectedPath,
    GoalStatus terminalStatus
) {
    public static final String SCHEMA = "regelsuche.search-trajectory/v1";

    public SearchTrajectoryRecord {
        schema = schema == null || schema.isBlank() ? SCHEMA : schema;
        requireText(producerVersion, "producerVersion");
        requireText(runId, "runId");
        requireText(family, "family");
        requireText(ruleInventoryHash, "ruleInventoryHash");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(features, "features");
        Objects.requireNonNull(split, "split");
        Objects.requireNonNull(terminalStatus, "terminalStatus");
        if (eventType == SearchEventType.TRANSFORMATION_GENERATED
                && parentFeatures == null) {
            throw new IllegalArgumentException(
                "transformation decisions require parentFeatures");
        }
        ruleId = safe(ruleId);
        applicableRuleIds = applicableRuleIds == null
            ? List.of()
            : applicableRuleIds.stream().filter(Objects::nonNull).distinct().sorted().toList();
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        pruningReason = safe(pruningReason);
    }

    public boolean decision() {
        return eventType == SearchEventType.TRANSFORMATION_GENERATED;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
