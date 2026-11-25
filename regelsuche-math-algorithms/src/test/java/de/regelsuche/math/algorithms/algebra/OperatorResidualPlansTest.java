package de.regelsuche.math.algorithms.algebra;

import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.linalg.ExactMatrixAlgebra;
import de.regelsuche.math.algorithms.linalg.ExactMatrixExpression;
import de.regelsuche.math.algorithms.linalg.SymbolicLinearSystem.PolynomialMatrix;
import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OperatorResidualPlansTest {
    static final IterationLimits LIMITS = new IterationLimits(5_000_000, 100_000, 10_000);
    static final ExactMatrixAlgebra FIXTURES = new ExactMatrixAlgebra(new ExactMatrixAlgebra.Work(5_000_000));

    static PolynomialMatrix matrix(long[]... rows) {
        return FIXTURES.matrix(rows.length, rows[0].length,
            (i, j) -> Polynomial.constant(Rational.of(rows[i][j])));
    }

    static ExactMatrixExpression literal(PolynomialMatrix value) {
        return new ExactMatrixExpression.Matrix("literal", value);
    }

    @Test void repairAndSelectByWholeCost() {
        var unit = FIXTURES.identity(3);
        var src = matrix(new long[]{4,0,0}, new long[]{0,1,0}, new long[]{0,0,1});
        var plan = OperatorResidualPlans.spectral(src, new ExactMatrixExpression.Identity(3),
            unit, unit, LIMITS, () -> false).requireValue();
        assertEquals(1, plan.correction().orElseThrow().lift().columns());
        var fullWidth = new OperatorResidualPlans.Plan(src, plan.predictor(), Optional.of(
            new OperatorResidualPlans.Factors(matrix(new long[]{3,0,0}, new long[]{0,0,0}, new long[]{0,0,0}), unit)));
        var onlyExpensive = OperatorResidualPlans.select(src, List.of(fullWidth), 1, LIMITS, () -> false).requireValue();
        assertEquals(onlyExpensive.directCost(), onlyExpensive.cost());
        var selected = OperatorResidualPlans.select(src, List.of(fullWidth, plan), 2, LIMITS, () -> false).requireValue();
        assertEquals(plan, selected.plan());
        assertEquals(11, selected.cost().total());
        assertEquals(15, selected.directCost().total());
        var wrong = new OperatorResidualPlans.Plan(src, literal(unit), Optional.empty());
        assertEquals("IDENTITY_MISMATCH", OperatorResidualPlans.verify(src, wrong, LIMITS, () -> false).detail());
        assertEquals("SOURCE_MISMATCH", OperatorResidualPlans.verify(unit, plan, LIMITS, () -> false).detail());
    }

    static Polynomial rational(long numerator, long denominator) {
        return Polynomial.constant(new Rational(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator)));
    }

    // S=I+t*e_(0,1); S D S^-1 differs from D only at (0,1).
    static PolynomialMatrix conjugated(long[] diagonal, Rational t) {
        return FIXTURES.matrix(diagonal.length, diagonal.length, (i, j) -> {
            if (i.equals(j)) return Polynomial.constant(Rational.of(diagonal[i]));
            if (i == 0 && j == 1)
                return Polynomial.constant(t.multiply(Rational.of(diagonal[1] - diagonal[0])));
            return Polynomial.zero();
        });
    }

    static PolynomialMatrix shearBasis(int n, Rational t) {
        return FIXTURES.matrix(n, n, (i, j) -> Polynomial.constant(i.equals(j) ? Rational.ONE
            : i == 0 && j == 1 ? t : Rational.ZERO));
    }

    @Test void conjugatedThreeInvolutionsAgainstIndependentProjectors() {
        for (var t : List.of(new Rational(BigInteger.ONE, BigInteger.TWO), Rational.of(-3))) {
            var unit = FIXTURES.identity(8);
            var plus = unit;
            var minus = unit;
            ExactMatrixExpression predictor = null;
            var signs = new long[8];
            var expected = new long[8];
            for (int axis = 0; axis < 3; axis++) {
                for (int i = 0; i < 8; i++) {
                    signs[i] = ((i >> axis) & 1) == 0 ? 1 : -1;
                    expected[i] = Integer.bitCount(i) % 2 == 0 ? 1 : -1;
                }
                var involution = conjugated(signs, t);
                var negative = FIXTURES.matrix(8, 8, (i, j) -> involution.get(i, j).multiply(Rational.NEGATIVE_ONE));
                plus = FIXTURES.multiply(plus, FIXTURES.add(unit, involution));
                minus = FIXTURES.multiply(minus, FIXTURES.add(unit, negative));
                predictor = predictor == null ? literal(negative) : new ExactMatrixExpression.Sum(predictor, literal(negative));
            }
            var source = conjugated(expected, t);
            var plan = OperatorResidualPlans.spectral(source, predictor, shearBasis(8, t),
                shearBasis(8, t.multiply(Rational.NEGATIVE_ONE)), LIMITS, () -> false).requireValue();
            var factors = plan.correction().orElseThrow();
            assertEquals(2, factors.lift().columns());
            var repaired = FIXTURES.multiply(factors.lift(), factors.coordinates());
            // 4(Eplus - Eminus) with Eplus/minus defined by their three ordered factors / 8.
            var half = new Rational(BigInteger.ONE, BigInteger.TWO);
            for (int i = 0; i < 8; i++) for (int j = 0; j < 8; j++) {
                var projectors = plus.get(i,j).add(minus.get(i,j).multiply(Rational.NEGATIVE_ONE));
                assertEquals(projectors.multiply(half), repaired.get(i,j));
            }
            assertEquals(new OperatorResidualPlans.Cost(224,214),
                OperatorResidualPlans.verify(source, plan, LIMITS, () -> false).requireValue());
            var best = OperatorResidualPlans.select(source, List.of(plan), 1, LIMITS, () -> false).requireValue();
            assertEquals(120, best.cost().total());
            assertEquals(best.cost(), best.directCost());
        }
    }

    @Test void guardsAndCancellationAtCompletion() {
        var unit = FIXTURES.identity(2);
        var f = matrix(new long[]{1, 0}, new long[]{0, -1});
        var swap = matrix(new long[]{0,1}, new long[]{1,0});
        assertEquals("NOT_COMMON_EIGENBASIS", OperatorResidualPlans.spectral(f, literal(swap), unit, unit,
            LIMITS, () -> false).detail());
        assertEquals("BASIS_INVERSE_MISMATCH", OperatorResidualPlans.spectral(f, literal(f), swap, unit,
            LIMITS, () -> false).detail());
        var plan = OperatorResidualPlans.spectral(f, literal(f), unit, unit, LIMITS, () -> false).requireValue();
        assertTrue(plan.correction().isEmpty());
        var forged = new OperatorResidualPlans.Plan(f, literal(unit), Optional.of(new OperatorResidualPlans.Factors(unit, unit)));
        assertEquals("IDENTITY_MISMATCH", OperatorResidualPlans.verify(f, forged, LIMITS, () -> false).detail());
        assertEquals("CANDIDATE_LIMIT", OperatorResidualPlans.select(f, List.of(plan), 0, LIMITS, () -> false).detail());
        assertEquals("WORK_LIMIT", OperatorResidualPlans.verify(f, plan, LIMITS.withMaximumWork(1), () -> false).detail());
        assertEquals("TERM_LIMIT", OperatorResidualPlans.verify(f, plan, new IterationLimits(10000, 1, 1000), () -> false).detail());
        var symbolic = FIXTURES.matrix(1, 1, (i,j) -> Polynomial.variable("v"));
        assertEquals(OperatorResidualPlans.Status.UNSUPPORTED, OperatorResidualPlans.verify(symbolic, plan, LIMITS, () -> false).status());
        var polls = new AtomicInteger();
        assertTrue(OperatorResidualPlans.verify(f, plan, LIMITS, () -> { polls.incrementAndGet(); return false; }).value().isPresent());
        int last = polls.get();
        polls.set(0);
        assertEquals("CANCELLED", OperatorResidualPlans.verify(f, plan, LIMITS, () -> polls.incrementAndGet() >= last).detail());
    }

    @Test void rectangularResidualAndWideCoefficients() {
        var coefficient = new Rational(BigInteger.ONE.shiftLeft(200).add(BigInteger.ONE), BigInteger.valueOf(3));
        var target = FIXTURES.matrix(2, 3, (i, j) -> Polynomial.constant(i == 0 ? coefficient : Rational.ZERO));
        var zero = FIXTURES.matrix(2, 3, (i,j) -> Polynomial.zero());
        var lift = FIXTURES.matrix(2, 1, (i,j) -> Polynomial.constant(i == 0 ? coefficient : Rational.ZERO));
        var row = matrix(new long[]{1, 1, 1});
        var good = new OperatorResidualPlans.Plan(target, literal(zero),
            Optional.of(new OperatorResidualPlans.Factors(lift, row)));
        assertEquals(OperatorResidualPlans.Status.VERIFIED,
            OperatorResidualPlans.verify(target, good, LIMITS, () -> false).status());
        var bad = new OperatorResidualPlans.Plan(target, literal(zero),
            Optional.of(new OperatorResidualPlans.Factors(row, lift)));
        assertEquals("PRODUCT_DIMENSION_MISMATCH", OperatorResidualPlans.verify(target, bad, LIMITS, () -> false).detail());
        assertTrue(OperatorResidualPlans.select(target, List.of(good,bad), 2, LIMITS, () -> false).value().isEmpty());
    }

    @Test void deadlineStopsInsideProof() {
        var unit = FIXTURES.identity(2);
        var polls = new AtomicInteger();
        var result = OperatorResidualPlans.spectral(unit, literal(unit), unit, unit, new IterationLimits(1_000_000, 1000, 10),
            () -> {
                if (polls.incrementAndGet() == 12) {
                    long until = System.nanoTime() + 25_000_000;
                    long remaining;
                    while ((remaining = until - System.nanoTime()) > 0)
                        java.util.concurrent.locks.LockSupport.parkNanos(remaining);
                }
                return false;
            });
        assertEquals("TIME_LIMIT", result.detail());
        assertTrue(result.value().isEmpty());
    }

    @Test void noncommutingInvolutionsLoseTheProjectorClaim() {
        var unit = FIXTURES.identity(2);
        var z = matrix(new long[]{1,0}, new long[]{0,-1});
        var x = matrix(new long[]{0,1}, new long[]{1,0});
        var source = FIXTURES.multiply(z, x); // Third involution is I.
        assertNotEquals(source, FIXTURES.multiply(x,z));
        var sum = FIXTURES.add(FIXTURES.add(z, x), unit);
        var predictor = FIXTURES.matrix(2, 2, (i,j) -> sum.get(i,j).multiply(Rational.NEGATIVE_ONE));
        var plus = FIXTURES.matrix(2, 2, (i,j) -> i == 0 ? rational(1,2) : Polynomial.zero());
        assertNotEquals(plus, FIXTURES.multiply(plus, plus));
        // Eminus vanishes since Q3=I. The ordered expansion still equals F.
        var correction = FIXTURES.matrix(2, 2, (i,j) -> plus.get(i,j).multiply(Rational.of(4)));
        assertEquals(source, FIXTURES.add(predictor, correction));
        assertEquals("NOT_COMMON_EIGENBASIS", OperatorResidualPlans.spectral(source, literal(predictor),
            unit, unit, LIMITS, () -> false).detail());
    }

    @Test void denseRankOnePerturbationOfIdentity() {
        long[] u = {1, 2, 3, 4};
        long[] v = {2, 3, 5, 7};
        long pairing = 51; // v^T u
        var target = FIXTURES.matrix(4, 4, (i,j) -> rational(u[i]*v[j] + (i.equals(j) ? 1 : 0), 1));
        var basis = FIXTURES.matrix(4, 4, (i,j) -> {
            if (j == 0) return rational(u[i], 1);
            return i == 0 ? rational(-v[j], v[0]) : rational(i.equals(j) ? 1 : 0, 1);
        });
        var inverse = FIXTURES.matrix(4, 4, (i,j) -> {
            if (i == 0) return rational(v[j], pairing);
            return rational((i.equals(j) ? pairing : 0) - u[i]*v[j], pairing);
        });
        var plan = OperatorResidualPlans.spectral(target, new ExactMatrixExpression.Identity(4),
            basis, inverse, LIMITS, () -> false).requireValue();
        var result = OperatorResidualPlans.select(target, List.of(plan), 1, LIMITS, () -> false).requireValue();
        assertEquals(1, plan.correction().orElseThrow().lift().columns());
        assertEquals(plan, result.plan());
        assertEquals(15, result.cost().total());
        assertEquals(28, result.directCost().total());
    }
}
