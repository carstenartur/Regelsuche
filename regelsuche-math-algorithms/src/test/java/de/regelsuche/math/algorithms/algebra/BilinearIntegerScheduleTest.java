package de.regelsuche.math.algorithms.algebra;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.math.algorithms.algebra.BilinearIntegerSchedule.Op;
import de.regelsuche.math.algorithms.algebra.BilinearIntegerSchedule.Plan;
import de.regelsuche.math.algorithms.algebra.BilinearIntegerSchedule.Profile;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class BilinearIntegerScheduleTest {
    private static final IterationLimits LIMITS = new IterationLimits(2_000_000, 50_000, 10_000);

    private static List<ExactRational> row(long... values) {
        return Arrays.stream(values).mapToObj(ExactRational::integer).toList();
    }

    private static Plan plan(BilinearProgram program, Profile profile) {
        var result = BilinearIntegerSchedule.plan(program, profile, LIMITS, () -> false);
        assertEquals(BilinearProgram.Status.VERIFIED, result.status(), result.reason());
        return result.value().orElseThrow();
    }

    private static List<BigInteger> evaluate(Plan plan, List<BigInteger> left, List<BigInteger> right) {
        var result = BilinearIntegerSchedule.evaluate(plan, left, right, LIMITS, () -> false);
        assertEquals(BilinearProgram.Status.VERIFIED, result.status(), result.reason());
        return result.value().orElseThrow();
    }

    @Test void signedProductsAndOutputsAbsorbUnitNegationsAndShareRepeatedOperations() {
        var program = new BilinearProgram(List.of(row(-1, -1), row(1, 1)), List.of(row(-1), row(1)),
            List.of(row(1, 0), row(0, 1), row(1, -1), row(-1, 0)));
        var schedule = plan(program, new Profile(List.of(8, 8), List.of(16)));
        assertEquals(List.of(Op.ADD, Op.MULTIPLY, Op.NEGATE),
            schedule.steps().stream().map(BilinearIntegerSchedule.Step::op).toList());
        assertEquals(schedule.outputs().get(0), schedule.outputs().get(1));
        assertEquals(schedule.zeroSlot(), schedule.outputs().get(2));
        // 8+8 bits => 9 magnitude bits. Times a 16-bit value => at most 25 bits.
        // One word added and written, one multiplied and written, one sign flipped.
        assertEquals(new BilinearIntegerSchedule.Cost(5, 2, 3, 1), schedule.cost());
        assertEquals(List.of(BigInteger.valueOf(-9), BigInteger.valueOf(-9), BigInteger.ZERO, BigInteger.valueOf(9)),
            evaluate(schedule, List.of(BigInteger.valueOf(4), BigInteger.valueOf(-1)), List.of(BigInteger.valueOf(-3))));
    }

    @Test void combinesNarrowTermsFirstAndUsesExactSignedNonunitScalings() {
        var program = new BilinearProgram(List.of(row(-1, 3, -2)), List.of(row(1)), List.of(row(1)));
        var schedule = plan(program, new Profile(List.of(129, 2, 3), List.of(4)));
        var sum = schedule.steps().stream().filter(step -> step.op() == Op.ADD).findFirst().orElseThrow();
        assertTrue(sum.left() > schedule.zeroSlot());
        assertTrue(sum.right() > schedule.zeroSlot());
        assertTrue(schedule.steps().stream().anyMatch(step -> step.op() == Op.SCALE
            && step.coefficient().equals(BigInteger.valueOf(-2))));
        assertTrue(schedule.steps().stream().anyMatch(step -> step.op() == Op.SUBTRACT && step.right() == 0));
        var large = BigInteger.ONE.shiftLeft(128).negate();
        var expected = large.negate().subtract(BigInteger.valueOf(17)).multiply(BigInteger.valueOf(3));
        assertEquals(List.of(expected), evaluate(schedule, List.of(large, BigInteger.valueOf(-3), BigInteger.valueOf(4)),
            List.of(BigInteger.valueOf(3))));
        assertTrue(expected.abs().bitLength() <= schedule.magnitudeBits().get(schedule.outputs().getFirst()));
    }

    @Test void widthAndOperationCostsAreConservativeAtWordBoundariesAndForNegativePowersOfTwo() {
        var program = new BilinearProgram(List.of(row(4)), List.of(row(1)), List.of(row(1)));
        var profile = new Profile(List.of(32), List.of(33));
        var schedule = plan(program, profile);
        int scaleSlot = schedule.zeroSlot() + 1;
        assertEquals(34, schedule.magnitudeBits().get(scaleSlot));
        assertEquals(67, schedule.magnitudeBits().get(schedule.outputs().getFirst()));
        // Scale: 1*1+2. Multiply: 2*2+3.
        assertEquals(new BilinearIntegerSchedule.Cost(10, 5, 2, 1), schedule.cost());
        assertEquals("INPUT_OUTSIDE_PROFILE", BilinearIntegerSchedule.evaluate(schedule,
            List.of(BigInteger.ONE.shiftLeft(32).negate()), List.of(BigInteger.ONE), LIMITS, () -> false).reason());
        var zero = plan(program, new Profile(List.of(0), List.of(1)));
        assertEquals(List.of(BigInteger.ZERO), evaluate(zero, List.of(BigInteger.ZERO), List.of(BigInteger.ONE.negate())));
        assertEquals(0, zero.magnitudeBits().get(zero.outputs().getFirst()));
    }

    @Test void rejectsFractionsInEachPhaseAndMalformedProgramsWithoutPartialPlans() {
        var fractional = List.of(List.of(ExactRational.parse("1/2")));
        var unit = List.of(row(1));
        var profile = new Profile(List.of(1), List.of(1));
        for (var program : List.of(new BilinearProgram(fractional, unit, unit),
            new BilinearProgram(unit, fractional, unit), new BilinearProgram(unit, unit, fractional))) {
            var result = BilinearIntegerSchedule.plan(program, profile, LIMITS, () -> false);
            assertEquals("NON_INTEGRAL_COEFFICIENT", result.reason());
            assertTrue(result.value().isEmpty());
        }
        assertEquals("PROGRAM_SHAPE", BilinearIntegerSchedule.plan(new BilinearProgram(unit, unit, List.of(row(1, 1))),
            profile, LIMITS, () -> false).reason());
        var empty = plan(new BilinearProgram(List.of(), List.of(), List.of(List.of())), profile);
        assertEquals(List.of(BigInteger.ZERO), evaluate(empty, List.of(BigInteger.ONE), List.of(BigInteger.ONE)));
    }

    @Test void budgetsApplyToPlanningAndToMagnitudeSensitiveExecution() {
        var program = new BilinearProgram(List.of(row(1)), List.of(row(1)), List.of(row(1)));
        var profile = new Profile(List.of(4096), List.of(4096));
        var schedule = plan(program, profile);
        var input = List.of(BigInteger.ONE.shiftLeft(4095));
        for (var low : List.of(LIMITS.withMaximumWork(1), new IterationLimits(50_000, 2, 1000))) {
            assertEquals(BilinearProgram.Status.INCONCLUSIVE, BilinearIntegerSchedule.plan(program, profile,
                low, () -> false).status());
            assertEquals(BilinearProgram.Status.INCONCLUSIVE, BilinearIntegerSchedule.evaluate(schedule, input,
                input, low, () -> false).status());
        }
        assertEquals("CANCELLED", BilinearIntegerSchedule.plan(program, profile, LIMITS, () -> true).reason());
        assertEquals("CANCELLED", BilinearIntegerSchedule.evaluate(schedule, input, input, LIMITS, () -> true).reason());
        assertEquals("INPUT_VECTOR_SHAPE", BilinearIntegerSchedule.evaluate(schedule, List.of(), input,
            LIMITS, () -> false).reason());
        assertThrows(UnsupportedOperationException.class, () -> schedule.steps().clear());
    }
}
