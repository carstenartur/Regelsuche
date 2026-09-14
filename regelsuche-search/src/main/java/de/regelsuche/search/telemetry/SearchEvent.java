package de.regelsuche.search.telemetry;

import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RecordedExecution;
import java.util.List;

/** A deterministic, replayable telemetry event emitted during search. */
public record SearchEvent(
    long sequence,
    SearchEventType type,
    String expression,
    String canonicalHash,
    int depth,
    int score,
    String parentCanonicalHash,
    String parentExpression,
    String ruleId,
    RewriteKind rewriteKind,
    boolean mayIncreaseComplexity,
    int estimatedCostDelta,
    boolean equivalencePreservingByConstruction,
    List<String> assumptions,
    int frontierSize,
    int visitedCount,
    int generatedCount,
    String pruningReason,
    RecordedExecution execution,
    String scoringRevision
) {
    /** Raw historical or custom values do not identify their scoring producer. */
    public SearchEvent(
        long sequence,
        SearchEventType type,
        String expression,
        String canonicalHash,
        int depth,
        int score,
        String parentCanonicalHash,
        String parentExpression,
        String ruleId,
        RewriteKind rewriteKind,
        boolean mayIncreaseComplexity,
        int estimatedCostDelta,
        boolean equivalencePreservingByConstruction,
        List<String> assumptions,
        int frontierSize,
        int visitedCount,
        int generatedCount,
        String pruningReason,
        RecordedExecution execution
    ) {
        this(
            sequence,
            type,
            expression,
            canonicalHash,
            depth,
            score,
            parentCanonicalHash,
            parentExpression,
            ruleId,
            rewriteKind,
            mayIncreaseComplexity,
            estimatedCostDelta,
            equivalencePreservingByConstruction,
            assumptions,
            frontierSize,
            visitedCount,
            generatedCount,
            pruningReason,
            execution,
            de.regelsuche.scoring.ScoreRevision.UNSPECIFIED);
    }

    public SearchEvent {
        scoringRevision = de.regelsuche.scoring.ScoreRevision.normalize(scoringRevision);
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        expression = expression == null ? "" : expression;
        canonicalHash = canonicalHash == null ? "" : canonicalHash;
        parentCanonicalHash = parentCanonicalHash == null ? "" : parentCanonicalHash;
        parentExpression = parentExpression == null ? "" : parentExpression;
        ruleId = ruleId == null ? "" : ruleId;
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        pruningReason = pruningReason == null ? "" : pruningReason;
    }

    public SearchEvent(long sequence, SearchEventType type, String expression, String canonicalHash,
            int depth, int score, String parentCanonicalHash, String parentExpression, String ruleId,
            RewriteKind rewriteKind, boolean mayIncreaseComplexity, int estimatedCostDelta,
            boolean equivalencePreservingByConstruction, List<String> assumptions, int frontierSize,
            int visitedCount, int generatedCount, String pruningReason) {
        this(sequence, type, expression, canonicalHash, depth, score, parentCanonicalHash, parentExpression,
            ruleId, rewriteKind, mayIncreaseComplexity, estimatedCostDelta, equivalencePreservingByConstruction,
            assumptions, frontierSize, visitedCount, generatedCount, pruningReason, null);
    }
}
