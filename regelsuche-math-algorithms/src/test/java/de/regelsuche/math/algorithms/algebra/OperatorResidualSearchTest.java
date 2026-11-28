package de.regelsuche.math.algorithms.algebra;

import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.linalg.ExactMatrixAlgebra;
import de.regelsuche.math.algorithms.linalg.ExactMatrixExpression;
import de.regelsuche.math.algorithms.linalg.SymbolicLinearSystem.PolynomialMatrix;
import de.regelsuche.scalar.ExactRational;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static de.regelsuche.math.algorithms.algebra.OperatorResidualPlans.Status.*;
import static de.regelsuche.math.algorithms.algebra.OperatorResidualSearch.Predictor.DIRECT;
import static de.regelsuche.math.algorithms.algebra.OperatorResidualSearch.Predictor.IDENTITY;
import static de.regelsuche.scalar.ExactRational.*;
import static org.junit.jupiter.api.Assertions.*;

class OperatorResidualSearchTest {
    private static final IterationLimits LIMITS = new IterationLimits(10_000_000, 500_000, 10_000);
    private static final ExactMatrixAlgebra FIXTURES = new ExactMatrixAlgebra(new ExactMatrixAlgebra.Work(5_000_000));

    @Test void discoversRankOneRepairWithoutAnEigenbasisAndReusesIt() {
        long[] u = {1, 2, 3, 4};
        long[] v = {2, 3, 5, 7};
        var source = FIXTURES.matrix(4, 4, (i, j) -> Polynomial.constant(Rational.of(
            u[i] * v[j] + (i.equals(j) ? 1 : 0))));
        var found = OperatorResidualSearch.discover(source, OperatorResidualSearch.Policy.defaults(), LIMITS, () -> false);
        assertEquals(VERIFIED, found.status(), found.detail());
        var prepared = found.requireValue().operator();
        assertEquals(IDENTITY, prepared.predictor());
        assertEquals(1, prepared.correctionWidth());
        assertEquals(28, prepared.directCost().total());
        assertTrue(prepared.cost().total() < prepared.directCost().total());
        assertEquals(VERIFIED, OperatorResidualPlans.verify(source, prepared.plan(), LIMITS, () -> false).status());
        assertEquals(List.of(integer(10), integer(17), integer(29), integer(36)),
            prepared.apply(List.of(ONE, NEGATIVE_ONE, integer(2), ZERO), LIMITS, () -> false).requireValue());
        assertEquals(List.of(ZERO, ZERO, ZERO, ZERO),
            prepared.apply(List.of(ZERO, ZERO, ZERO, ZERO), LIMITS, () -> false).requireValue());
    }

    @Test void rectangularRankOneSourceUsesZeroPredictorAndCheckedThinFactors() {
        var source = matrix(new long[]{2,4,6}, new long[]{3,6,9});
        var prepared = OperatorResidualSearch.discover(source, OperatorResidualSearch.Policy.defaults(), LIMITS,
            () -> false).requireValue().operator();
        assertEquals(OperatorResidualSearch.Predictor.ZERO, prepared.predictor());
        assertEquals(1, prepared.correctionWidth());
        assertEquals(6, prepared.cost().total());
        assertEquals(10, prepared.directCost().total());
        assertEquals(List.of(integer(28), integer(42)),
            prepared.apply(List.of(ONE, integer(2), integer(3)), LIMITS, () -> false).requireValue());
    }

    @Test void directRectangularPlanSharesOnlyEqualScalingsOfTheSameInput() {
        var source = matrix(new long[]{2,-2,1}, new long[]{2,-2,0}, new long[]{-2,2,1}, new long[]{0,0,0});
        var distinct = matrix(new long[]{2,-2,1}, new long[]{3,-3,0}, new long[]{-2,2,1}, new long[]{0,0,0});
        var directOnly = new OperatorResidualSearch.Policy(List.of(), 0);
        var shared = OperatorResidualSearch.discover(source, directOnly, LIMITS, () -> false).requireValue().operator();
        var control = OperatorResidualSearch.discover(distinct, directOnly, LIMITS, () -> false).requireValue().operator();
        assertEquals(new OperatorResidualPlans.Cost(4, 5), shared.cost());
        assertEquals(new OperatorResidualPlans.Cost(6, 5), control.cost());
        var input = List.of(integer(4), ONE, integer(2));
        var applied = shared.apply(input, LIMITS, () -> false);
        assertEquals(List.of(integer(8), integer(6), integer(-4), ZERO), applied.requireValue());
        // Same sparse row shapes and small integer sizes; actual replay pays for two fewer products.
        assertTrue(applied.work() < control.apply(input, LIMITS, () -> false).work());
        var third = ONE.divide(integer(3));
        assertEquals(List.of(third.multiply(integer(-5)), third.multiply(integer(-2)), third.negate(), ZERO),
            shared.apply(List.of(third, third.multiply(integer(2)), NEGATIVE_ONE), LIMITS, () -> false).requireValue());
    }

