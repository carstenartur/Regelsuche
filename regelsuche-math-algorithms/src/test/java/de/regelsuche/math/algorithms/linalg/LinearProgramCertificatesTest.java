package de.regelsuche.math.algorithms.linalg;

import static de.regelsuche.math.algorithms.linalg.LinearProgramVerifier.Status.INVALID;
import static de.regelsuche.math.algorithms.linalg.LinearProgramVerifier.Status.UNAVAILABLE;
import static de.regelsuche.math.algorithms.linalg.LinearProgramVerifier.Status.VERIFIED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
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
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class LinearProgramCertificatesTest {
    private static final IterationLimits LIMITS = new IterationLimits(2_000_000, 50_000, 5_000);
    private static final BooleanSupplier ACTIVE = () -> false;

    @Test
    void verifiesContradictionAndNormalizesItsScale() {
        Problem problem = problem(new long[][] {{1}, {-1}}, new long[] {0, -1}, 0);
        var input = new Infeasible(problem, vector(7, 7));

        assertEquals(VERIFIED, LinearProgramVerifier.verify(problem, input, LIMITS, ACTIVE).status());
        var result = FarkasSparsifier.sparsify(problem, input, LIMITS, ACTIVE);
        assertEquals(VERIFIED, result.status());
        assertEquals(vector(1, 1), result.certificate().orElseThrow().y());
    }

    @Test
    void removesRedundantRowsInRankDeficientRationalContradiction() {
        Problem problem = new Problem(
            List.of(vector(1, 2, 0), vector(-1, -2, 0),
                List.of(rational(3, 2), integer(3), integer(0)), vector(-4, -8, 0), vector(0, 0, 0)),
            vector(0, -1, 0, -4, -9), vector(0, 0, 0));
        var input = new Infeasible(problem,
            List.of(rational(1, 2), integer(1), integer(3), integer(1), integer(0)));

        assertEquals(VERIFIED, LinearProgramVerifier.verify(problem, input, LIMITS, ACTIVE).status());
        var result = FarkasSparsifier.sparsify(problem, input, LIMITS, ACTIVE);
        assertEquals(VERIFIED, result.status());
        var sparse = result.certificate().orElseThrow();
        assertEquals(problem, sparse.source());
        assertTrue(supportSize(sparse.y()) <= 2);
        assertTrue(supportSize(sparse.y()) < supportSize(input.y()));
        assertTrue(sparse.y().getLast().isZero());
        assertNormalized(problem, sparse.y());
    }

    @Test
    void reducesZeroRankToOneActiveConstraint() {
        Problem problem = problem(new long[][] {{0, 0}, {0, 0}, {0, 0}}, new long[] {0, -2, -3}, 0, 0);
        var result = FarkasSparsifier.sparsify(problem, new Infeasible(problem, vector(1, 2, 1)), LIMITS, ACTIVE);

        assertEquals(VERIFIED, result.status());
        var weights = result.certificate().orElseThrow().y();
        assertEquals(1, supportSize(weights));
        assertNormalized(problem, weights);
    }

    @Test
    void verifiesMatchingPrimalAndDualValuesAtAFractionalMaximum() {
        Problem problem = problem(new long[][] {{2, 1}, {0, 1}, {0, -1}}, new long[] {4, 1, 0}, 2, 3);
        var optimum = new Optimal(problem, List.of(rational(3, 2), integer(1)), vector(1, 2, 0));

        assertEquals(VERIFIED, LinearProgramVerifier.verify(problem, optimum, LIMITS, ACTIVE).status());
        assertEquals(INVALID, LinearProgramVerifier.verify(problem,
            new Optimal(problem, vector(1, 1), optimum.y()), LIMITS, ACTIVE).status());
        assertEquals(INVALID, LinearProgramVerifier.verify(problem,
            new Optimal(problem, optimum.x(), vector(2, 0, 0)), LIMITS, ACTIVE).status());
        assertEquals(INVALID, LinearProgramVerifier.verify(problem,
            new Optimal(problem, vector(3, 0), optimum.y()), LIMITS, ACTIVE).status());
    }

    @Test
    void acceptsNegativeCoordinatesForUnrestrictedVariables() {
        Problem problem = problem(new long[][] {{1}}, new long[] {-2}, -1);
        var ray = new Unbounded(problem, vector(-2), vector(-1));

        assertEquals(VERIFIED, LinearProgramVerifier.verify(problem, ray, LIMITS, ACTIVE).status());
    }

    @Test
    void verifiesFeasiblePointAndUnitObjectiveRay() {
        Problem problem = problem(new long[][] {{-1, 0}, {0, 1}}, new long[] {-1, 4}, 2, 1);
        var ray = new Unbounded(problem, vector(1, 4), List.of(rational(1, 2), integer(0)));

        assertEquals(VERIFIED, LinearProgramVerifier.verify(problem, ray, LIMITS, ACTIVE).status());
        assertEquals(INVALID, LinearProgramVerifier.verify(problem,
            new Unbounded(problem, ray.x(), vector(1, 0)), LIMITS, ACTIVE).status());
        assertEquals(INVALID, LinearProgramVerifier.verify(problem,
            new Unbounded(problem, vector(0, 4), ray.v()), LIMITS, ACTIVE).status());
        assertEquals(INVALID, LinearProgramVerifier.verify(problem,
            new Unbounded(problem, ray.x(), vector(0, 1)), LIMITS, ACTIVE).status());
    }

    @Test
    void rejectsNegativeWeightsAndNonContradictions() {
        Problem problem = problem(new long[][] {{1}, {-1}, {0}}, new long[] {1, 0, 1}, 0);
        for (List<ExactRational> y : List.of(vector(0, 0, -1), vector(1, 1, 0),
                vector(0, 0, 0), vector(1, 0, 0))) {
            var input = new Infeasible(problem, y);
            assertEquals(INVALID, LinearProgramVerifier.verify(problem, input, LIMITS, ACTIVE).status());
            var reduction = FarkasSparsifier.sparsify(problem, input, LIMITS, ACTIVE);
            assertEquals(INVALID, reduction.status());
            assertTrue(reduction.certificate().isEmpty());
        }
    }

    @Test
    void bindsEverySourceCoordinateIncludingTheObjective() {
        Problem problem = problem(new long[][] {{1}, {-1}}, new long[] {0, -1}, 0);
        var input = new Infeasible(problem, vector(1, 1));
        var equalSource = problem(new long[][] {{1}, {-1}}, new long[] {0, -1}, 0);

        assertEquals(VERIFIED, LinearProgramVerifier.verify(equalSource, input, LIMITS, ACTIVE).status());
        for (var altered : List.of(problem(new long[][] {{1}, {-1}}, new long[] {0, -1}, 1),
                problem(new long[][] {{1}, {-1}}, new long[] {1, -2}, 0),
                problem(new long[][] {{2}, {-2}}, new long[] {0, -1}, 0),
                problem(new long[][] {{1}, {-1}, {0}}, new long[] {0, -1, 1}, 0))) {
            assertEquals(INVALID, LinearProgramVerifier.verify(altered, input, LIMITS, ACTIVE).status());
            var reduction = FarkasSparsifier.sparsify(altered, input, LIMITS, ACTIVE);
            assertEquals(INVALID, reduction.status());
            assertFalse(reduction.certificate().isPresent());
        }
    }

    @Test
    void rejectsMalformedDimensionsAndRetainsImmutableInputs() {
        assertThrows(IllegalArgumentException.class, () -> new Problem(List.of(), List.of(), vector(1)));
        assertThrows(IllegalArgumentException.class,
            () -> new Problem(List.of(List.of()), vector(0), List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> new Problem(List.of(vector(1), vector(1, 2)), vector(0, 0), vector(1)));
        assertThrows(IllegalArgumentException.class,
            () -> new Problem(List.of(vector(1)), List.of(), vector(1)));
        assertThrows(IllegalArgumentException.class,
            () -> new Problem(List.of(vector(1)), vector(0), vector(1, 2)));

        var firstRow = new ArrayList<>(vector(1));
        var rows = new ArrayList<List<ExactRational>>(List.of(firstRow, vector(-1)));
        var rhs = new ArrayList<>(vector(0, -1));
        var objective = new ArrayList<>(vector(0));
        Problem problem = new Problem(rows, rhs, objective);
        var witness = new ArrayList<>(vector(1, 1));
        var certificate = new Infeasible(problem, witness);
        firstRow.set(0, integer(2));
        rows.clear();
        rhs.clear();
        objective.clear();
        witness.clear();

        assertEquals(VERIFIED, LinearProgramVerifier.verify(problem, certificate, LIMITS, ACTIVE).status());
        assertEquals(INVALID, LinearProgramVerifier.verify(problem,
            new Infeasible(problem, vector(1)), LIMITS, ACTIVE).status());
        assertEquals(INVALID, LinearProgramVerifier.verify(problem,
            new Optimal(problem, vector(1, 2), vector(1, 1)), LIMITS, ACTIVE).status());
        assertEquals(INVALID, LinearProgramVerifier.verify(problem,
            new Unbounded(problem, vector(1), vector(1, 2)), LIMITS, ACTIVE).status());
    }

    @Test
    void preservesBeyondTwoHundredBitRationalValues() {
        BigInteger large = BigInteger.ONE.shiftLeft(220);
        var coefficient = new ExactRational(large.add(BigInteger.ONE), large.subtract(BigInteger.ONE));
        var reversed = coefficient.negate();
        var problem = new Problem(List.of(List.of(coefficient), List.of(reversed)),
            List.of(integer(0), reversed), vector(0));
        var result = FarkasSparsifier.sparsify(problem, new Infeasible(problem, vector(1, 1)), LIMITS, ACTIVE);

        assertEquals(VERIFIED, result.status());
        var inverse = new ExactRational(large.subtract(BigInteger.ONE), large.add(BigInteger.ONE));
        assertEquals(List.of(inverse, inverse), result.certificate().orElseThrow().y());
    }

    @Test
    void inspectsWitnessValuesBeforePrimalArithmetic() {
        Problem problem = problem(new long[][] {{-1}}, new long[] {-1}, 1);
        var tooLarge = ExactRational.integer(BigInteger.ONE.shiftLeft(IterationLimits.MAX_RUNTIME_BITS));
        var input = new Unbounded(problem, vector(0), List.of(tooLarge));

        var result = LinearProgramVerifier.verify(problem, input, LIMITS, ACTIVE);
        assertEquals(UNAVAILABLE, result.status());
        assertEquals("RUNTIME_BIT_LIMIT", result.reason());
    }

    @Test
    void separatesWorkStorageAndCancellationFromMathematicalRejection() {
        Problem problem = problem(new long[][] {{1}, {-1}, {-1}}, new long[] {0, -1, -1}, 0);
        var input = new Infeasible(problem, vector(2, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new IterationLimits(0, 10, 1_000));

        var tinyWork = LIMITS.withMaximumWork(1);
        var tinyStorage = new IterationLimits(LIMITS.maximumWork(), 1, LIMITS.timeoutMillis());
        var verificationOnlyStorage = new IterationLimits(LIMITS.maximumWork(), 20, LIMITS.timeoutMillis());
        assertEquals(UNAVAILABLE, LinearProgramVerifier.verify(problem, input, tinyWork, ACTIVE).status());
        assertEquals(UNAVAILABLE, LinearProgramVerifier.verify(problem, input, tinyStorage, ACTIVE).status());
        assertEquals(UNAVAILABLE, LinearProgramVerifier.verify(problem, input, LIMITS, () -> true).status());
        assertTrue(FarkasSparsifier.sparsify(problem, input, tinyWork, ACTIVE).certificate().isEmpty());
        assertEquals(UNAVAILABLE, FarkasSparsifier.sparsify(problem, input, tinyStorage, ACTIVE).status());
        assertEquals(VERIFIED, LinearProgramVerifier.verify(problem, input, verificationOnlyStorage, ACTIVE).status());
        var workspaceLimit = FarkasSparsifier.sparsify(problem, input, verificationOnlyStorage, ACTIVE);
        assertEquals(UNAVAILABLE, workspaceLimit.status());
        assertEquals("TERM_LIMIT", workspaceLimit.reason());

        long validation = LinearProgramVerifier.verify(problem, input, LIMITS, ACTIVE).work();
        var checks = new AtomicInteger();
        var passing = FarkasSparsifier.sparsify(problem, input, LIMITS, () -> {
            checks.incrementAndGet();
            return false;
        });
        assertTrue(passing.work() > validation);
        int finalCheck = checks.get();
        checks.set(0);
        var cancelled = FarkasSparsifier.sparsify(problem, input, LIMITS,
            () -> checks.incrementAndGet() >= finalCheck);
        assertEquals(UNAVAILABLE, cancelled.status());
        assertEquals(passing.work(), cancelled.work());
        assertTrue(cancelled.certificate().isEmpty());

        var oneUnitShort = FarkasSparsifier.sparsify(problem, input,
            LIMITS.withMaximumWork(passing.work() - 1), ACTIVE);
        assertEquals(UNAVAILABLE, oneUnitShort.status());
        assertTrue(oneUnitShort.certificate().isEmpty());
    }

    @Test
    void generatedContradictionsMatchOriginalDotProductsAndIndependentRank() {
        var random = new Random(0xda60481);
        int strictReductions = 0;
        for (int trial = 0; trial < 60; trial++) {
            int columns = random.nextInt(1, 5);
            int pairs = random.nextInt(2, 7);
            var rows = new ArrayList<List<ExactRational>>();
            var rhs = new ArrayList<ExactRational>();
            var weights = new ArrayList<ExactRational>();
            for (int i = 0; i < pairs; i++) {
                var direction = new ArrayList<ExactRational>();
                for (int column = 0; column < columns; column++) {
                    direction.add(rational(random.nextInt(-3, 4), random.nextInt(1, 5)));
                }
                var scale = rational(random.nextInt(1, 7), random.nextInt(1, 5));
                rows.add(direction);
                rows.add(direction.stream().map(value -> value.multiply(scale).negate()).toList());
                var target = integer(random.nextInt(-4, 5));
                rhs.add(target);
                rhs.add(target.multiply(scale).negate().subtract(rational(1, random.nextInt(1, 5))));
                weights.add(scale);
                weights.add(integer(1));
            }
            rows.add(directionZero(columns));
            rhs.add(integer(-3));
            weights.add(integer(0));

            var problem = new Problem(rows, rhs, directionZero(columns));
            var result = FarkasSparsifier.sparsify(problem, new Infeasible(problem, weights), LIMITS, ACTIVE);
            assertEquals(VERIFIED, result.status(), "trial " + trial);
            var sparse = result.certificate().orElseThrow();
            assertEquals(VERIFIED, LinearProgramVerifier.verify(problem, sparse, LIMITS, ACTIVE).status());
            assertNormalized(problem, sparse.y());
            assertEquals(integer(0), sparse.y().getLast());
            assertTrue(supportSize(sparse.y()) <= rank(problem.a()) + 1);
            if (supportSize(sparse.y()) < supportSize(weights)) strictReductions++;
        }
        assertTrue(strictReductions > 0);
    }

    private static void assertNormalized(Problem problem, List<ExactRational> y) {
        for (ExactRational value : y) {
            assertTrue(value.signum() >= 0);
        }
        assertEquals(integer(-1), dot(problem.b(), y));
        for (int column = 0; column < problem.c().size(); column++) {
            var coefficients = new ArrayList<ExactRational>();
            for (var row : problem.a()) coefficients.add(row.get(column));
            assertEquals(integer(0), dot(coefficients, y));
        }
    }

    private static ExactRational dot(List<ExactRational> first, List<ExactRational> second) {
        var sum = integer(0);
        for (int i = 0; i < first.size(); i++) sum = sum.add(first.get(i).multiply(second.get(i)));
        return sum;
    }

    /** Fraction-preserving elimination in the original row orientation, independent of sparse construction. */
    private static int rank(List<List<ExactRational>> input) {
        var matrix = input.stream().map(row -> row.toArray(ExactRational[]::new))
            .toArray(ExactRational[][]::new);
        int rank = 0;
        for (int column = 0; column < matrix[0].length; column++) {
            int pivot = rank;
            while (pivot < matrix.length && matrix[pivot][column].isZero()) pivot++;
            if (pivot == matrix.length) continue;
            var pivotRow = matrix[pivot];
            matrix[pivot] = matrix[rank];
            matrix[rank] = pivotRow;
            for (int row = rank + 1; row < matrix.length; row++) {
                var multiplier = matrix[row][column];
                for (int j = column; j < matrix[row].length; j++) {
                    matrix[row][j] = matrix[row][j].multiply(pivotRow[column])
                        .subtract(pivotRow[j].multiply(multiplier));
                }
            }
            rank++;
        }
        return rank;
    }

    private static long supportSize(List<ExactRational> y) {
        return y.stream().filter(value -> !value.isZero()).count();
    }

    private static List<ExactRational> directionZero(int columns) {
        return java.util.Collections.nCopies(columns, integer(0));
    }

    private static Problem problem(long[][] matrix, long[] b, long... c) {
        var rows = new ArrayList<List<ExactRational>>();
        for (long[] row : matrix) rows.add(vector(row));
        return new Problem(rows, vector(b), vector(c));
    }

    private static List<ExactRational> vector(long... values) {
        return java.util.Arrays.stream(values).mapToObj(ExactRational::integer).toList();
    }

    private static ExactRational rational(long numerator, long denominator) {
        return new ExactRational(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
    }

    private static ExactRational integer(long value) {
        return ExactRational.integer(value);
    }
}
