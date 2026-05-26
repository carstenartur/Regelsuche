package de.regelsuche.math.algorithms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.math.algorithms.numeric.PslqNumericRelationService;
import de.regelsuche.math.algorithms.registry.DefaultMathematicalAlgorithmRegistry;
import de.regelsuche.validation.MathematicalAlgorithmRegistry;
import de.regelsuche.validation.NumericRelationService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PslqNumericRelationServiceTest {
    @Test
    void disablingPslqPreventsExecution() {
        NumericRelationService service = new PslqNumericRelationService(
            new DefaultMathematicalAlgorithmRegistry(Map.of(
                MathematicalAlgorithmRegistry.NUMERIC_RELATION_SEARCH, true,
                MathematicalAlgorithmRegistry.PSLQ, false
            ), Map.of())
        );

        NumericRelationService.NumericRelationResult result = service.findIntegerRelation(List.of(Math.sqrt(2), Math.sqrt(8)));
        assertEquals(MathematicalAlgorithmRegistry.ExecutionStatus.DISABLED, result.result().status());
    }

    @Test
    void pslqResultIsHypothesisNeverProof() {
        NumericRelationService service = new PslqNumericRelationService(
            new DefaultMathematicalAlgorithmRegistry(Map.of(
                MathematicalAlgorithmRegistry.NUMERIC_RELATION_SEARCH, true,
                MathematicalAlgorithmRegistry.PSLQ, true
            ), Map.of())
        );

        NumericRelationService.NumericRelationResult result = service.findIntegerRelation(List.of(Math.sqrt(2), Math.sqrt(8)));
        assertEquals(MathematicalAlgorithmRegistry.ResultType.HYPOTHESIS, result.result().resultType());
        assertTrue(result.result().detail().contains("not a proof"));
    }

    @Test
    void findsKnownIntegerRelationsAsHypotheses() {
        NumericRelationService service = enabledService();

        NumericRelationService.NumericRelationResult sqrtRelation =
            service.findIntegerRelation(List.of(Math.sqrt(2), Math.sqrt(8)));
        NumericRelationService.NumericRelationResult simpleRelation =
            service.findIntegerRelation(List.of(1.0, 2.0, 3.0));

        assertEquals(List.of(2, -1), sqrtRelation.coefficients());
        assertEquals(MathematicalAlgorithmRegistry.ResultType.HYPOTHESIS, sqrtRelation.result().resultType());
        assertEquals(List.of(1, 1, -1), simpleRelation.coefficients());
        assertEquals(MathematicalAlgorithmRegistry.ResultType.HYPOTHESIS, simpleRelation.result().resultType());
    }

    @Test
    void rejectsInvalidNumericSamplesAsDiagnostics() {
        NumericRelationService service = enabledService();

        assertEquals(MathematicalAlgorithmRegistry.ResultType.DIAGNOSTIC,
            service.findIntegerRelation(List.of(Double.NaN, 1.0)).result().resultType());
        assertEquals(MathematicalAlgorithmRegistry.ResultType.DIAGNOSTIC,
            service.findIntegerRelation(List.of(Double.POSITIVE_INFINITY, 1.0)).result().resultType());
        assertEquals(MathematicalAlgorithmRegistry.ResultType.DIAGNOSTIC,
            service.findIntegerRelation(List.of(0.0, 0.0)).result().resultType());
        assertEquals(MathematicalAlgorithmRegistry.ResultType.DIAGNOSTIC,
            service.findIntegerRelation(List.of(1.0)).result().resultType());
    }

    @Test
    void budgetsStopExpensiveAlgorithms() {
        NumericRelationService service = new PslqNumericRelationService(
            new DefaultMathematicalAlgorithmRegistry(Map.of(
                MathematicalAlgorithmRegistry.NUMERIC_RELATION_SEARCH, true,
                MathematicalAlgorithmRegistry.PSLQ, true
            ), Map.of(
                MathematicalAlgorithmRegistry.PSLQ,
                MathematicalAlgorithmRegistry.AlgorithmBudget.bounded(0, 1, 6, 1e-9)
            ))
        );

        NumericRelationService.NumericRelationResult result = service.findIntegerRelation(List.of(Math.PI, Math.E, 1.0));
        assertEquals(MathematicalAlgorithmRegistry.ExecutionStatus.BUDGET_EXHAUSTED, result.result().status());
    }

    private static NumericRelationService enabledService() {
        return new PslqNumericRelationService(
            new DefaultMathematicalAlgorithmRegistry(Map.of(
                MathematicalAlgorithmRegistry.NUMERIC_RELATION_SEARCH, true,
                MathematicalAlgorithmRegistry.PSLQ, true
            ), Map.of())
        );
    }
}
