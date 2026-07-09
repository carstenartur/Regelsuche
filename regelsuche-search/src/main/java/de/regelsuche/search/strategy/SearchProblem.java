package de.regelsuche.search.strategy;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.scoring.cost.CostModel;
import de.regelsuche.scoring.cost.TransformationGoal;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.memory.SearchMemory;
import de.regelsuche.search.telemetry.NoOpSearchObserver;
import de.regelsuche.search.telemetry.SearchObserver;
import de.regelsuche.transform.TransformationEngine;

public record SearchProblem(
    String rootExpression,
    TransformationEngine engine,
    ExpressionScorer scorer,
    ExpressionCanonicalizer canonicalizer,
    SearchHeuristic heuristic,
    SearchMemory memory,
    CostModel costModel,
    SearchObserver observer
) {
    public SearchProblem {
        observer = observer == null ? NoOpSearchObserver.INSTANCE : observer;
    }

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
        this(rootExpression, engine, scorer, canonicalizer, heuristic, null, null, NoOpSearchObserver.INSTANCE);
    }

    /**
     * Backwards-compatible constructor that preserves the pre-PR-3 API
     * (no explicit cost model): strategies use their legacy
     * {@link de.regelsuche.scoring.ExpressionScore#weightedTotal()}-based
     * ordering.
     */
    public SearchProblem(
        String rootExpression,
        TransformationEngine engine,
        ExpressionScorer scorer,
        ExpressionCanonicalizer canonicalizer,
        SearchHeuristic heuristic,
        SearchMemory memory
    ) {
        this(rootExpression, engine, scorer, canonicalizer, heuristic, memory, null, NoOpSearchObserver.INSTANCE);
    }

    /** Backwards-compatible constructor without runtime search telemetry. */
    public SearchProblem(
        String rootExpression,
        TransformationEngine engine,
        ExpressionScorer scorer,
        ExpressionCanonicalizer canonicalizer,
        SearchHeuristic heuristic,
        SearchMemory memory,
        CostModel costModel
    ) {
        this(rootExpression, engine, scorer, canonicalizer, heuristic, memory, costModel, NoOpSearchObserver.INSTANCE);
    }

    /** Returns this problem with {@code memory} attached. */
    public SearchProblem withMemory(SearchMemory memory) {
        return new SearchProblem(rootExpression, engine, scorer, canonicalizer, heuristic, memory, costModel, observer);
    }

    /**
     * Returns this problem with {@code costModel} attached. Passing {@code
     * null} restores legacy strategy-local ordering based on
     * {@link de.regelsuche.scoring.ExpressionScore#weightedTotal()}.
     */
    public SearchProblem withCostModel(CostModel costModel) {
        return new SearchProblem(rootExpression, engine, scorer, canonicalizer, heuristic, memory, costModel, observer);
    }

    /** Returns this problem with a runtime telemetry observer attached. */
    public SearchProblem withObserver(SearchObserver observer) {
        return new SearchProblem(rootExpression, engine, scorer, canonicalizer, heuristic, memory, costModel, observer);
    }

    /** Convenience: derive the cost model from the goal. */
    public SearchProblem withGoal(TransformationGoal goal) {
        return withCostModel(goal == null ? null : goal.defaultCostModel());
    }
}
