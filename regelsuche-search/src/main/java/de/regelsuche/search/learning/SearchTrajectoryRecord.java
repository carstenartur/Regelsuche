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
    TransformationDescriptor transformationDescriptor,
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
    GoalStatus terminalStatus,
    String scoringRevision
) {
    public static final String SCHEMA = "regelsuche.search-trajectory/v3";

    public SearchTrajectoryRecord {
        scoringRevision = de.regelsuche.scoring.ScoreRevision.normalize(scoringRevision);
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
        ruleId = safe(ruleId);
        applicableRuleIds = applicableRuleIds == null
            ? List.of()
            : applicableRuleIds.stream().filter(Objects::nonNull).distinct().sorted().toList();
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        pruningReason = safe(pruningReason);
        boolean decision = eventType == SearchEventType.TRANSFORMATION_GENERATED;
        if (decision != (transformationDescriptor != null)) {
            throw new IllegalArgumentException(
                "transformationDescriptor must be present exactly for transformation decisions");
        }
    }


    /** Unversioned callers cannot attest which scoring algorithm produced their numbers. */
    public SearchTrajectoryRecord(String schema,
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
        TransformationDescriptor transformationDescriptor,
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
        GoalStatus terminalStatus) {
        this(schema, producerVersion, runId, family, split, ruleInventoryHash, sequence, eventType, expression, parent, target, features, transformationDescriptor, depth, score, parentScore, frontierSize, visitedCount, generatedCount, ruleId, rewriteKind, applicableRuleIds, assumptions, pruningReason, eventualSuccess, selectedPath, terminalStatus, de.regelsuche.scoring.ScoreRevision.UNSPECIFIED);
    }

    public void requireCurrentScoring() {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("unsupported scoring trajectory schema: " + schema);
        }
        de.regelsuche.scoring.ScoreRevision.requireCurrent(scoringRevision);
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
