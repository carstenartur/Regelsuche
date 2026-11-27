package de.regelsuche.math.algorithms.algebra;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.linalg.SymbolicLinearSystem.PolynomialMatrix;
import de.regelsuche.scalar.ExactRational;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static de.regelsuche.math.algorithms.algebra.OperatorResidualPlans.*;
import static de.regelsuche.math.algorithms.algebra.OperatorChecks.need;
import static de.regelsuche.math.algorithms.algebra.SpectralBatchMath.*;

/**
 * Independently checks spectral supports and finite syndrome identities; never
 * constructs a table.
 */
public final class SpectralBatchRepairVerifier {
    private SpectralBatchRepairVerifier() {}

    public static Result<Cost> verify(SpectralBatchRepair.Problem expected, SpectralBatchRepair.Certificate certificate,
            IterationLimits limits, BooleanSupplier cancel) {
        return OperatorChecks.run(limits, cancel, c -> {
            var checked = check(expected, certificate, c);
            return PreparedSpectralBatch.compile(certificate, checked, PreparedSpectralBatch.Kind.REPAIR, c).cost();
        });
    }

    record Checked(List<ExactRational> target) {}

    static Checked check(SpectralBatchRepair.Problem expected, SpectralBatchRepair.Certificate certificate,
            OperatorChecks c) {
        checkSourceBinding(expected, certificate, c);
        var z = target(expected, c);
        int n = expected.source().rows(), width = expected.batchWidth(), s = certificate.syndromeWidth();
        var p = predictorSpectra(expected, certificate, n, width, s, c);
        if (s == 0) {
            checkExactPredictions(certificate, p, z, c);
            return new Checked(z);
        }
        var h = checkSyndromeMatrix(certificate, width, s, c);
        need(certificate.frequencies().size() == n, "FREQUENCY_COUNT_MISMATCH");
        for (int nu = 0; nu < n; nu++)
            checkFrequencyRepair(certificate.frequencies().get(nu), p, z.get(nu), h, nu, c);
        return new Checked(z);
    }

    private static void checkSourceBinding(SpectralBatchRepair.Problem expected,
            SpectralBatchRepair.Certificate certificate, OperatorChecks c) {
        need(expected != null && certificate != null, "MISSING_SPECTRAL_EVIDENCE");
        c.input(expected.source());
        c.input(expected.basis());
        c.input(expected.inverse());
        c.input(certificate.problem().source());
        c.input(certificate.problem().basis());
        c.input(certificate.problem().inverse());
        need(expected.equals(certificate.problem()), "SOURCE_MISMATCH");
    }

    private static List<List<ExactRational>> predictorSpectra(SpectralBatchRepair.Problem expected,
            SpectralBatchRepair.Certificate certificate, int n, int width, int s, OperatorChecks c) {
        need(certificate.predictors().size() == width, "PREDICTOR_COUNT_MISMATCH");
        need(s >= 0 && s <= width, "SYNDROME_DIMENSION_MISMATCH");
        c.reserve(width, n);
        var p = new ArrayList<List<ExactRational>>();
        for (var expression : certificate.predictors())
            p.add(prediction(expected, expression, c));
        return p;
    }

    private static void checkExactPredictions(SpectralBatchRepair.Certificate certificate,
            List<List<ExactRational>> p, List<ExactRational> z, OperatorChecks c) {
        need(certificate.nodes().isEmpty() && certificate.checks().isEmpty() && certificate.frequencies().isEmpty(),
                "UNEXPECTED_SYNDROME_DATA");
        for (var row : p)
            for (int nu = 0; nu < z.size(); nu++) {
                c.work.charge(1);
                need(row.get(nu).equals(z.get(nu)), "MISSING_EXCEPTION");
            }
    }

    private static PolynomialMatrix checkSyndromeMatrix(SpectralBatchRepair.Certificate certificate,
            int width, int s, OperatorChecks c) {
        need(certificate.nodes().size() == width && certificate.checks().isPresent(), "MISSING_CHECK_MATRIX");
        var h = certificate.checks().orElseThrow();
        c.input(h);
        need(h.rows() == s && h.columns() == width, "CHECK_DIMENSION_MISMATCH");
        for (int i = 0; i < width; i++) {
            var node = c.work.check(certificate.nodes().get(i));
            for (int j = 0; j < i; j++) {
                c.work.charge(1);
                need(!node.equals(certificate.nodes().get(j)), "DUPLICATE_CHECK_NODE");
            }
            var power = ExactRational.ONE;
            for (int k = 0; k < s; k++) {
                need(value(h.get(k, i), c).equals(power), "CHECK_MATRIX_MISMATCH");
                power = c.work.mul(power, node);
            }
        }
        return h;
    }

    private static void checkFrequencyRepair(SpectralBatchRepair.Frequency table,
            List<List<ExactRational>> p, ExactRational z, PolynomialMatrix h, int nu, OperatorChecks c) {
        int width = p.size(), s = h.rows();
        c.reserve(width, 1);
        var support = new ArrayList<Integer>();
        for (int i = 0; i < width; i++) {
            c.work.charge(1);
            if (!p.get(i).get(nu).equals(z))
                support.add(i);
        }
        need(support.equals(table.support()), "EXCEPTION_SUPPORT_MISMATCH");
        need(support.size() <= s, "EXCEPTION_WIDTH_MISMATCH");
        c.input(table.repair());
        need(table.repair().rows() == s && table.repair().columns() == s, "REPAIR_DIMENSION_MISMATCH");
        checkRepairIdentity(support, table.repair(), p, z, h, nu, c);
    }

    /** Check H_J^T A H_J=D directly, independent of the producer's Vandermonde inverse. */
    private static void checkRepairIdentity(List<Integer> support, PolynomialMatrix repair,
            List<List<ExactRational>> p, ExactRational z, PolynomialMatrix h, int nu, OperatorChecks c) {
        for (int a = 0; a < support.size(); a++)
            for (int b = 0; b < support.size(); b++) {
                var sum = decoderEntry(h, repair, support.get(a), support.get(b), c);
                var want = a == b ? c.work.mul(ExactRational.NEGATIVE_ONE, c.work.div(
                        c.work.add(p.get(support.get(a)).get(nu), c.work.mul(TWO, z)), TWO))
                        : ExactRational.ZERO;
                need(sum.equals(want), "REPAIR_IDENTITY_MISMATCH");
            }
    }

    private static ExactRational decoderEntry(PolynomialMatrix h, PolynomialMatrix repair,
            int row, int column, OperatorChecks c) {
        var sum = ExactRational.ZERO;
        for (int k = 0; k < h.rows(); k++)
            for (int l = 0; l < h.rows(); l++)
                sum = c.work.add(sum, c.work.mul(value(h.get(k, row), c),
                        c.work.mul(value(repair.get(k, l), c), value(h.get(l, column), c))));
        return sum;
    }
}
