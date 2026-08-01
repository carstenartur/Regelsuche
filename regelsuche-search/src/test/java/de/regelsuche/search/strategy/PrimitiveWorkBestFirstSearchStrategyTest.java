package de.regelsuche.search.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.strategy.PrimitiveWorkBestFirstSearchStrategy.Budget;
import de.regelsuche.search.strategy.PrimitiveWorkBestFirstSearchStrategy.Problem;
import de.regelsuche.transform.MeasuredTransformationEngine;
import de.regelsuche.transform.MeasuredTransformationEngines;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationBatch;
import de.regelsuche.transform.TransformationEngine;
import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.List;
import org.junit.jupiter.api.Test;

class PrimitiveWorkBestFirstSearchStrategyTest {
    private final PrimitiveWorkBestFirstSearchStrategy search =
        new PrimitiveWorkBestFirstSearchStrategy();

    @Test
    void macroEdgeCannotBypassPrimitiveStepBudget() {
        MeasuredTransformationEngine macro =
            MeasuredTransformationEngines.counting(expression ->
                expression.equals("a")
                    ? List.of(macro("a_to_c", "c", "r1", "r2"))
                    : List.of());

        var blocked = search.search(problem(
            macro,
            new Budget(1, 10, 10, 10, 1000)));
        var admitted = search.search(problem(
            macro,
            new Budget(2, 10, 10, 10, 1000)));

        assertFalse(blocked.reached());
        assertEquals(
            PrimitiveWorkBestFirstSearchStrategy.Status.PRIMITIVE_BUDGET,
            blocked.status());
        assertTrue(blocked.metrics().primitiveBudgetPrunes() > 0);
        assertTrue(admitted.reached());
        assertEquals(1, admitted.reachedState().edgeDepth());
        assertEquals(2, admitted.reachedState().primitiveDepth());
        assertEquals(List.of("r1", "r2"),
            admitted.reachedState().primitiveRuleIds());
    }

    @Test
    void measuredInternalWorkCanExhaustBudgetBeforeCandidateUse() {
        MeasuredTransformationEngine expensive = expression ->
            new TransformationBatch(
                List.of(macro("expensive", "c", "r1", "r2")),
                new TransformationWorkMetrics(
                    1, 5, 2, 4, 3, 2, 1,
                    0, 0, 1, 1, 0, 0, 0));

        var result = search.search(problem(
            expensive,
            new Budget(2, 10, 10, 10, 5)));

        assertFalse(result.reached());
        assertEquals(
            PrimitiveWorkBestFirstSearchStrategy.Status.WORK_BUDGET,
            result.status());
        assertTrue(result.metrics().transformationWork().totalWorkUnits() > 5);
        assertEquals(0, result.metrics().enqueuedStates());
    }

    @Test
    void flatAndMacroPathsReceiveTheSamePrimitiveAllowance() {
        TransformationEngine flat = expression -> switch (expression) {
            case "a" -> List.of(primitive("r1", "b"));
            case "b" -> List.of(primitive("r2", "c"));
            default -> List.of();
        };
        MeasuredTransformationEngine flatMeasured =
            MeasuredTransformationEngines.counting(flat);
        MeasuredTransformationEngine withMacro =
            MeasuredTransformationEngines.union(
                flatMeasured,
                MeasuredTransformationEngines.counting(expression ->
                    expression.equals("a")
                        ? List.of(macro("a_to_c", "c", "r1", "r2"))
                        : List.of()));
        Budget budget = new Budget(2, 10, 10, 10, 1000);

        var flatResult = search.search(problem(flatMeasured, budget));
        var macroResult = search.search(problem(withMacro, budget));

        assertTrue(flatResult.reached());
        assertTrue(macroResult.reached());
        assertEquals(2, flatResult.reachedState().primitiveDepth());
        assertEquals(2, macroResult.reachedState().primitiveDepth());
        assertEquals(2, flatResult.reachedState().edgeDepth());
        assertEquals(1, macroResult.reachedState().edgeDepth());
        assertTrue(
            macroResult.metrics().transformationWork().totalWorkUnits()
                > flatResult.metrics().transformationWork().totalWorkUnits(),
            "the macro's additional internal/frontier work remains visible");
    }

    private static Problem problem(
        MeasuredTransformationEngine engine,
        Budget budget
    ) {
        return new Problem(
            "a",
            "c",
            engine,
            new ExpressionScorer(),
            new ExpressionCanonicalizer(),
            budget);
    }

    private static Transformation primitive(String rule, String output) {
        return new Transformation(
            rule,
            output,
            RewriteKind.SIMPLIFY,
            false,
            -1,
            true,
            rule + ":" + output);
    }

    private static Transformation macro(
        String rule,
        String output,
        String... primitiveRules
    ) {
        return new Transformation(
            "program:" + rule,
            output,
            RewriteKind.NORMALIZE,
            false,
            -2,
            true,
            "program:" + rule + ":" + output,
            List.of(),
            "core",
            "PROJECT",
            List.of(primitiveRules));
    }
}
