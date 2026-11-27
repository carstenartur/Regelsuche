package de.regelsuche.math.algorithms.algebra;

import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.linalg.ExactMatrixAlgebra;
import de.regelsuche.math.algorithms.linalg.ExactMatrixExpression;
import de.regelsuche.math.algorithms.linalg.SymbolicLinearSystem.PolynomialMatrix;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static de.regelsuche.math.algorithms.algebra.OperatorResidualPlans.Status.*;
import static de.regelsuche.scalar.ExactRational.*;
import static org.junit.jupiter.api.Assertions.*;

class SpectralBatchRepairTest {
    private static final IterationLimits LIMITS = new IterationLimits(20_000_000, 900_000, 15_000);
    private static final ExactMatrixAlgebra ALG = new ExactMatrixAlgebra(new ExactMatrixAlgebra.Work(5_000_000));
    private static final PolynomialMatrix B = matrix(new long[] { 1, 1, 0 }, new long[] { 0, 1, 1 },
            new long[] { 0, 0, 1 });
    private static final PolynomialMatrix S = matrix(new long[] { 1, -1, 1 }, new long[] { 0, 1, -1 },
            new long[] { 0, 0, 1 });
    private static final PolynomialMatrix F = matrix(new long[] { 1, -2, 2 }, new long[] { 0, -1, 2 },
            new long[] { 0, 0, 1 });

    @Test
    void repairsIndependentRowsWithChangingFrequencySupportsUsingOnlyTwoCheckRows() {
        var problem = new SpectralBatchRepair.Problem(F, B, S, 3);
        var built = SpectralBatchRepair.construct(problem, predictors(), LIMITS, () -> false);
        assertEquals(VERIFIED, built.status(), built.detail());
        var certificate = built.requireValue();
        assertEquals(1, certificate.syndromeWidth());
        assertEquals(List.of(List.of(0), List.of(1), List.of(2)),
                certificate.frequencies().stream().map(SpectralBatchRepair.Frequency::support).toList());
        var prepared = SpectralBatchRepair.prepare(problem, certificate, LIMITS, () -> false).requireValue();
        assertEquals(PreparedSpectralBatch.Kind.REPAIR, prepared.kind());
        assertEquals(2, prepared.costBreakdown().forwardRows());
        assertEquals(2, prepared.costBreakdown().inverseRows());
        assertEquals(12, prepared.costBreakdown().predictorApplications());
        assertEquals(new OperatorResidualPlans.Cost(74, 88), prepared.cost());
        var rows = List.of(List.of(ONE, integer(2), integer(3)),
                List.of(integer(-2), integer(4), ONE), List.of(integer(5), ZERO, integer(-3)));
        assertEquals(List.of(List.of(integer(3), integer(4), integer(3)),
                List.of(integer(-8), integer(-2), ONE), List.of(NEGATIVE_ONE, integer(-6), integer(-3))),
                prepared.apply(rows, LIMITS, () -> false).requireValue());
        for (int active = 0; active < 9; active++) {
            var input = new ArrayList<List<ExactRational>>();
            for (int i = 0; i < 3; i++) {
                var row = new ArrayList<ExactRational>();
                for (int j = 0; j < 3; j++)
                    row.add(3 * i + j == active ? ONE : ZERO);
                input.add(row);
            }
            assertEquals(direct(F, input), prepared.apply(input, LIMITS, () -> false).requireValue());
        }
        assertEquals(VERIFIED, SpectralBatchRepairVerifier.verify(problem, certificate, LIMITS, () -> false).status());
    }

    @Test
    void exactPredictorsNeedNoSyndromeMatricesOrTransforms() {
        var problem = new SpectralBatchRepair.Problem(F, B, S, 2);
        var expression = new ExactMatrixExpression.Matrix("target", F);
        var certificate = SpectralBatchRepair.construct(problem, List.of(expression, expression), LIMITS, () -> false)
                .requireValue();
        assertEquals(0, certificate.syndromeWidth());
        assertTrue(certificate.checks().isEmpty());
        var prepared = SpectralBatchRepair.prepare(problem, certificate, LIMITS, () -> false).requireValue();
        assertEquals(0, prepared.costBreakdown().forwardRows());
        var input = List.of(List.of(ONE, ZERO, ONE), List.of(ZERO, ONE, integer(2)));
        assertEquals(direct(F, input), prepared.apply(input, LIMITS, () -> false).requireValue());
    }

