package de.regelsuche.search.strategy;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import de.regelsuche.ast.Expr;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.scoring.cost.CostModel;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SearchPriorityBoundaryTest {
    @ParameterizedTest
    @ValueSource(ints = {Integer.MAX_VALUE - 2, Integer.MAX_VALUE - 1, Integer.MAX_VALUE})
    void allObjectiveStrategiesExploreTheCheapAlternativeFirst(int expensiveCost) {
        var scorer = new ExpressionScorer();
        var problem = new SearchProblem("x", expression -> expression.equals("x")
            ? List.of(new Transformation("expensive", "b"), new Transformation("cheap", "a")) : List.of(),
            scorer, new ExpressionCanonicalizer(), new SearchHeuristic(1, 10, 1))
            .withCostModel(new CostModel() {
                @Override
                public int cost(String expression, Expr ast, ExpressionScore score) {
                    return expression.equals("b") ? expensiveCost : 20;
                }

                @Override
                public String id() {
                    return "queue-boundary-fixture";
                }
            });
        for (SearchStrategy strategy : List.of(new BestFirstSearchStrategy(), new AStarSearchStrategy(),
                new BeamSearchStrategy(), new StructuralDiversitySearchStrategy())) {
            assertEquals("a", strategy.search(problem).get(1).expression(), strategy.getClass().getSimpleName());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MAX_VALUE - 2, Integer.MAX_VALUE - 1, Integer.MAX_VALUE})
    void objectiveAndPathCostsCannotWrapOrBecomeCheaperAtSaturation(int cost) {
        var scorer = new ExpressionScorer();
        var problem = new SearchProblem("x", expression -> List.of(), scorer,
            new ExpressionCanonicalizer(), new SearchHeuristic(3, 10, 1));
        var root = state(scorer, 0);
        var child = state(scorer, 1);
        for (BestFirstSearchStrategy strategy : List.of(new BestFirstSearchStrategy(), new AStarSearchStrategy())) {
            int parentPriority = strategy.priority(root, problem.withCostModel(fixedCost(cost)));
            int childPriority = strategy.priority(child, problem.withCostModel(fixedCost(cost)));
            assertTrue(parentPriority >= 0, strategy.getClass().getSimpleName());
            assertTrue(childPriority >= parentPriority,
                strategy.getClass().getSimpleName() + ": a path penalty must not improve priority");
            assertTrue(parentPriority >= strategy.priority(root, problem.withCostModel(fixedCost(cost - 1))),
                strategy.getClass().getSimpleName() + ": saturation must not improve priority");
        }
    }

    private SearchState state(ExpressionScorer scorer, int depth) {
        return new SearchState("x", depth, scorer.score("x"), List.of("x"), List.of(), Set.of(),
            0, "x", null, null, RewriteKind.NORMALIZE, false, 0, true, 1);
    }

    private CostModel fixedCost(int value) {
        return new CostModel() {
            @Override
            public int cost(String expression, Expr ast, ExpressionScore score) {
                return value;
            }

            @Override
            public String id() {
                return "boundary-fixture";
            }
        };
    }
}
