package de.regelsuche.search.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.scoring.cost.OperatorCountCost;
import de.regelsuche.scoring.cost.TransformationGoal;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Covers transformation objectives and typed target guidance independently. */
class GoalAwareSearchTest {

    @Test
    void searchProblemWithoutCostModelRetainsDefaultBehaviour() {
        SearchProblem problem = new SearchProblem(
            "(x + 0) * 1",
            new AstRewriteTransformationEngine(),
            new ExpressionScorer(),
            new ExpressionCanonicalizer(),
            new SearchHeuristic(4, 80, 1, 2, 40, 8)
        );
        assertTrue(new BestFirstSearchStrategy().search(problem).stream()
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

        List<SearchState> states = new BestFirstSearchStrategy().search(problem);
        assertTrue(states.stream().anyMatch(state -> state.expression().equals("x")),
            "BestFirst with OperatorCountCost must still reach 'x'");
    }

    @Test
    void everyTransformationObjectiveCanDriveSearch() {
        for (TransformationGoal objective : TransformationGoal.values()) {
            SearchProblem problem = new SearchProblem(
                "x + 0",
                new AstRewriteTransformationEngine(),
                new ExpressionScorer(),
                new ExpressionCanonicalizer(),
                new SearchHeuristic(3, 40, 1, 2, 20, 4)
            ).withObjective(objective);
            List<SearchState> states = new BestFirstSearchStrategy().search(problem);
            assertTrue(states.size() >= 1, () -> objective + " produced no states");
        }
    }

    @Test
    void objectiveConvenienceUsesItsDefaultCostModel() {
        SearchProblem base = new SearchProblem(
            "x + 0",
            new AstRewriteTransformationEngine(),
            new ExpressionScorer(),
            new ExpressionCanonicalizer(),
            new SearchHeuristic(3, 40, 1, 2, 20, 4)
        );
        SearchProblem viaObjective = base.withObjective(TransformationGoal.SIMPLIFY);
        SearchProblem viaModel = base.withCostModel(TransformationGoal.SIMPLIFY.defaultCostModel());
        assertEquals(viaObjective.costModel().id(), viaModel.costModel().id());
    }

    @Test
    void valueEquivalentTargetRecognizesAcRootIndependentOfGroupingAndOrder() {
        SearchProblem problem = baseProblem(
            "(a + b) + c",
            expression -> List.of(),
            2,
            8,
            4
        ).withTarget("c + a + b");

        BestFirstSearchStrategy.GoalSearchResult result =
            new BestFirstSearchStrategy().searchWithDiagnostics(problem);

        assertTrue(result.reached());
        assertEquals(BestFirstSearchStrategy.GoalStatus.ROOT_ALREADY_TARGET, result.status());
        assertEquals(0, result.bestDistance());
        assertEquals(List.of("(a + b) + c"),
            result.states().stream().map(SearchState::expression).toList());
    }

    @Test
    void targetDistanceOrdersUsefulCandidateBeforeRuleNameBudget() {
        SearchProblem unguided = baseProblem(
            "x",
            new TargetAndDecoyEngine(),
            1,
            8,
            1
        );
        SearchProblem guided = unguided.withTarget(
            SearchProblem.SearchTarget.syntaxExact("a").withDistanceWeight(20));

        List<String> defaultOrder = new BestFirstSearchStrategy().search(unguided).stream()
            .map(SearchState::expression)
            .toList();
        BestFirstSearchStrategy.GoalSearchResult guidedResult =
            new BestFirstSearchStrategy().searchWithDiagnostics(guided);

        assertEquals(List.of("x", "z"), defaultOrder,
            "without a target, deterministic rule ordering remains unchanged");
        assertEquals(List.of("x", "a"),
            guidedResult.states().stream().map(SearchState::expression).toList());
        assertEquals(BestFirstSearchStrategy.GoalStatus.REACHED, guidedResult.status());
        assertNotNull(guidedResult.reachedState());
        assertEquals("a", guidedResult.reachedState().expression());
        assertTrue(guidedResult.metrics().candidateBudgetPrunes() >= 1);
    }

    @Test
    void syntaxExactTargetOutranksAnAcEquivalentNonExactCandidate() {
        TransformationEngine engine = expression -> {
            if (!"x * (y + z)".equals(expression)) {
                return List.of();
            }
            return List.of(
                new Transformation(
                    "a_reordered_equivalent", "x * z + x * y", RewriteKind.NORMALIZE,
                    false, 0, true, "a_reordered_equivalent"),
                new Transformation(
                    "z_exact_target", "x * y + x * z", RewriteKind.NORMALIZE,
                    false, 0, true, "z_exact_target")
            );
        };
        SearchProblem problem = baseProblem(
            "x * (y + z)", engine, 1, 8, 1)
            .withTarget(SearchProblem.SearchTarget
                .syntaxExact("x * y + x * z")
                .withDistanceWeight(20));

        BestFirstSearchStrategy.GoalSearchResult result =
            new BestFirstSearchStrategy().searchWithDiagnostics(problem);

        assertTrue(result.reached(), result.toString());
        assertEquals(List.of("x * (y + z)", "x * y + x * z"),
            result.states().stream().map(SearchState::expression).toList());
        assertEquals("z_exact_target", result.reachedState().appliedRuleId());
        assertTrue(result.metrics().candidateBudgetPrunes() >= 1);
    }

    @Test
    void syntaxEquivalentButNonExactStateHasPositiveDistance() {
        TransformationEngine engine = expression -> "x * (y + z)".equals(expression)
            ? List.of(new Transformation(
                "reordered_equivalent", "x * z + x * y", RewriteKind.NORMALIZE,
                false, 0, true, "reordered_equivalent"))
            : List.of();
        SearchProblem problem = baseProblem(
            "x * (y + z)", engine, 1, 8, 1)
            .withTarget(SearchProblem.SearchTarget
                .syntaxExact("x * y + x * z")
                .withDistanceWeight(20));

        BestFirstSearchStrategy.GoalSearchResult result =
            new BestFirstSearchStrategy().searchWithDiagnostics(problem);

        assertFalse(result.reached());
        assertEquals(1, result.bestDistance());
        assertEquals("x * z + x * y", result.bestState().expression());
    }

    @Test
    void diagnosticsDistinguishNoTransformationsFromGenericExhaustion() {
        SearchProblem problem = baseProblem(
            "x",
            expression -> List.of(),
            2,
            8,
            4
        ).withTarget("y");

        BestFirstSearchStrategy.GoalSearchResult result =
            new BestFirstSearchStrategy().searchWithDiagnostics(problem);

        assertEquals(BestFirstSearchStrategy.GoalStatus.NO_TRANSFORMATIONS, result.status());
        assertEquals(1, result.metrics().statesWithoutTransformations());
        assertEquals(0, result.metrics().generatedTransformations());
    }

    @Test
    void malformedValueTargetIsReportedExplicitly() {
        SearchProblem problem = baseProblem(
            "x",
            expression -> List.of(),
            1,
            4,
            2
        ).withTarget("a +");

        BestFirstSearchStrategy.GoalSearchResult result =
            new BestFirstSearchStrategy().searchWithDiagnostics(problem);

        assertEquals(BestFirstSearchStrategy.GoalStatus.UNPARSEABLE_TARGET, result.status());
    }

    private static SearchProblem baseProblem(
        String root,
        TransformationEngine engine,
        int maxDepth,
        int maxStates,
        int maxCandidates
    ) {
        return new SearchProblem(
            root,
            engine,
            new ExpressionScorer(),
            new ExpressionCanonicalizer(),
            new SearchHeuristic(maxDepth, maxStates, 1, 2, maxCandidates, 8)
        );
    }

    private static final class TargetAndDecoyEngine implements TransformationEngine {
        @Override
        public List<Transformation> transform(String expression) {
            if (!"x".equals(expression)) {
                return List.of();
            }
            return List.of(
                new Transformation("rule_a_decoy", "z", RewriteKind.NORMALIZE,
                    false, 0, true, "rule_a_decoy:z"),
                new Transformation("rule_z_target", "a", RewriteKind.NORMALIZE,
                    false, 0, true, "rule_z_target:a")
            );
        }
    }
}
