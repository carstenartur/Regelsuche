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
    SearchExpression expressionType,
    String expressionLatex
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
        // Stage 4: dedicated `expressionLatex` that is always routed
        // through the central `MathPresentation.latex(...)` pipeline so
        // the Cytoscape KaTeX overlay layer receives a guaranteed-non-
        // blank, canonically-rendered LaTeX string per node. Falls back
        // to the legacy `latex` field (or a fresh `MathPresentation`
        // render of `expression`) when callers do not set it explicitly.
        if (expressionLatex == null || expressionLatex.isBlank()) {
            if (!latex.isBlank()) {
                expressionLatex = latex;
            } else {
                expressionLatex = de.regelsuche.export.MathPresentation.DEFAULT.latex(expression);
            }
        }
    }

    /**
     * Backwards-compatible constructor used by callers that pre-date
     * the Stage 4 {@code expressionLatex} field. Routes through the
     * compact ctor so {@code expressionLatex} is populated from the
     * existing {@code latex} string (or, failing that, from a fresh
     * {@code MathPresentation.latex(expression)} render).
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
        String clusterId,
        SearchExpression expressionType
    ) {
        this(id, expression, latex, score, depth, visitedCount, isBest, isDeadEnd,
            candidateStatus, clusterId, expressionType, null);
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
            candidateStatus, clusterId, SearchExpression.classify(expression), null);
    }
}
