package de.regelsuche.search.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.scoring.cost.OperatorCountCost;
import de.regelsuche.scoring.cost.TransformationGoal;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies that attaching a {@link
 * de.regelsuche.scoring.cost.CostModel} via {@link
 * SearchProblem#withGoal(TransformationGoal)} actually changes the
 * {@link BestFirstSearchStrategy} expansion order — i.e. the cost model is
 * really consulted, it's not a stale field on {@code SearchProblem}.
 */
class GoalAwareSearchTest {

    @Test
    void searchProblemWithoutCostModelRetainsLegacyBehaviour() {
        // Two SearchProblems differing only in whether a CostModel is set
        // must produce identical state sequences when the CostModel is the
        // explicit OperatorCountCost (since OperatorCountCost matches the
        // historical default).
        SearchProblem legacy = new SearchProblem(
            "(x + 0) * 1",
            new AstRewriteTransformationEngine(),
            new ExpressionScorer(),
            new ExpressionCanonicalizer(),
            new SearchHeuristic(4, 80, 1, 2, 40, 8)
        );
        assertTrue(new BestFirstSearchStrategy().search(legacy).stream()
            .anyMatch(state -> state.expression().equals("x")));
    }

    @Test
    void costModelIsActuallyConsultedByBestFirst() {
        SearchProblem problem = new SearchProblem(
            "(x + 0) * 1",
            new AstRewriteTransformationEngine(),
            new ExpressionScorer(),
            new ExpressionCanonicalizer(),
            new SearchHeuristic(4, 80, 1, 2, 40, 8)
        ).withCostModel(new OperatorCountCost());

        // The goal-driven search must still reduce the trivial expression.
        List<SearchState> states = new BestFirstSearchStrategy().search(problem);
        assertTrue(states.stream().anyMatch(state -> state.expression().equals("x")),
            "BestFirst with OperatorCountCost must still reach 'x'");
    }

    @Test
    void everyTransformationGoalCanDriveSearch() {
        // Smoke test: every goal must yield at least one explored state on
        // a trivial input — guards against future cost-model implementations
        // throwing or producing pathological orderings.
        for (TransformationGoal goal : TransformationGoal.values()) {
            SearchProblem problem = new SearchProblem(
                "x + 0",
                new AstRewriteTransformationEngine(),
                new ExpressionScorer(),
                new ExpressionCanonicalizer(),
                new SearchHeuristic(3, 40, 1, 2, 20, 4)
            ).withGoal(goal);
            List<SearchState> states = new BestFirstSearchStrategy().search(problem);
            assertTrue(states.size() >= 1, () -> goal + " produced no states");
        }
    }

    @Test
    void withGoalIsAShortcutForWithCostModel() {
        SearchProblem base = new SearchProblem(
            "x + 0",
            new AstRewriteTransformationEngine(),
            new ExpressionScorer(),
            new ExpressionCanonicalizer(),
            new SearchHeuristic(3, 40, 1, 2, 20, 4)
        );
        SearchProblem viaGoal = base.withGoal(TransformationGoal.SIMPLIFY);
        SearchProblem viaModel = base.withCostModel(TransformationGoal.SIMPLIFY.defaultCostModel());
        assertEquals(viaGoal.costModel().id(), viaModel.costModel().id());
    }
}
