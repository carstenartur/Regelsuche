package de.regelsuche.search.memory;

/**
 * Explainable pruning decision recorded by a {@link de.regelsuche.search.strategy.SearchStrategy}
 * after consulting a {@link TranspositionTable}.
 *
 * <p>Surfaced via the {@code /api/search} response, the
 * {@code search-analysis-report.json} export and the {@code pruning-decisions.json}
 * entry in the bundle ZIP. The workbench UI's "Suchgedächtnis" tab filters
 * decisions by {@link PruningReason}.</p>
 */
public record PruningDecision(
    String expression,
    String canonicalHash,
    PruningReason reason,
    String explanation
) {
    public PruningDecision {
        if (expression == null) {
            throw new IllegalArgumentException("expression must not be null");
        }
        if (canonicalHash == null) {
            throw new IllegalArgumentException("canonicalHash must not be null");
        }
        if (reason == null) {
            throw new IllegalArgumentException("reason must not be null");
        }
        if (explanation == null || explanation.isBlank()) {
            explanation = reason.explanation();
        }
    }

    public PruningDecision(String expression, String canonicalHash, PruningReason reason) {
        this(expression, canonicalHash, reason, reason.explanation());
    }
}
