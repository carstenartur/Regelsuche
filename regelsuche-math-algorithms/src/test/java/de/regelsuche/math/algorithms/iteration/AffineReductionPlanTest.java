package de.regelsuche.math.algorithms.iteration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.iteration.AffineReduction;
import de.regelsuche.ast.iteration.AffineReduction.Form;
import de.regelsuche.ast.iteration.AffineReduction.Operator;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class AffineReductionPlanTest {
    private static final IterationLimits LIMITS = new IterationLimits(1_000_000, 128, 5_000);

    @Test
    void supportsNegativeValuesAndMonotoneAndNonMonotoneWindows() {
        var monotone = source(Operator.MAX, 1, 0, List.of(
            form(0, 0, -1, 1), form(0, 0, 2, -1)), 2);
        assertEvaluation(monotone, 2, integers(-9, -5, -2, -4, -7), integers(-9, -2, -2));
        var decreasingLower = source(Operator.MAX, 1, 0, List.of(
            form(0, -1, 1, 1), form(0, 1, 0, -1)), 1);
        assertEvaluation(decreasingLower, 4, integers(-9, -6, -2, -4, -7), integers(-7, -4, -2, -2, -2));
    }

    @Test
    void verifiesWeightedEliminationForEitherReadAxis() {
        var first = source(Operator.SUM, 2, 0, List.of(form(0, 0, 0, 1, 0),
            form(0, 0, 1, -1, 0), form(1, 0, 0, -1, 1), form(0, 0, 1, 0, -1)), 1);
        var second = source(Operator.SUM, 2, 1, List.of(form(0, 0, 0, 0, 1),
            form(0, 0, 1, 0, -1), form(1, 0, 0, 1, -1), form(0, 0, 1, -1, 0)), 1);
        for (var candidate : List.of(first, second))
            assertEvaluation(candidate, 2, integers(2, -3, 4), integers(4, 0, 7));
    }

    @Test
    void preservesAnIndependentIntegerOutputDomainWithNonUnitCoefficients() {
        var source = new AffineReduction(Operator.SUM, List.of(), List.of(form(0, 0, 1), form(0, 1, -1)),
            2, List.of(form(0, 0, 1, 0), form(0, 0, -1, 1), form(0, 1, 0, -2)),
            1, List.of(form(0, 0, 0, 0, 1), form(0, 0, 0, 1, -1)), 0);
        var result = evaluate(source, 5, integers(4, -5, 6, 7, -2, 1));
        assertEquals(List.of(List.of(0, 0), List.of(0, 1), List.of(0, 2), List.of(1, 1),
            List.of(1, 2), List.of(2, 2)), result.shape().outputCoordinates());
        assertEquals(integers(4, -1, 5, -1, 5, 5), result.values());
    }

    @Test
    void rejectsEmptyOutOfDomainAndUnboundedWindows() {
        var empty = source(Operator.MAX, 1, 0, List.of(form(-1, 0, 0, 1), form(0, 0, 1, -1)), 1);
        var overflow = source(Operator.SUM, 1, 0, List.of(form(0, 0, 0, 1), form(1, 1, 0, -1)), 1);
        var unbounded = source(Operator.SUM, 1, 0, List.of(form(0, 0, 0, 1)), 1);
        assertEquals("UNDEFINED_EMPTY_AFFINE_REDUCTION", assertThrows(IllegalArgumentException.class,
            () -> evaluate(empty, 2, integers(1, 2, 3))).getMessage());
        assertEquals("AFFINE_ACCESS_OUTSIDE_INPUT", assertThrows(IllegalArgumentException.class,
            () -> evaluate(overflow, 2, integers(1, 2, 3))).getMessage());
        assertEquals("UNBOUNDED_OR_LARGE_AFFINE_DOMAIN", assertThrows(IllegalArgumentException.class,
            () -> evaluate(unbounded, 2, integers(1, 2, 3))).getMessage());
    }

    @Test
    void preparationAndEvaluationRespectWorkAndCancellation() {
        var source = source(Operator.SUM, 1, 0, List.of(form(0, 0, 0, 1), form(0, 0, 1, -1)), 1);
        assertEquals("WORK_LIMIT", assertThrows(IllegalArgumentException.class,
            () -> AffineReductionPlan.prepare(source, new IterationLimits(1, 128, 5_000), () -> false)).getMessage());
        var plan = AffineReductionPlan.prepare(source, LIMITS, () -> false).plan();
        assertEquals("CANCELLED", assertThrows(IllegalArgumentException.class,
            () -> plan.evaluate(2, integers(1, 2, 3), LIMITS, () -> true)).getMessage());
        assertEquals("WORK_LIMIT", assertThrows(IllegalArgumentException.class,
            () -> plan.evaluate(2, integers(1, 2, 3), new IterationLimits(5, 128, 5_000), () -> false)).getMessage());
    }

    private static void assertEvaluation(AffineReduction source, int parameter, List<BigInteger> data,
            List<BigInteger> expected) {
        var actual = evaluate(source, parameter, data);
        assertEquals(expected, actual.values());
        var direct = AffineReductionReference.evaluate(source, parameter, data, LIMITS, () -> false);
        assertEquals(actual.shape(), direct.shape());
        assertEquals(expected, direct.values());
        assertTrue(actual.work() > 0);
    }

    private static AffineReductionPlan.Evaluation evaluate(AffineReduction source, int parameter, List<BigInteger> data) {
        return AffineReductionPlan.prepare(source, LIMITS, () -> false).plan().evaluate(parameter, data, LIMITS, () -> false);
    }

    private static AffineReduction source(Operator operator, int reductionRank, int readAxis, List<Form> reductions,
            int inputScale) {
        return new AffineReduction(operator, List.of(form(0, 1)),
            List.of(form(0, 0, 1), form(0, inputScale, -1)), 1,
            List.of(form(0, 0, 1), form(0, 1, -1)), reductionRank, reductions, readAxis);
    }

    private static Form form(int constant, int parameter, Integer... coordinates) {
        return new Form(constant, parameter, List.of(coordinates));
    }

    private static List<BigInteger> integers(int... values) {
        return Arrays.stream(values).mapToObj(BigInteger::valueOf).toList();
    }
}
