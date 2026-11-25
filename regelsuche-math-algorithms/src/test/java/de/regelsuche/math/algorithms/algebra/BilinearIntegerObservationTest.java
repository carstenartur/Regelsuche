package de.regelsuche.math.algorithms.algebra;

import de.regelsuche.math.algorithms.algebra.BilinearIntegerSchedule.Plan;
import de.regelsuche.math.algorithms.algebra.BilinearIntegerSchedule.Profile;
import de.regelsuche.math.algorithms.algebra.BilinearProgram.Request;
import de.regelsuche.math.algorithms.algebra.BilinearProgram.Status;
import de.regelsuche.math.algorithms.algebra.BilinearProgram.Tensor;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent execution of four signed filter observations and a zero control channel. */
class BilinearIntegerObservationTest {
    private static final IterationLimits LIMITS = new IterationLimits(5_000_000, 50_000, 20_000);
    private static final Profile PROFILE = new Profile(List.of(10, 145), List.of(130, 11, 257));
    private static final Tensor TENSOR = new Tensor(2, 3, List.of(
        row(1, -2, 0, 0, 0, 0), row(0, 0, 0, 0, -1, 2),
        row(1, -2, 0, 0, 3, -6), row(2, -4, 0, 0, -1, 2), row(0, 0, 0, 0, 0, 0)));

    @Test void signedNonunitFilterExecutesOnSparseAndUnequalWidthInputs() {
        var program = new BilinearProgram(List.of(row(1, 0), row(0, -1)),
            List.of(row(1, -2, 0), row(0, 1, -2)), List.of(row(1, 0), row(0, 1), row(1, -3), row(2, 1), row(0, 0)));
        var request = new Request(TENSOR, program.u(), program.v(), 2);
        assertEquals(Status.VERIFIED, BilinearProgramVerifier.verify(request, program, LIMITS, () -> false).status());
        var result = BilinearIntegerSchedule.plan(program, PROFILE, LIMITS, () -> false);
        assertEquals(Status.VERIFIED, result.status(), result.reason());
        var plan = result.value().orElseThrow();
        assertEquals(2, plan.cost().variableProducts());
        checkObservations(plan);
    }

    @Test void zeroObservationCanBeEncodedByTwoCancelingNonzeroProducts() {
        // p3 = (-gain1) * (-red + 2*green) = p1, so the last output 5*p1 - 5*p3 vanishes.
        var program = new BilinearProgram(List.of(row(1, 0), row(0, -1), row(-1, 0)),
            List.of(row(1, -2, 0), row(0, 1, -2), row(-1, 2, 0)),
            List.of(row(1, 0, 0), row(0, 1, 0), row(1, -3, 0), row(2, 1, 0), row(5, 0, -5)));
        var request = new Request(TENSOR, program.u(), program.v(), 3);
        assertEquals(Status.VERIFIED, BilinearProgramVerifier.verify(request, program, LIMITS, () -> false).status());
        var plan = BilinearIntegerSchedule.plan(program, PROFILE, LIMITS, () -> false);
        assertEquals(Status.VERIFIED, plan.status(), plan.reason());
        checkObservations(plan.value().orElseThrow());
    }

    @Test void portfolioReturnsOriginalObservationsForTheSelectedAndCoordinateSchedules() {
        var result = BilinearIntegerPortfolio.select(TENSOR, 4, 75, 4, PROFILE, LIMITS, () -> false);
        assertEquals(Status.VERIFIED, result.status(), result.reason());
        var selection = result.value().orElseThrow();
        assertEquals(TENSOR, selection.request().tensor());
        var selected = selection.selected();
        var direct = selection.direct().orElseThrow();
        var guarded = selection.preferredWithoutAllocationIncrease().orElseThrow();
        assertTrue(guarded.schedule().cost().allocatedObjects() <= direct.schedule().cost().allocatedObjects());
        assertTrue(guarded.schedule().cost().allocatedWords() <= direct.schedule().cost().allocatedWords());
        for (var candidate : List.of(selected, direct, guarded)) {
            assertEquals(TENSOR, candidate.request().tensor());
            var replay = BilinearProgramVerifier.verify(candidate.request(), candidate.witness().program(),
                LIMITS, () -> false);
            assertEquals(Status.VERIFIED, replay.status(), replay.reason());
            checkObservations(candidate.schedule());
        }
    }

    private static void checkObservations(Plan plan) {
        // All 243 ternary input vectors exercise positive, negative and zero coefficients independently.
        for (int sample = 0; sample < 243; sample++) {
            var vector = new ArrayList<BigInteger>(5);
            int digits = sample;
            for (int i = 0; i < 5; i++) {
                vector.add(BigInteger.valueOf(digits % 3 - 1));
                digits /= 3;
            }
            compareWithFilter(plan, vector);
        }
        compareWithFilter(plan, List.of(BigInteger.valueOf(-509), BigInteger.TWO.pow(143).subtract(BigInteger.ONE),
            BigInteger.TWO.pow(129).negate(), BigInteger.valueOf(415), BigInteger.TWO.pow(256).subtract(BigInteger.ONE)));
    }

    private static void compareWithFilter(Plan plan, List<BigInteger> input) {
        BigInteger redGreen = input.get(0).multiply(input.get(2).subtract(input.get(3).shiftLeft(1)));
        BigInteger blueGreen = input.get(1).multiply(input.get(4).shiftLeft(1).subtract(input.get(3)));
        var expected = List.of(redGreen, blueGreen, redGreen.subtract(blueGreen.multiply(BigInteger.valueOf(3))),
            redGreen.shiftLeft(1).add(blueGreen), BigInteger.ZERO);

        // Execute the lowered schedule directly, independently of tensor evaluation and of its producer.
        var slots = new ArrayList<>(input);
        assertEquals(slots.size(), plan.zeroSlot());
        slots.add(BigInteger.ZERO);
        for (var step : plan.steps()) {
            var left = slots.get(step.left());
            slots.add(switch (step.op()) {
                case ADD -> left.add(slots.get(step.right()));
                case SUBTRACT -> left.subtract(slots.get(step.right()));
                case MULTIPLY -> left.multiply(slots.get(step.right()));
                case NEGATE -> left.negate();
                case SCALE -> left.multiply(step.coefficient());
            });
        }
        assertEquals(expected, plan.outputs().stream().map(slots::get).toList());
    }

    private static List<ExactRational> row(long... values) {
        return Arrays.stream(values).mapToObj(ExactRational::integer).toList();
    }
}