    @Test
    void repairsLargerChangingSupportsWithNonconsecutiveRows() {
        var problem = new SpectralBatchRepair.Problem(F, B, S, 4);
        var ps = List.of(conjugate(2, -1, 0), conjugate(1, 3, 1), conjugate(-1, -1, 1), conjugate(1, 0, 1));
        var certificate = SpectralBatchRepair.construct(problem, ps, LIMITS, () -> false).requireValue();
        assertEquals(2, certificate.syndromeWidth());
        assertEquals(List.of(0, 2), certificate.frequencies().getFirst().support());
        assertEquals(List.of(1, 3), certificate.frequencies().get(1).support());
        var prepared = SpectralBatchRepair.prepare(problem, certificate, LIMITS, () -> false).requireValue();
        var input = List.of(List.of(ONE, integer(2), integer(3)), List.of(integer(4), integer(5), integer(6)),
                List.of(integer(7), integer(8), integer(9)), List.of(integer(10), integer(11), integer(12)));
        assertEquals(direct(F, input), prepared.apply(input, LIMITS, () -> false).requireValue());
    }

    @Test
    void independentVerifierAcceptsAlternativeRepairOnTheUnusedSyndromeSubspace() {
        var problem = new SpectralBatchRepair.Problem(F, B, S, 4);
        var ps = List.of(conjugate(2, -1, 0), conjugate(1, 3, 1), conjugate(-1, -1, 1), conjugate(1, 0, 1));
        var certificate = SpectralBatchRepair.construct(problem, ps, LIMITS, () -> false).requireValue();
        var tables = new ArrayList<>(certificate.frequencies());
        // At frequency 2 only row 0 differs. H_J=(1,1)^T, so adding
        // [[1,-1],[-1,1]] changes A but preserves H_J^T A H_J=-1.
        tables.set(2, new SpectralBatchRepair.Frequency(List.of(0), matrix(new long[] { 0, -1 }, new long[] { -1, 1 })));
        var alternative = copy(certificate, tables, certificate.checks());
        assertNotEquals(certificate.frequencies().get(2).repair(), alternative.frequencies().get(2).repair());
        assertEquals(VERIFIED, SpectralBatchRepairVerifier.verify(problem, alternative, LIMITS, () -> false).status());
        var prepared = SpectralBatchRepair.prepare(problem, alternative, LIMITS, () -> false).requireValue();
        var input = List.of(List.of(fraction(1, 2), fraction(-1, 3), fraction(2, 7)),
                List.of(fraction(3, 11), fraction(-5, 2), fraction(7, 5)),
                List.of(fraction(-2, 3), fraction(5, 7), fraction(1, 13)),
                List.of(fraction(9, 2), fraction(-5, 3), fraction(1, 2)));
        assertEquals(direct(F, input), prepared.apply(input, LIMITS, () -> false).requireValue());
    }

    @Test
    void predictorsAndEmptySyndromeClaimsRemainBoundToExactSpectra() {
        var problem = new SpectralBatchRepair.Problem(F, B, S, 3);
        var certificate = SpectralBatchRepair.construct(problem, predictors(), LIMITS, () -> false).requireValue();
        var altered = new ArrayList<>(certificate.predictors());
        altered.set(0, conjugate(4, -1, 1)); // same support, wrong Hermite diagonal
        var changed = new SpectralBatchRepair.Certificate(problem, altered, certificate.syndromeWidth(),
                certificate.nodes(), certificate.checks(), certificate.frequencies());
        var rejected = SpectralBatchRepairVerifier.verify(problem, changed, LIMITS, () -> false);
        assertEquals(INVALID, rejected.status());
        assertEquals("REPAIR_IDENTITY_MISMATCH", rejected.detail());
        var missing = new SpectralBatchRepair.Certificate(problem, predictors(), 0, List.of(), Optional.empty(), List.of());
        assertEquals(INVALID, SpectralBatchRepairVerifier.verify(problem, missing, LIMITS, () -> false).status());
        var noncommuting = new ExactMatrixExpression.Matrix("not diagonal in B",
                matrix(new long[] { 1, 0, 0 }, new long[] { 0, -1, 0 }, new long[] { 0, 0, 1 }));
        assertEquals(INVALID, SpectralBatchRepair.construct(problem, List.of(noncommuting, noncommuting, noncommuting),
                LIMITS, () -> false).status());
    }

