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
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(features, "features");
        Objects.requireNonNull(split, "split");
        Objects.requireNonNull(terminalStatus, "terminalStatus");
        producerVersion = safe(producerVersion);
        runId = safe(runId);
        family = safe(family);
        ruleInventoryHash = safe(ruleInventoryHash);
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

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
