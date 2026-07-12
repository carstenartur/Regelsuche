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
        Objects.requireNonNull(rootExpression, "rootExpression");
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(scorer, "scorer");
        Objects.requireNonNull(canonicalizer, "canonicalizer");
        Objects.requireNonNull(heuristic, "heuristic");
        observer = observer == null ? NoOpSearchObserver.INSTANCE : observer;
    }

    /** Creates an untargeted problem with no optional memory, objective, or observer. */
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

    public SearchProblem withMemory(SearchMemory memory) {
        return new SearchProblem(rootExpression, engine, scorer, canonicalizer,
            heuristic, memory, costModel, observer, target);
    }

    public SearchProblem withCostModel(CostModel costModel) {
        return new SearchProblem(rootExpression, engine, scorer, canonicalizer,
            heuristic, memory, costModel, observer, target);
    }

    public SearchProblem withObserver(SearchObserver observer) {
        return new SearchProblem(rootExpression, engine, scorer, canonicalizer,
            heuristic, memory, costModel, observer, target);
    }

    /** Sets the transformation objective; this is distinct from a concrete target. */
    public SearchProblem withObjective(TransformationGoal objective) {
        return withCostModel(objective == null ? null : objective.defaultCostModel());
    }

    public SearchProblem withTarget(String targetExpression) {
        return withTarget(SearchTarget.valueEquivalent(targetExpression));
    }

    public SearchProblem withTarget(SearchTarget target) {
        return new SearchProblem(rootExpression, engine, scorer, canonicalizer,
            heuristic, memory, costModel, observer, Objects.requireNonNull(target, "target"));
    }

    public SearchProblem withoutTarget() {
        return new SearchProblem(rootExpression, engine, scorer, canonicalizer,
            heuristic, memory, costModel, observer, null);
    }

    public enum TargetRelation {
        VALUE_EQUIVALENT,
        SYNTAX_EXACT
    }

    /** Target guidance changes ordering only; it never changes rule applicability or evidence. */
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