    @Test
    void discoveryReservesItsIncumbentWhileCompilingACompetingProgram() {
        var problem = new SpectralBatchRepair.Problem(F, B, S, 3);
        var ps = new ArrayList<ExactMatrixExpression>();
        long[] spectrum = { 1, -1, 1 };
        for (int row = 0; row < 3; row++) {
            final int omitted = row;
            var diagonal = ALG.matrix(3, 3, (i, j) -> Polynomial.constant(Rational.of(
                    i.equals(j) && i != omitted ? spectrum[i] : 0)));
            ps.add(new ExactMatrixExpression.Product(new ExactMatrixExpression.Matrix("basis", B),
                    new ExactMatrixExpression.Product(new ExactMatrixExpression.Matrix("diagonal", diagonal),
                            new ExactMatrixExpression.Matrix("inverse", S))));
        }
        var certificate = SpectralBatchRepair.construct(problem, ps, LIMITS, () -> false).requireValue();
        var proposed = SpectralBatchRepair.prepare(problem, certificate, LIMITS, () -> false).requireValue();
        var onlyOneProgram = new IterationLimits(LIMITS.maximumWork(), Math.toIntExact(proposed.retainedCells()), 15_000);
        var found = SpectralBatchRepair.discover(problem,
                new SpectralBatchRepair.Policy(List.of(SpectralBatchRepair.Recipe.SPECTRAL_PARTITION), 1), 1,
                onlyOneProgram, () -> false);
        assertEquals(STOPPED, found.status(), "The direct incumbent remains live while the proposal is compiled");
        assertTrue(found.value().isEmpty());
    }

    @Test
    void retainedProgramsAndLargeExactInputsObeySpaceAndBitLimits() {
        var problem = new SpectralBatchRepair.Problem(F, B, S, 3);
        var certificate = SpectralBatchRepair.construct(problem, predictors(), LIMITS, () -> false).requireValue();
        var prepared = SpectralBatchRepair.prepare(problem, certificate, LIMITS, () -> false).requireValue();
        var input = List.of(List.of(ONE, ONE, ONE), List.of(ONE, ONE, ONE), List.of(ONE, ONE, ONE));
        var onlyRetained = new IterationLimits(LIMITS.maximumWork(), Math.toIntExact(prepared.retainedCells()), 15_000);
        assertEquals(STOPPED, prepared.apply(input, onlyRetained, () -> false).status());
        var huge = new ExactRational(BigInteger.ONE.shiftLeft(IterationLimits.MAX_RUNTIME_BITS + 1), BigInteger.ONE);
        var oversize = List.of(List.of(huge, ONE, ONE), input.get(1), input.get(2));
        var stopped = prepared.apply(oversize, LIMITS, () -> false);
        assertEquals(STOPPED, stopped.status());
        assertEquals("RUNTIME_BIT_LIMIT", stopped.detail());
        assertEquals(STOPPED, SpectralBatchRepair.deriveProblem(F, 17, LIMITS, () -> false).status());
        assertThrows(UnsupportedOperationException.class, () -> certificate.predictors().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> prepared.apply(input, LIMITS, () -> false).requireValue().getFirst().clear());
    }

