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
import java.util.Objects;

public record SearchProblem(
    String rootExpression,
    TransformationEngine engine,
    ExpressionScorer scorer,
    ExpressionCanonicalizer canonicalizer,
    SearchHeuristic heuristic,
    SearchMemory memory,
    CostModel costModel,
    SearchObserver observer,
    SearchTarget target
) {
    public SearchProblem {
        observer = observer == null ? NoOpSearchObserver.INSTANCE : observer;
    }

    /** Backwards-compatible canonical constructor without a target. */
    public SearchProblem(
        String rootExpression,
        TransformationEngine engine,
        ExpressionScorer scorer,
        ExpressionCanonicalizer canonicalizer,
        SearchHeuristic heuristic,
        SearchMemory memory,
        CostModel costModel,
        SearchObserver observer
    ) {
        this(rootExpression, engine, scorer, canonicalizer, heuristic,
            memory, costModel, observer, null);
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
        this(rootExpression, engine, scorer, canonicalizer, heuristic,
            null, null, NoOpSearchObserver.INSTANCE, null);
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
        this(rootExpression, engine, scorer, canonicalizer, heuristic,
            memory, null, NoOpSearchObserver.INSTANCE, null);
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
        this(rootExpression, engine, scorer, canonicalizer, heuristic,
            memory, costModel, NoOpSearchObserver.INSTANCE, null);
    }

    /** Returns this problem with {@code memory} attached. */
    public SearchProblem withMemory(SearchMemory memory) {
        return new SearchProblem(rootExpression, engine, scorer, canonicalizer,
            heuristic, memory, costModel, observer, target);
    }

    /**
     * Returns this problem with {@code costModel} attached. Passing {@code
     * null} restores legacy strategy-local ordering based on
     * {@link de.regelsuche.scoring.ExpressionScore#weightedTotal()}.
     */
    public SearchProblem withCostModel(CostModel costModel) {
        return new SearchProblem(rootExpression, engine, scorer, canonicalizer,
            heuristic, memory, costModel, observer, target);
    }

    /** Returns this problem with a runtime telemetry observer attached. */
    public SearchProblem withObserver(SearchObserver observer) {
        return new SearchProblem(rootExpression, engine, scorer, canonicalizer,
            heuristic, memory, costModel, observer, target);
    }

    /** Convenience: derive the cost model from the transformation objective. */
    public SearchProblem withGoal(TransformationGoal goal) {
        return withCostModel(goal == null ? null : goal.defaultCostModel());
    }

    /** Adds a value-equivalent target with early termination enabled. */
    public SearchProblem withTarget(String targetExpression) {
        return withTarget(SearchTarget.valueEquivalent(targetExpression));
    }

    /** Adds an explicit target specification. */
    public SearchProblem withTarget(SearchTarget target) {
        return new SearchProblem(rootExpression, engine, scorer, canonicalizer,
            heuristic, memory, costModel, observer, Objects.requireNonNull(target, "target"));
    }

    /** Removes target guidance while retaining all other settings. */
    public SearchProblem withoutTarget() {
        return new SearchProblem(rootExpression, engine, scorer, canonicalizer,
            heuristic, memory, costModel, observer, null);
    }

    public enum TargetRelation {
        /** Match the scoped canonical mathematical value, including AC laws. */
        VALUE_EQUIVALENT,
        /** Match the whitespace-normalized syntax string exactly. */
        SYNTAX_EXACT
    }

    /**
     * Optional target guidance. The distance weight influences ordering only;
     * it is not mathematical evidence and never changes rule applicability.
     */
    public record SearchTarget(
        String targetExpression,
        TargetRelation relation,
        int distanceWeight,
        boolean stopWhenReached
    ) {
        public static final int DEFAULT_DISTANCE_WEIGHT = 8;

        public SearchTarget {
            Objects.requireNonNull(targetExpression, "targetExpression");
            Objects.requireNonNull(relation, "relation");
            targetExpression = targetExpression.trim().replaceAll("\\s+", " ");
            if (targetExpression.isEmpty()) {
                throw new IllegalArgumentException("targetExpression must not be blank");
            }
            if (distanceWeight < 0) {
                throw new IllegalArgumentException("distanceWeight must not be negative");
            }
        }

        public static SearchTarget valueEquivalent(String expression) {
            return new SearchTarget(
                expression, TargetRelation.VALUE_EQUIVALENT,
                DEFAULT_DISTANCE_WEIGHT, true);
        }

        public static SearchTarget syntaxExact(String expression) {
            return new SearchTarget(
                expression, TargetRelation.SYNTAX_EXACT,
                DEFAULT_DISTANCE_WEIGHT, true);
        }

        public SearchTarget continueAfterReached() {
            return new SearchTarget(targetExpression, relation, distanceWeight, false);
        }

        public SearchTarget withDistanceWeight(int weight) {
            return new SearchTarget(targetExpression, relation, weight, stopWhenReached);
        }
    }
}
