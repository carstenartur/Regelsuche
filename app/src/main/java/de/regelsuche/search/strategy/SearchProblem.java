package de.regelsuche.search.strategy;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.scoring.cost.CostModel;
import de.regelsuche.scoring.cost.TransformationGoal;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.memory.SearchMemory;
import de.regelsuche.transform.TransformationEngine;

public record SearchProblem(
    String rootExpression,
    TransformationEngine engine,
    ExpressionScorer scorer,
    ExpressionCanonicalizer canonicalizer,
    SearchHeuristic heuristic,
    SearchMemory memory,
    CostModel costModel
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
        this(rootExpression, engine, scorer, canonicalizer, heuristic, null, null);
    }

    /**
     * Backwards-compatible constructor that preserves the pre-PR-3 API
     * (no explicit cost model): selecting {@link TransformationGoal#SIMPLIFY}
     * — i.e. the historical operator-count behaviour — is the default.
     */
    public SearchProblem(
        String rootExpression,
        TransformationEngine engine,
        ExpressionScorer scorer,
        ExpressionCanonicalizer canonicalizer,
        SearchHeuristic heuristic,
        SearchMemory memory
    ) {
        this(rootExpression, engine, scorer, canonicalizer, heuristic, memory, null);
    }

    /** Returns this problem with {@code memory} attached. */
    public SearchProblem withMemory(SearchMemory memory) {
        return new SearchProblem(rootExpression, engine, scorer, canonicalizer, heuristic, memory, costModel);
    }

    /**
     * Returns this problem with {@code costModel} attached. Passing {@code
     * null} restores the default operator-count behaviour.
     */
    public SearchProblem withCostModel(CostModel costModel) {
        return new SearchProblem(rootExpression, engine, scorer, canonicalizer, heuristic, memory, costModel);
    }

    /** Convenience: derive the cost model from the goal. */
    public SearchProblem withGoal(TransformationGoal goal) {
        return withCostModel(goal == null ? null : goal.defaultCostModel());
    }
}