    @Test
    void verifierRejectsSourceSupportBasisAndRepairMutations() {
        var problem = new SpectralBatchRepair.Problem(F, B, S, 3);
        var certificate = SpectralBatchRepair.construct(problem, predictors(), LIMITS, () -> false).requireValue();
        var changedSource = new SpectralBatchRepair.Problem(ALG.identity(3), B, S, 3);
        assertEquals(INVALID, SpectralBatchRepairVerifier.verify(changedSource, certificate, LIMITS, () -> false).status());
        assertEquals(INVALID, SpectralBatchRepairVerifier.verify(new SpectralBatchRepair.Problem(F, B, S, 2),
                certificate, LIMITS, () -> false).status());
        var tables = new ArrayList<>(certificate.frequencies());
        tables.set(0, new SpectralBatchRepair.Frequency(List.of(), tables.getFirst().repair()));
        assertEquals(INVALID, SpectralBatchRepairVerifier.verify(problem, copy(certificate, tables,
                certificate.checks()), LIMITS, () -> false).status());
        tables = new ArrayList<>(certificate.frequencies());
        tables.set(0, new SpectralBatchRepair.Frequency(List.of(0), matrix(new long[] { 0 })));
        assertEquals(INVALID, SpectralBatchRepairVerifier.verify(problem, copy(certificate, tables,
                certificate.checks()), LIMITS, () -> false).status());
        assertEquals(INVALID, SpectralBatchRepairVerifier.verify(problem, copy(certificate,
                certificate.frequencies(), Optional.of(matrix(new long[] { 1, 2, 1 }))), LIMITS, () -> false).status());
        assertEquals(INVALID, SpectralBatchRepair.construct(new SpectralBatchRepair.Problem(F, B, B, 3),
                predictors(), LIMITS, () -> false).status());
    }

    @Test
    void sourceOnlyDiscoveryDerivesBasisAndKeepsFairDirectWinner() {
        var derived = SpectralBatchRepair.deriveProblem(F, 3, LIMITS, () -> false);
        assertEquals(VERIFIED, derived.status(), derived.detail());
        var found = SpectralBatchRepair.discover(F, 3, SpectralBatchRepair.Policy.defaults(), 8, LIMITS,
                () -> false).requireValue();
        var prepared = found.operator();
        assertTrue(prepared.cost().total() <= prepared.directCost().total());
        var input = List.of(List.of(ONE, ZERO, ONE), List.of(ZERO, ONE, integer(2)), List.of(integer(3), ONE, ZERO));
        assertEquals(direct(F, input), prepared.apply(input, LIMITS, () -> false).requireValue());
        var identity = SpectralBatchRepair.discover(ALG.identity(3), 3, SpectralBatchRepair.Policy.defaults(), 8,
                LIMITS, () -> false).requireValue().operator();
        assertEquals(PreparedSpectralBatch.Kind.DIRECT, identity.kind());
        assertEquals(0, identity.cost().total());
        assertEquals(UNSUPPORTED, SpectralBatchRepair.deriveProblem(matrix(new long[] { 2 }), 2,
                LIMITS, () -> false).status());
        assertEquals(UNSUPPORTED, SpectralBatchRepair.deriveProblem(matrix(new long[] { 1, 2 }), 2,
                LIMITS, () -> false).status());
    }

    @Test
    void spectralDirectWinnerReconstructsFromItsCheckedZeroSyndromeCertificate() {
        int n = 8;
        var basis = ALG.matrix(n, n,
                (i, j) -> Polynomial.constant(Rational.of(j == i || j == i + 1 ? 1 : 0)));
        var inverse = ALG.matrix(n, n,
                (i, j) -> Polynomial.constant(Rational.of(j < i ? 0 : (j - i) % 2 == 0 ? 1 : -1)));
        var spectrum = ALG.matrix(n, n,
                (i, j) -> Polynomial.constant(Rational.of(i.equals(j) ? (i % 2 == 0 ? 1 : -1) : 0)));
        var source = ALG.multiply(basis, ALG.multiply(spectrum, inverse));
        var problem = new SpectralBatchRepair.Problem(source, basis, inverse, 2);
        var found = SpectralBatchRepair.discover(problem, new SpectralBatchRepair.Policy(List.of(), 0), 0,
                LIMITS, () -> false).requireValue().operator();
        assertEquals(PreparedSpectralBatch.Kind.SPECTRAL_DIRECT, found.kind());
        assertEquals(new OperatorResidualPlans.Cost(40, 70), found.cost());
        assertEquals(new OperatorResidualPlans.Cost(64, 56), found.directCost());
        var reconstructed = SpectralBatchRepair.prepare(problem, found.certificate(), LIMITS, () -> false).requireValue();
        assertEquals(0, reconstructed.certificate().syndromeWidth());
        assertEquals(found.cost(), reconstructed.cost());
        var input = List.of(List.of(fraction(1, 2), ONE, ZERO, integer(3), integer(-5), fraction(2, 7), ONE, NEGATIVE_ONE),
                List.of(integer(-4), fraction(5, 3), ONE, ONE, ZERO, integer(2), fraction(-1, 5), ONE));
        assertEquals(direct(source, input), reconstructed.apply(input, LIMITS, () -> false).requireValue());
    }

