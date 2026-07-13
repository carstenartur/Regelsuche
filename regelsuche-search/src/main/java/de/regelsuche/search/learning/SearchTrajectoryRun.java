package de.regelsuche.search.learning;

import de.regelsuche.search.learning.SearchTrajectoryContext.DatasetSplit;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalStatus;
import java.util.List;
import java.util.Objects;

/** One complete search run after eventual outcome and selected path are known. */
public record SearchTrajectoryRun(
    SearchTrajectoryContext context,
    ExpressionFingerprint root,
    ExpressionFingerprint target,
    String taskValueFingerprint,
    String taskAlphaFingerprint,
    GoalStatus terminalStatus,
    boolean success,
    List<SearchTrajectoryRecord> records
) {
    public SearchTrajectoryRun {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(terminalStatus, "terminalStatus");
        Objects.requireNonNull(records, "records");
        taskValueFingerprint = safe(taskValueFingerprint);
        taskAlphaFingerprint = safe(taskAlphaFingerprint);
        records = List.copyOf(records);
    }

    public SearchTrajectoryRun withSplit(DatasetSplit split) {
        SearchTrajectoryContext updated = context.withSplit(split);
        List<SearchTrajectoryRecord> updatedRecords = records.stream()
            .map(record -> new SearchTrajectoryRecord(
                record.schema(),
                record.producerVersion(),
                record.runId(),
                record.family(),
                split,
                record.ruleInventoryHash(),
                record.sequence(),
                record.eventType(),
                record.expression(),
                record.parent(),
                record.target(),
                record.features(),
                record.transformationDescriptor(),
                record.depth(),
                record.score(),
                record.parentScore(),
                record.frontierSize(),
                record.visitedCount(),
                record.generatedCount(),
                record.ruleId(),
                record.rewriteKind(),
                record.applicableRuleIds(),
                record.assumptions(),
                record.pruningReason(),
                record.eventualSuccess(),
                record.selectedPath(),
                record.terminalStatus()))
            .toList();
        return new SearchTrajectoryRun(
            updated, root, target, taskValueFingerprint, taskAlphaFingerprint,
            terminalStatus, success, updatedRecords);
    }

    public int decisionCount() {
        return (int) records.stream().filter(SearchTrajectoryRecord::decision).count();
    }

    public int selectedDecisionCount() {
        return (int) records.stream()
            .filter(SearchTrajectoryRecord::decision)
            .filter(SearchTrajectoryRecord::selectedPath)
            .count();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
