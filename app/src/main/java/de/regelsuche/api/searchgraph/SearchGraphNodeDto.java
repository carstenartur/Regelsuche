package de.regelsuche.api.searchgraph;

import de.regelsuche.mining.CandidateProofStatus;

/**
 * Node DTO for the Visual Search Graph.
 *
 * <p>Represents a single expression encountered during search, enriched with
 * UI-relevant attributes (best-path / dead-end markers, validation status, cluster id).
 *
 * <p>See {@code docs/visual-search-graph.md}.
 */
public record SearchGraphNodeDto(
    String id,
    String expression,
    String latex,
    int score,
    int depth,
    int visitedCount,
    boolean isBest,
    boolean isDeadEnd,
    CandidateProofStatus candidateStatus,
    String clusterId,
    SearchExpression expressionType
) {
    public SearchGraphNodeDto {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        if (expression == null) {
            throw new IllegalArgumentException("expression is required");
        }
        latex = latex == null ? "" : latex;
        candidateStatus = candidateStatus == null ? CandidateProofStatus.OBSERVED : candidateStatus;
        clusterId = clusterId == null ? "" : clusterId;
        expressionType = expressionType == null ? SearchExpression.classify(expression) : expressionType;
    }

    /**
     * Backwards-compatible constructor used by callers that pre-date
     * the typed-expression integration. The {@link #expressionType()}
     * is inferred from the raw {@code expression} string via
     * {@link SearchExpression#classify(String)}.
     */
    public SearchGraphNodeDto(
        String id,
        String expression,
        String latex,
        int score,
        int depth,
        int visitedCount,
        boolean isBest,
        boolean isDeadEnd,
        CandidateProofStatus candidateStatus,
        String clusterId
    ) {
        this(id, expression, latex, score, depth, visitedCount, isBest, isDeadEnd,
            candidateStatus, clusterId, SearchExpression.classify(expression));
    }
}