    @Test void singleUseScalingsNeedNoExtraExecutionCells() {
        var source = FIXTURES.matrix(8, 8, (i, j) -> Polynomial.constant(Rational.of(2 + i + 8 * j)));
        var directOnly = new OperatorResidualSearch.Policy(List.of(), 0);
        var direct = OperatorResidualSearch.discover(source, directOnly, LIMITS, () -> false).requireValue().operator();
        var input = java.util.Collections.nCopies(8, ONE);
        var expected = List.of(integer(240), integer(248), integer(256), integer(264),
            integer(272), integer(280), integer(288), integer(296));
        assertEquals(expected, direct.apply(input, new IterationLimits(10_000, 24, 1_000), () -> false).requireValue());
        assertEquals("TERM_LIMIT", direct.apply(input, new IterationLimits(10_000, 23, 1_000), () -> false).detail());
    }

    @Test void diagonalAndIdentityHaveStrongDirectBaselines() {
        var diagonal = matrix(new long[]{2,0,0}, new long[]{0,1,0}, new long[]{0,0,0});
        var prepared = OperatorResidualSearch.discover(diagonal, OperatorResidualSearch.Policy.defaults(), LIMITS,
            () -> false).requireValue().operator();
        assertEquals(DIRECT, prepared.predictor());
        assertEquals(new OperatorResidualPlans.Cost(1, 0), prepared.directCost());
        assertEquals(prepared.directCost(), prepared.cost());
        assertEquals(List.of(integer(6), integer(-4), ZERO),
            prepared.apply(List.of(integer(3), integer(-4), integer(10)), LIMITS, () -> false).requireValue());
        var unit = OperatorResidualSearch.discover(FIXTURES.identity(4),
            OperatorResidualSearch.Policy.defaults(), LIMITS, () -> false).requireValue().operator();
        assertEquals(0, unit.cost().total());
        assertEquals(DIRECT, unit.predictor());
    }

    @Test void losingAndRankLimitedCandidatesKeepDirectEvaluation() {
        var source = matrix(new long[]{2,3}, new long[]{5,7});
        for (int maximumRank : List.of(0, 2)) {
            var prepared = OperatorResidualSearch.discover(source,
                new OperatorResidualSearch.Policy(List.of(IDENTITY), maximumRank), LIMITS, () -> false)
                .requireValue().operator();
            assertEquals(DIRECT, prepared.predictor());
            assertEquals(List.of(integer(8), integer(19)),
                prepared.apply(List.of(ONE, integer(2)), LIMITS, () -> false).requireValue());
        }
    }

    @Test void centeringConsumerUsesOneDiscoveredPlanAcrossDifferentSamples() {
        var eighth = new Rational(java.math.BigInteger.ONE, java.math.BigInteger.valueOf(8));
        var center = FIXTURES.matrix(8, 8, (i,j) -> Polynomial.constant(
            (i.equals(j) ? Rational.ONE : Rational.ZERO).subtract(eighth)));
        var prepared = OperatorResidualSearch.discover(center, OperatorResidualSearch.Policy.defaults(), LIMITS,
            () -> false).requireValue().operator();
        assertEquals(IDENTITY, prepared.predictor());
        assertEquals(1, prepared.correctionWidth());
        for (int sample = -4; sample <= 4; sample++) {
            var input = new ArrayList<ExactRational>();
            for (int channel = 0; channel < 8; channel++) input.add(integer(sample + 2L * channel));
            var output = prepared.apply(input, LIMITS, () -> false).requireValue();
            assertEquals(List.of(integer(-7), integer(-5), integer(-3), NEGATIVE_ONE,
                ONE, integer(3), integer(5), integer(7)), output);
        }
    }

