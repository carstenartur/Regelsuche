package de.regelsuche.math.algorithms.modular;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

import static java.math.BigInteger.ONE;
import static java.math.BigInteger.ZERO;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolynomialCongruenceTest {
    private static final IterationLimits LIMITS = new IterationLimits(100_000, 128, 10_000);
    private static final BooleanSupplier RUN = () -> false;

    @Test
    void findsAllRootsOverACompositeModulus() {
        assertEquals(List.of(1, 3, 5, 7), solve(request(8, 1, 0, 0, 1)).residues());
    }

    @Test
    void returnsARepeatedRootOnlyOnce() {
        assertEquals(List.of(1), solve(request(5, 0, 1, -2, 1)).residues());
    }

    @Test
    void distinguishesACompleteEmptyPreimageFromAnUnavailableScan() {
        var request = request(3, -1, 0, 0, 1);
        var solution = solve(request);
        assertEquals(List.of(), solution.residues());
        assertTrue(PolynomialCongruence.verify(request, solution, LIMITS, RUN).valid());
        assertUnavailable(request, LIMITS.withMaximumWork(1), RUN, "WORK_LIMIT");
    }

    @Test
    void handlesConstantsZeroAndModulusOne() {
        assertEquals(List.of(0, 1, 2, 3), solve(request(4, 1, -3)).residues());
        assertEquals(List.of(), solve(request(4, 1, 0)).residues());
        assertEquals(List.of(0), solve(request(1, 0, 0)).residues());
        assertEquals(List.of(0), solve(request(1, -33, 7, -11, 9)).residues());
    }

    @Test
    void matchesAnIndependentBigIntegerOracle() {
        var random = new Random(0x51e7eL);
        for (int i = 0; i < 250; i++) {
            int modulus = random.nextInt(1, 59);
            int size = random.nextInt(1, 8);
            var coefficients = new ArrayList<BigInteger>();
            for (int j = 0; j < size; j++) {
                var value = new BigInteger(130, random);
                coefficients.add(random.nextBoolean() ? value : value.negate());
            }
            var request = new PolynomialCongruence.Request(coefficients, modulus,
                BigInteger.valueOf(random.nextInt(-120, 121)));
            var solution = solve(request);
            assertEquals(oracle(request), solution.residues(), request::toString);
            assertTrue(PolynomialCongruence.verify(request, solution, LIMITS, RUN).valid(), request::toString);
        }
    }

    @Test
    void evaluatesNearTheLargestIntModulusWithoutOverflow() {
        int modulus = Integer.MAX_VALUE;
        int x = modulus - 1;
        // P(x) = -1 - 2x + 3x^2. At x = -1, the residue is 4.
        assertEquals(4, PolynomialCongruence.evaluateNormalized(new int[] {modulus - 1, modulus - 2, 3},
            modulus, x));
    }

    @Test
    void preservesExactImmutableRequestAndSolutionData() {
        var coefficients = new ArrayList<>(List.of(ZERO, ONE));
        var request = new PolynomialCongruence.Request(coefficients, 3, ONE);
        coefficients.set(0, ONE);
        var roots = new ArrayList<>(List.of(1));
        var solution = new PolynomialCongruence.Solution(request, roots);
        roots.set(0, 2);
        assertEquals(List.of(ZERO, ONE), request.coefficientsAscending());
        assertEquals(List.of(1), solution.residues());
        assertThrows(UnsupportedOperationException.class, () -> request.coefficientsAscending().add(ONE));
        assertThrows(UnsupportedOperationException.class, () -> solution.residues().add(2));
        assertTrue(PolynomialCongruence.verify(request(3, 1, 0, 1), solution, LIMITS, RUN).valid());
    }

    @Test
    void rejectsAnEmptyCoefficientListAndNonpositiveModuli() {
        assertThrows(IllegalArgumentException.class, () -> request(3, 0));
        assertThrows(IllegalArgumentException.class, () -> request(0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> request(-3, 0, 1));
    }

    @Test
    void rejectsChangedSourcesEvenWhenTheyHaveTheSameRoots() {
        var request = request(8, 1, 0, 0, 1);
        var solution = solve(request);
        for (var differentSource : List.of(request(4, 1, 0, 0, 1), request(8, 9, 0, 0, 1),
                request(8, 1, 8, 0, 1), request(8, 1, 0, 0, 1, 0))) {
            assertFalse(PolynomialCongruence.verify(differentSource, solution, LIMITS, RUN).valid());
        }
    }

    @Test
    void rejectsIncompleteIncorrectOrNoncanonicalRootSets() {
        var request = request(8, 1, 0, 0, 1);
        for (var forged : List.of(List.of(1, 3, 5), List.of(1, 1, 3, 5, 7), List.of(3, 1, 5, 7),
                List.of(-1, 1, 3, 5, 7), List.of(1, 3, 5, 7, 8), List.of(1, 2, 3, 5, 7))) {
            var solution = new PolynomialCongruence.Solution(request, forged);
            assertFalse(PolynomialCongruence.verify(request, solution, LIMITS, RUN).valid(), forged::toString);
        }
    }

    @Test
    void enforcesCoefficientAndResultStateLimits() {
        var limits = new IterationLimits(100_000, 3, 10_000);
        assertUnavailable(request(8, 1, 0, 0, 1, 0), limits, RUN, "TERM_LIMIT");
        var request = request(8, 1, 0, 0, 1);
        assertUnavailable(request, limits, RUN, "TERM_LIMIT");
        assertFalse(PolynomialCongruence.verify(request, solve(request), limits, RUN).valid());
    }

    @Test
    void enforcesAbsoluteBitLengthForCoefficientsAndTarget() {
        var overLimit = ONE.shiftLeft(IterationLimits.MAX_RUNTIME_BITS);
        var withinLimit = overLimit.shiftRight(1).negate();
        assertEquals(List.of(0, 1), solve(new PolynomialCongruence.Request(List.of(withinLimit), 2, ZERO))
            .residues());
        for (var request : List.of(new PolynomialCongruence.Request(List.of(overLimit), 2, ZERO),
                new PolynomialCongruence.Request(List.of(overLimit.negate()), 2, ZERO),
                new PolynomialCongruence.Request(List.of(ZERO), 2, overLimit.negate()))) {
            assertUnavailable(request, LIMITS, RUN, "RUNTIME_BIT_LIMIT");
            assertFalse(PolynomialCongruence.verify(request, new PolynomialCongruence.Solution(request,
                List.of(0, 1)), LIMITS, RUN).valid());
        }
    }

    @Test
    void workExhaustionDiscardsAnOtherwiseValidPreimage() {
        var request = request(24, 0, 0);
        var complete = PolynomialCongruence.solve(request, LIMITS, RUN);
        var insufficient = LIMITS.withMaximumWork(complete.work() / 2);
        assertUnavailable(request, insufficient, RUN, "WORK_LIMIT");
        assertFalse(PolynomialCongruence.verify(request, complete.solution().orElseThrow(), insufficient, RUN).valid());
    }

    @Test
    void cancellationDuringARequestedScanDiscardsAllResidues() {
        var request = request(48, 0, 0);
        var checks = new AtomicInteger();
        var complete = PolynomialCongruence.solve(request, LIMITS, () -> {
            checks.incrementAndGet();
            return false;
        });
        int stopAfter = checks.get() / 2;
        checks.set(0);
        assertUnavailable(request, LIMITS, () -> checks.incrementAndGet() > stopAfter, "CANCELLED");
        assertFalse(PolynomialCongruence.verify(request, complete.solution().orElseThrow(), LIMITS,
            () -> true).valid());
    }

    @Test
    void elapsedTimeExhaustionDiscardsTheSolutionAndPreventsVerification() {
        var request = request(8, 1, 0, 0, 1);
        var shortDeadline = new IterationLimits(100_000, 128, 1);
        BooleanSupplier slowPoll = () -> {
            assertDoesNotThrow(() -> Thread.sleep(20));
            return false;
        };
        assertUnavailable(request, shortDeadline, slowPoll, "TIME_LIMIT");
        assertFalse(PolynomialCongruence.verify(request, solve(request), shortDeadline, slowPoll).valid());
    }

    private static PolynomialCongruence.Request request(int modulus, long residue, long... coefficients) {
        var values = new ArrayList<BigInteger>();
        for (long coefficient : coefficients) values.add(BigInteger.valueOf(coefficient));
        return new PolynomialCongruence.Request(values, modulus, BigInteger.valueOf(residue));
    }

    private static PolynomialCongruence.Solution solve(PolynomialCongruence.Request request) {
        var result = PolynomialCongruence.solve(request, LIMITS, RUN);
        assertEquals(PolynomialCongruence.Status.COMPLETE, result.status(), result::reason);
        return result.solution().orElseThrow();
    }

    private static void assertUnavailable(PolynomialCongruence.Request request, IterationLimits limits,
            BooleanSupplier cancellation, String reason) {
        var result = PolynomialCongruence.solve(request, limits, cancellation);
        assertEquals(PolynomialCongruence.Status.UNAVAILABLE, result.status());
        assertEquals(reason, result.reason());
        assertTrue(result.solution().isEmpty());
        assertTrue(result.work() >= 0 && result.work() <= limits.maximumWork());
    }

    private static List<Integer> oracle(PolynomialCongruence.Request request) {
        var modulus = BigInteger.valueOf(request.modulus());
        var roots = new ArrayList<Integer>();
        for (int x = 0; x < request.modulus(); x++) {
            var sum = ZERO;
            for (int exponent = 0; exponent < request.coefficientsAscending().size(); exponent++) {
                sum = sum.add(request.coefficientsAscending().get(exponent).multiply(BigInteger.valueOf(x).pow(exponent)));
            }
            if (sum.subtract(request.residue()).mod(modulus).equals(ZERO)) roots.add(x);
        }
        return roots;
    }
}