    @Test
    void limitsAndCancellationNeverPublishAnUncheckedWinner() {
        var problem = new SpectralBatchRepair.Problem(F, B, S, 3);
        var built = SpectralBatchRepair.construct(problem, predictors(), LIMITS, () -> false);
        assertEquals(STOPPED, SpectralBatchRepair.construct(problem, predictors(),
                LIMITS.withMaximumWork(built.work() - 1), () -> false).status());
        assertEquals(STOPPED, SpectralBatchRepair.construct(problem, predictors(),
                new IterationLimits(1_000_000, 1, 15_000), () -> false).status());
        assertEquals(STOPPED, SpectralBatchRepair.construct(problem, predictors(), LIMITS, () -> true).status());
        assertEquals(STOPPED, SpectralBatchRepair.discover(F, 3, SpectralBatchRepair.Policy.defaults(), 0,
                LIMITS, () -> false).status());
        var prepared = SpectralBatchRepair.prepare(problem, built.requireValue(), LIMITS, () -> false).requireValue();
        assertEquals(INVALID, prepared.apply(List.of(List.of(ONE)), LIMITS, () -> false).status());
        var input = List.of(List.of(ONE, ONE, ONE), List.of(ONE, ONE, ONE), List.of(ONE, ONE, ONE));
        assertEquals(STOPPED, prepared.apply(input, LIMITS, () -> true).status());
        var polls = new AtomicInteger();
        prepared.apply(input, LIMITS, () -> {
            polls.incrementAndGet();
            return false;
        });
        int last = polls.get();
        polls.set(0);
        assertEquals(STOPPED, prepared.apply(input, LIMITS, () -> polls.incrementAndGet() >= last).status());
    }

    private static ExactRational fraction(long numerator, long denominator) {
        return new ExactRational(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
    }

    private static SpectralBatchRepair.Certificate copy(SpectralBatchRepair.Certificate c,
            List<SpectralBatchRepair.Frequency> frequencies, Optional<PolynomialMatrix> checks) {
        return new SpectralBatchRepair.Certificate(c.problem(), c.predictors(), c.syndromeWidth(), c.nodes(), checks,
                frequencies);
    }

    private static List<ExactMatrixExpression> predictors() {
        return List.of(conjugate(2, -1, 1), conjugate(1, 3, 1), conjugate(1, -1, 0));
    }

    private static ExactMatrixExpression conjugate(long... diagonal) {
        var d = ALG.matrix(diagonal.length, diagonal.length,
                (i, j) -> Polynomial.constant(Rational.of(i.equals(j) ? diagonal[i] : 0)));
        return new ExactMatrixExpression.Matrix("predictor", ALG.multiply(B, ALG.multiply(d, S)));
    }

    private static PolynomialMatrix matrix(long[]... rows) {
        return ALG.matrix(rows.length, rows[0].length, (i, j) -> Polynomial.constant(Rational.of(rows[i][j])));
    }

    private static List<List<ExactRational>> direct(PolynomialMatrix source, List<List<ExactRational>> rows) {
        var result = new ArrayList<List<ExactRational>>();
        for (var input : rows) {
            var out = new ArrayList<ExactRational>();
            for (int i = 0; i < source.rows(); i++) {
                var sum = ZERO;
                for (int j = 0; j < source.columns(); j++)
                    if (!source.get(i, j).isZero()) {
                        var c = source.get(i, j).terms().values().iterator().next();
                        sum = sum.add(new ExactRational(c.numerator(), c.denominator()).multiply(input.get(j)));
                    }
                out.add(sum);
            }
            result.add(List.copyOf(out));
        }
        return List.copyOf(result);
    }
}