    @Test void independentDenseCalculationChecksHeldOutRectangularAndSquareSources() {
        var random = new Random(78293);
        for (int trial = 0; trial < 24; trial++) {
            int rows = 2 + random.nextInt(4);
            int columns = 2 + random.nextInt(4);
            var source = FIXTURES.matrix(rows, columns,
                (i,j) -> Polynomial.constant(Rational.of(random.nextInt(7) - 3)));
            var input = new ArrayList<ExactRational>();
            for (int j = 0; j < columns; j++) input.add(integer(random.nextInt(11) - 5));
            var expected = new ArrayList<ExactRational>();
            for (int i = 0; i < rows; i++) {
                var sum = ExactRational.ZERO;
                for (int j = 0; j < columns; j++) {
                    var entry = source.get(i,j);
                    if (!entry.isZero()) {
                        var coefficient = entry.terms().values().iterator().next();
                        sum = sum.add(new ExactRational(coefficient.numerator(), coefficient.denominator()).multiply(input.get(j)));
                    }
                }
                expected.add(sum);
            }
            var prepared = OperatorResidualSearch.discover(source, OperatorResidualSearch.Policy.defaults(), LIMITS,
                () -> false).requireValue().operator();
            assertEquals(expected, prepared.apply(input, LIMITS, () -> false).requireValue());
            assertTrue(prepared.cost().total() <= prepared.directCost().total());
        }
    }

    @Test void externalPlansAreCheckedBeforePreparation() {
        var source = matrix(new long[]{1,2}, new long[]{3,4});
        var wrong = new OperatorResidualPlans.Plan(source, new ExactMatrixExpression.Identity(2), Optional.empty());
        assertEquals(INVALID, OperatorResidualSearch.prepare(source, wrong, LIMITS, () -> false).status());
        var alien = new OperatorResidualPlans.Plan(FIXTURES.identity(2), new ExactMatrixExpression.Identity(2), Optional.empty());
        assertEquals("SOURCE_MISMATCH", OperatorResidualSearch.prepare(source, alien, LIMITS, () -> false).detail());
    }

    @Test void budgetsAndFinalCancellationApplyToDiscoveryAndReplay() {
        var source = matrix(new long[]{2,3}, new long[]{5,7});
        var policy = OperatorResidualSearch.Policy.defaults();
        var found = OperatorResidualSearch.discover(source, policy, LIMITS, () -> false);
        assertEquals(STOPPED, OperatorResidualSearch.discover(source, policy,
            LIMITS.withMaximumWork(found.work() - 1), () -> false).status());
        assertEquals(STOPPED, OperatorResidualSearch.discover(source, policy,
            new IterationLimits(1_000_000, 1, 10_000), () -> false).status());
        var polls = new AtomicInteger();
        OperatorResidualSearch.discover(source, policy, LIMITS, () -> { polls.incrementAndGet(); return false; });
        int finalPoll = polls.get();
        polls.set(0);
        assertEquals("CANCELLED", OperatorResidualSearch.discover(source, policy, LIMITS,
            () -> polls.incrementAndGet() >= finalPoll).detail());
        var prepared = found.requireValue().operator();
        assertEquals(INVALID, prepared.apply(List.of(ONE), LIMITS, () -> false).status());
        assertEquals(STOPPED, prepared.apply(List.of(ONE, ONE), LIMITS, () -> true).status());
        assertThrows(IllegalArgumentException.class,
            () -> new OperatorResidualSearch.Policy(List.of(DIRECT), 2));
    }

    @Test void rankRejectedPredictorStillChargesItsDenseEvaluation() {
        var source = FIXTURES.identity(16);
        var policy = new OperatorResidualSearch.Policy(
            List.of(OperatorResidualSearch.Predictor.ZERO), 0);
        // The discarded predictor still materializes a second 16 x 16 table.
        var tight = new IterationLimits(1_000_000, 3636, 10_000);
        var stopped = OperatorResidualSearch.discover(source, policy, tight, () -> false);
        assertEquals(STOPPED, stopped.status());
        assertEquals("TERM_LIMIT", stopped.detail());
        var enough = new IterationLimits(1_000_000, 3636 + 16 * 16, 10_000);
        var found = OperatorResidualSearch.discover(source, policy, enough, () -> false);
        assertEquals(VERIFIED, found.status(), found.detail());
        assertEquals(DIRECT, found.requireValue().operator().predictor());
        assertEquals(16, found.requireValue().candidates().getFirst().residualRank());
        assertTrue(found.requireValue().candidates().getFirst().cost().isEmpty());
    }

    private static PolynomialMatrix matrix(long[]... rows) {
        return FIXTURES.matrix(rows.length, rows[0].length,
            (i,j) -> Polynomial.constant(Rational.of(rows[i][j])));
    }
}
