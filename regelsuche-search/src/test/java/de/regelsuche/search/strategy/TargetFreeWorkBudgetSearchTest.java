package de.regelsuche.search.strategy;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy.Budget;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy.Problem;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.MeasuredTransformationEngines;
import de.regelsuche.transform.Transformation;
import java.util.List;
import org.junit.jupiter.api.Test;

class TargetFreeWorkBudgetSearchTest {
    @Test
    void anAbsentTargetDoesNotAddATextLengthHeuristic() {
        String name = "a_very_long_variable_name_with_the_same_score_as_x";
        // The frontier only ranks this synthetic source; it does not certify its rule.
        var source = new SearchExpansionSource.Measured(MeasuredTransformationEngines.counting(expression ->
            expression.equals("x+x+x") ? List.of(new Transformation("synthetic", name)) : List.of()));
        var budget = Budget.primitive(1, 4, 4, 1, 1000);
        var scorer = new ExpressionScorer() {
            @Override public ExpressionScore score(String expression) {
                return new ExpressionScore(0, expression.equals(name) ? 1 : 20, 0, 0, 0);
            }
        };
        var result = new WorkBudgetBestFirstSearchStrategy().search(Problem.withoutTarget("x+x+x", source,
            scorer, new ExpressionCanonicalizer(), budget));
        assertEquals(name, result.bestState().expression());
        assertThrows(IllegalArgumentException.class, () -> new Problem("x", " ", source,
            new ExpressionScorer(), new ExpressionCanonicalizer(), budget));
        var targeted = new WorkBudgetBestFirstSearchStrategy().search(new Problem("x", "x", source,
            new ExpressionScorer(), new ExpressionCanonicalizer(), budget));
        assertTrue(targeted.reached());
        assertFalse(targeted.toCanonicalJson().contains("goalMode"));
    }

    @Test
    void retainsAReplayableBestStateWithoutInventingATargetHit() {
        var source = new SearchExpansionSource.Measured(MeasuredTransformationEngines.counting(
            new AstRewriteTransformationEngine(AstRewriteTransformationEngine.defaultRules().stream()
                .filter(rule -> java.util.Set.of("ast_add_zero_right", "ast_multiply_one_right").contains(rule.id())).toList())));
        var problem = Problem.withoutTarget("(x+0)*1", source, new ExpressionScorer(),
            new ExpressionCanonicalizer(), Budget.primitive(3, 40, 40, 2, 10_000));
        var result = new WorkBudgetBestFirstSearchStrategy().search(problem);
        assertEquals("x", result.bestState().expression());
        assertFalse(result.reached());
        assertNull(result.reachedState());
        assertTrue(problem.targetFree());
        assertEquals("", result.configuration().targetExpression());
        assertTrue(result.toCanonicalJson().contains("\"goalMode\":\"TARGET_FREE\""));
        assertEquals(result, WorkSearchReplay.verify(result.toCanonicalJson(), problem));
        assertTrue(result.metrics().chargedSearchWorkUnits() <= problem.budget().maxWorkUnits());
    }

    @Test
    void targetFreeModeStillChargesRejectedPrimitivePaths() {
        var source = new SearchExpansionSource.Measured(MeasuredTransformationEngines.counting(
            new AstRewriteTransformationEngine(AstRewriteTransformationEngine.defaultRules().stream()
                .filter(rule -> java.util.Set.of("ast_add_zero_right", "ast_multiply_one_right").contains(rule.id())).toList())));
        var problem = Problem.withoutTarget("(x+0)*1", source, new ExpressionScorer(),
            new ExpressionCanonicalizer(), Budget.primitive(1, 40, 40, 2, 10_000));
        var result = new WorkBudgetBestFirstSearchStrategy().search(problem);
        assertNotEquals("x", result.bestState().expression());
        assertTrue(result.metrics().primitiveBudgetPrunes() > 0);
        assertFalse(result.reached());
    }
}
