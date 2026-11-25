package de.regelsuche.math.algorithms.linalg;

import static de.regelsuche.math.algorithms.linalg.LinearProgramVerifier.Status.UNAVAILABLE;
import static de.regelsuche.math.algorithms.linalg.LinearProgramVerifier.Status.VERIFIED;
import static de.regelsuche.scalar.ExactRational.ONE;
import static de.regelsuche.scalar.ExactRational.ZERO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.linalg.LinearProgramCertificates.Certificate;
import de.regelsuche.math.algorithms.linalg.LinearProgramCertificates.Infeasible;
import de.regelsuche.math.algorithms.linalg.LinearProgramCertificates.Optimal;
import de.regelsuche.math.algorithms.linalg.LinearProgramCertificates.Problem;
import de.regelsuche.math.algorithms.linalg.LinearProgramCertificates.Unbounded;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LinearProgramSearchTest {
    private static final IterationLimits LIMITS = new IterationLimits(5_000_000, 100_000, 10_000);

    @Test
    void findsPrimalAndDualForCoupledRationalAndNegativeFreeVariables() {
        var coupled = problem(new long[][] {{1, 1}, {1, -1}, {-1, 0}, {0, -1}},
            new long[] {4, 2, 0, 0}, 3, 2);
        var optimal = assertInstanceOf(Optimal.class, solve(coupled));
        assertEquals(vector(3, 1), optimal.x());
        assertEquals(integer(11), dot(coupled.c(), optimal.x()));

        var negative = problem(new long[][] {{2, 0}, {0, 3}, {-1, 0}, {0, -1}},
            new long[] {1, -2, 3, 1}, 3, -1);
        var rational = assertInstanceOf(Optimal.class, solve(negative));
        assertEquals(List.of(fraction(1, 2), integer(-1)), rational.x());
        assertEquals(fraction(5, 2), dot(negative.c(), rational.x()));
    }

    @Test
    void derivesFarkasEvidenceIncludingAnImpossibleZeroRow() {
        var impossible = problem(new long[][] {{1}, {-1}}, new long[] {0, -1}, 7);
        assertInstanceOf(Infeasible.class, solve(impossible));
        var zeroRow = problem(new long[][] {{0, 0}}, new long[] {-1}, 0, 0);
        assertInstanceOf(Infeasible.class, solve(zeroRow));
    }

    @Test
    void constructsAnUnboundedRayFromANegativeFeasibleBase() {
        var source = problem(new long[][] {{1}}, new long[] {-2}, -3);
        var ray = assertInstanceOf(Unbounded.class, solve(source));
        assertTrue(ray.x().getFirst().compareTo(integer(-2)) <= 0);
        assertEquals(fraction(-1, 3), ray.v().getFirst());
        assertEquals(ONE, dot(source.c(), ray.v()));
    }

    @Test
    void handlesLinealityAndFeasibleRegionsWithoutVertices() {
        var strip = problem(new long[][] {{1, 0}, {-1, 0}}, new long[] {2, 2}, 1, 0);
        assertEquals(integer(2), assertInstanceOf(Optimal.class, solve(strip)).x().getFirst());
        var plane = problem(new long[][] {{0, 0}}, new long[] {0}, 0, 0);
        assertInstanceOf(Optimal.class, solve(plane));
        var freeObjective = problem(new long[][] {{0, 0}}, new long[] {1}, 1, -1);
        assertInstanceOf(Unbounded.class, solve(freeObjective));
    }

    @Test
    void retainsEqualitiesAndRedundantDegenerateRows() {
        var source = problem(new long[][] {{1, 1}, {-1, -1}, {2, 2}, {0, 0}},
            new long[] {-2, 2, -4, 0}, 1, 1);
        var optimal = assertInstanceOf(Optimal.class, solve(source));
        assertEquals(integer(-2), dot(source.c(), optimal.x()));
    }

    @Test
    void terminatesOnDegenerateRatioTiesWithAnIndependentUpperBound() {
        var source = new Problem(List.of(
            List.of(fraction(1, 2), fraction(-11, 2), fraction(-5, 2), integer(9)),
            List.of(fraction(1, 2), fraction(-3, 2), fraction(-1, 2), ONE),
            vector(1, 0, 0, 0), vector(-1, 0, 0, 0), vector(0, -1, 0, 0),
            vector(0, 0, -1, 0), vector(0, 0, 0, -1)),
            vector(0, 0, 1, 0, 0, 0, 0), vector(10, -57, -9, -24));
        // x=(1,0,1,0) and y=(0,18,1,0,30,0,42) both have value one.
        var optimal = assertInstanceOf(Optimal.class, solve(source));
        assertEquals(ONE, dot(source.c(), optimal.x()));
    }

    @Test
    void agreesWithIndependentIntervalClassificationOnAllSmallOneVariableInputs() {
        for (long first = -1; first <= 1; first++) {
            for (long second = -1; second <= 1; second++) {
                for (long firstBound = -1; firstBound <= 1; firstBound++) {
                    for (long secondBound = -1; secondBound <= 1; secondBound++) {
                        for (long objective = -1; objective <= 1; objective++) {
                            var source = problem(new long[][] {{first}, {second}},
                                new long[] {firstBound, secondBound}, objective);
                            assertIntervalAnswer(source, solve(source));
                        }
                    }
                }
            }
        }
    }

    @Test
    void solvesCoupledTransformedBoxesWithKnownOptimaContradictionsAndRays() {
        var random = new Random(20261125);
        for (int dimension = 2; dimension <= 5; dimension++) {
            for (int sample = 0; sample < 4; sample++) {
                // Unimodular row operations preserve an invertible coordinate change.
                var transform = new long[dimension][dimension];
                for (int index = 0; index < dimension; index++) transform[index][index] = 1;
                for (int step = 0; step < 4 * dimension; step++) {
                    int target = random.nextInt(dimension);
                    int source = (target + 1 + random.nextInt(dimension - 1)) % dimension;
                    int scale = random.nextBoolean() ? 1 : -1;
                    for (int column = 0; column < dimension; column++) {
                        transform[target][column] += scale * transform[source][column];
                    }
                }
                var rows = new ArrayList<List<ExactRational>>();
                var bounds = new ArrayList<ExactRational>();
                var objective = new ExactRational[dimension];
                java.util.Arrays.fill(objective, ZERO);
                var expected = ZERO;
                for (int coordinate = 0; coordinate < dimension; coordinate++) {
                    var upper = fraction(2 + sample + coordinate, 2 + sample);
                    var lower = fraction(-3 - coordinate, 1 + sample);
                    var coefficient = integer(coordinate == 0 ? 1 : random.nextInt(5) - 2);
                    rows.add(vector(transform[coordinate]));
                    bounds.add(upper);
                    rows.add(java.util.Arrays.stream(transform[coordinate])
                        .mapToObj(value -> integer(-value)).toList());
                    bounds.add(lower.negate());
                    for (int column = 0; column < dimension; column++) {
                        objective[column] = objective[column].add(coefficient.multiply(integer(transform[coordinate][column])));
                    }
                    expected = expected.add(coefficient.multiply(coefficient.signum() >= 0 ? upper : lower));
                }
                var source = new Problem(rows, bounds, List.of(objective));
                var optimum = assertInstanceOf(Optimal.class, solve(source));
                assertEquals(expected, dot(source.c(), optimum.x()));

                // The first transformed coordinate has positive objective coefficient.
                var withoutUpper = new Problem(rows.subList(1, rows.size()), bounds.subList(1, bounds.size()), source.c());
                assertInstanceOf(Unbounded.class, solve(withoutUpper));
                var inconsistentBounds = new ArrayList<>(bounds);
                inconsistentBounds.set(1, bounds.getFirst().add(ONE).negate());
                assertInstanceOf(Infeasible.class, solve(new Problem(rows, inconsistentBounds, source.c())));
            }
        }
    }

    @Test
    void generatedWitnessesRejectCorruptionAndChangedSources() {
        var source = problem(new long[][] {{1}, {-1}}, new long[] {2, 0}, 1);
        var optimum = assertInstanceOf(Optimal.class, solve(source));
        assertFalse(LinearProgramVerifier.verify(source,
            new Optimal(source, vector(3), optimum.y()), LIMITS, () -> false).valid());
        var changed = new Problem(source.a(), vector(3, 0), source.c());
        assertEquals("SOURCE_MISMATCH", LinearProgramVerifier.verify(changed, optimum,
            LIMITS, () -> false).reason());
        var impossible = problem(new long[][] {{0}}, new long[] {-1}, 1);
        var contradiction = assertInstanceOf(Infeasible.class, solve(impossible));
        assertFalse(LinearProgramVerifier.verify(impossible,
            new Infeasible(contradiction.source(), vector(0)), LIMITS, () -> false).valid());
        var unbounded = problem(new long[][] {{-1}}, new long[] {0}, 1);
        var ray = assertInstanceOf(Unbounded.class, solve(unbounded));
        assertFalse(LinearProgramVerifier.verify(unbounded,
            new Unbounded(unbounded, ray.x(), vector(0)), LIMITS, () -> false).valid());
    }

    @Test
    void resourceFailureIsUnavailableAndNeverAnInfeasibilityClaim() {
        var source = problem(new long[][] {{1}, {-1}}, new long[] {2, 0}, 1);
        assertUnavailable(source, new IterationLimits(1, 100_000, 10_000), "WORK_LIMIT");
        assertUnavailable(source, new IterationLimits(5_000_000, 1, 10_000), "TERM_LIMIT");
        var cancelled = LinearProgramSearch.search(source, LIMITS, () -> true);
        assertEquals(UNAVAILABLE, cancelled.status());
        assertEquals("CANCELLED", cancelled.reason());
        assertTrue(cancelled.certificate().isEmpty());
        var visits = new AtomicInteger();
        var midSearch = LinearProgramSearch.search(source, LIMITS, () -> visits.incrementAndGet() > 20);
        assertEquals("CANCELLED", midSearch.reason());
        assertTrue(midSearch.certificate().isEmpty());
    }

    @Test
    void accountsForIndependentVerificationAndTheFinalCancellationCheck() {
        var source = problem(new long[][] {{1}, {-1}}, new long[] {2, 0}, 1);
        var visits = new AtomicInteger();
        var complete = LinearProgramSearch.search(source, LIMITS, () -> {
            visits.incrementAndGet();
            return false;
        });
        assertEquals(VERIFIED, complete.status());
        assertTrue(complete.work() > 1);
        assertUnavailable(source, LIMITS.withMaximumWork(complete.work() - 1), "WORK_LIMIT");
        var exact = LinearProgramSearch.search(source, LIMITS.withMaximumWork(complete.work()), () -> false);
        assertEquals(VERIFIED, exact.status());
        assertEquals(complete.work(), exact.work());
        int allVisits = visits.get();
        visits.set(0);
        var lastCancellation = LinearProgramSearch.search(source, LIMITS,
            () -> visits.incrementAndGet() >= allVisits);
        assertEquals("CANCELLED", lastCancellation.reason());
        assertTrue(lastCancellation.certificate().isEmpty());
    }

    @Test
    void rejectsOversizedInputsAndStopsWhenAnAdmittedPivotGrowsTooLarge() {
        var huge = ExactRational.integer(BigInteger.ONE.shiftLeft(IterationLimits.MAX_RUNTIME_BITS));
        var source = new Problem(List.of(List.of(ONE)), List.of(huge), List.of(ONE));
        assertUnavailable(source, LIMITS, "RUNTIME_BIT_LIMIT");
        var admitted = BigInteger.ONE.shiftLeft(40_000);
        var growing = new Problem(List.of(List.of(new ExactRational(BigInteger.ONE, admitted))),
            List.of(ExactRational.integer(admitted)), List.of(ONE));
        assertUnavailable(growing, LIMITS, "RUNTIME_BIT_LIMIT");
    }

    @Test
    void aDeadlineIsAnUnavailableResult() {
        var source = problem(new long[][] {{1}}, new long[] {1}, 1);
        var result = LinearProgramSearch.search(source, new IterationLimits(5_000_000, 100_000, 1), () -> {
            java.util.concurrent.locks.LockSupport.parkNanos(25_000_000);
            return false;
        });
        assertEquals(UNAVAILABLE, result.status());
        assertEquals("TIME_LIMIT", result.reason());
        assertTrue(result.certificate().isEmpty());
    }

    private static Certificate solve(Problem source) {
        var result = LinearProgramSearch.search(source, LIMITS, () -> false);
        assertEquals(VERIFIED, result.status(), result.reason());
        var certificate = result.certificate().orElseThrow();
        assertEquals(source, certificate.source());
        assertTrue(LinearProgramVerifier.verify(source, certificate, LIMITS, () -> false).valid());
        return certificate;
    }

    private static void assertUnavailable(Problem source, IterationLimits limits, String reason) {
        var result = LinearProgramSearch.search(source, limits, () -> false);
        assertEquals(UNAVAILABLE, result.status());
        assertEquals(reason, result.reason());
        assertTrue(result.certificate().isEmpty());
        assertTrue(result.work() <= limits.maximumWork());
    }

    private static void assertIntervalAnswer(Problem source, Certificate result) {
        ExactRational lower = null;
        ExactRational upper = null;
        boolean impossible = false;
        for (int row = 0; row < source.constraints(); row++) {
            var coefficient = source.a().get(row).getFirst();
            var bound = source.b().get(row);
            if (coefficient.isZero()) {
                impossible |= bound.signum() < 0;
            } else {
                var endpoint = bound.divide(coefficient);
                if (coefficient.signum() > 0 && (upper == null || endpoint.compareTo(upper) < 0)) upper = endpoint;
                if (coefficient.signum() < 0 && (lower == null || endpoint.compareTo(lower) > 0)) lower = endpoint;
            }
        }
        if (impossible || lower != null && upper != null && lower.compareTo(upper) > 0) {
            assertInstanceOf(Infeasible.class, result);
        } else {
            var objective = source.c().getFirst();
            if (objective.signum() > 0 && upper == null || objective.signum() < 0 && lower == null) {
                assertInstanceOf(Unbounded.class, result);
            } else {
                var optimum = assertInstanceOf(Optimal.class, result);
                var expected = objective.isZero() ? ZERO
                    : objective.multiply(objective.signum() > 0 ? upper : lower);
                assertEquals(expected, dot(source.c(), optimum.x()));
            }
        }
    }

    private static ExactRational dot(List<ExactRational> first, List<ExactRational> second) {
        var result = ZERO;
        for (int index = 0; index < first.size(); index++) result = result.add(first.get(index).multiply(second.get(index)));
        return result;
    }

    private static Problem problem(long[][] a, long[] b, long... c) {
        var rows = new ArrayList<List<ExactRational>>();
        for (long[] row : a) rows.add(vector(row));
        return new Problem(rows, vector(b), vector(c));
    }

    private static List<ExactRational> vector(long... values) {
        return java.util.Arrays.stream(values).mapToObj(ExactRational::integer).toList();
    }

    private static ExactRational integer(long value) { return ExactRational.integer(value); }

    private static ExactRational fraction(long numerator, long denominator) {
        return new ExactRational(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
    }
}
