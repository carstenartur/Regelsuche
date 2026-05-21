package de.regelsuche.search.strategy;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.memory.SearchMemory;
import de.regelsuche.transform.TransformationEngine;

public record SearchProblem(
    String rootExpression,
    TransformationEngine engine,
    ExpressionScorer scorer,
    ExpressionCanonicalizer canonicalizer,
    SearchHeuristic heuristic,
    SearchMemory memory
) {
    /**
     * Backwards-compatible constructor without a search memory – the strategy
     * falls back to plain canonical-hash deduplication, no
     * {@link de.regelsuche.search.memory.PruningDecision}s are recorded.
     */
    public SearchProblem(
        String rootExpression,
        TransformationEngine engine,
        ExpressionScorer scorer,
        ExpressionCanonicalizer canonicalizer,
        SearchHeuristic heuristic
    ) {
        this(rootExpression, engine, scorer, canonicalizer, heuristic, null);
    }

    /** Returns this problem with {@code memory} attached. */
    public SearchProblem withMemory(SearchMemory memory) {
        return new SearchProblem(rootExpression, engine, scorer, canonicalizer, heuristic, memory);
    }
}
