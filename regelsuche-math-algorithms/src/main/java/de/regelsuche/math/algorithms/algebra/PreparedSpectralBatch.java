package de.regelsuche.math.algorithms.algebra;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.linalg.ExactMatrixExpression;
import de.regelsuche.scalar.ExactRational;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static de.regelsuche.math.algorithms.algebra.OperatorResidualPlans.*;
import static de.regelsuche.math.algorithms.algebra.OperatorChecks.need;
import static de.regelsuche.math.algorithms.algebra.SpectralBatchMath.*;

/**
 * Immutable checked program; the repair branch transforms check rows, never all
 * source outputs.
 */
public final class PreparedSpectralBatch {
    public enum Kind {
        DIRECT, SPECTRAL_DIRECT, REPAIR
    }

    public record CostBreakdown(Cost predictors, Cost checks, Cost forwardTransforms, Cost tables,
            Cost inverseTransforms, Cost lifts, Cost combination, int predictorApplications, int forwardRows,
            int inverseRows) {
        public Cost total() {
            return predictors.plus(checks).plus(forwardTransforms).plus(tables)
                    .plus(inverseTransforms).plus(lifts).plus(combination);
        }
    }

    private final SpectralBatchRepair.Certificate certificate;
    private final Kind kind;
    private final List<PreparedOperator.Node> predictors;
    private final PreparedOperator.Node h, ht, analysis, synthesis;
    private final List<PreparedOperator.Node> tables;
    private final List<ExactRational> signs;
    private final CostBreakdown breakdown;
    private final Cost directCost, spectralDirectCost;
    private final long retainedCells;

    private PreparedSpectralBatch(SpectralBatchRepair.Certificate certificate, Kind kind,
            List<PreparedOperator.Node> predictors, PreparedOperator.Node h, PreparedOperator.Node ht,
            PreparedOperator.Node analysis, PreparedOperator.Node synthesis, List<PreparedOperator.Node> tables,
            List<ExactRational> signs, CostBreakdown breakdown, Cost directCost, Cost spectralDirectCost,
            long retainedCells) {
        this.certificate = certificate;
        this.kind = kind;
        this.predictors = List.copyOf(predictors);
        this.h = h;
        this.ht = ht;
        this.analysis = analysis;
        this.synthesis = synthesis;
        this.tables = List.copyOf(tables);
        this.signs = List.copyOf(signs);
        this.breakdown = breakdown;
        this.directCost = directCost;
        this.spectralDirectCost = spectralDirectCost;
        this.retainedCells = retainedCells;
    }

    static PreparedSpectralBatch compile(SpectralBatchRepair.Certificate certificate,
            SpectralBatchRepairVerifier.Checked checked, Kind kind, OperatorChecks c) {
        var problem = certificate.problem();
        int n = problem.source().rows(), w = problem.batchWidth(), s = certificate.syndromeWidth();
        long retained = 32L * (3L * n * n + w + (long) w * n + 1);
        for (var expression : certificate.predictors()) {
            retained = Math.addExact(retained, expressionCells(expression, c));
            c.work.cells(retained);
        }
        if (s > 0)
            retained = Math.addExact(retained, 32L * (2L * s * w + (long) n * s * s + (long) n * w + w + 1));
        reserveRetained(retained, c);
        var source = PreparedOperator.compile(new ExactMatrixExpression.Matrix("source", problem.source()), c);
        var analysis = PreparedOperator.compile(new ExactMatrixExpression.Matrix("analysis", problem.inverse()), c);
        var synthesis = PreparedOperator.compile(new ExactMatrixExpression.Matrix("synthesis", problem.basis()), c);
        var d = PreparedOperator.compile(new ExactMatrixExpression.Matrix("spectrum", diagonal(checked.target(), c)),
                c);
        var direct = scale(source.cost(), w);
        var spectralDirect = scale(analysis.cost().plus(d.cost()).plus(synthesis.cost()), w);
        c.reserve(w, 1);
        var programs = new ArrayList<PreparedOperator.Node>();
        var pc = ZERO_COST;
        for (var expression : certificate.predictors()) {
            var program = PreparedOperator.compile(expression, c);
            programs.add(program);
            pc = pc.plus(program.cost());
        }
        if (s == 0) {
            return new PreparedSpectralBatch(certificate, kind, programs, null, null, analysis, synthesis, List.of(),
                    checked.target(),
                    new CostBreakdown(pc, ZERO_COST, ZERO_COST, ZERO_COST, ZERO_COST, ZERO_COST, ZERO_COST, w, 0, 0),
                    direct, spectralDirect, retained);
        }
        var h = PreparedOperator.compile(new ExactMatrixExpression.Matrix("checks", certificate.checks().orElseThrow()),
                c);
        var ht = PreparedOperator
                .compile(new ExactMatrixExpression.Matrix("lift", transpose(certificate.checks().orElseThrow(), c)), c);
        c.reserve(n, 1);
        var tables = new ArrayList<PreparedOperator.Node>();
        var tc = ZERO_COST;
        int negatives = 0;
        for (int nu = 0; nu < n; nu++) {
            var table = PreparedOperator
                    .compile(new ExactMatrixExpression.Matrix("repair", certificate.frequencies().get(nu).repair()), c);
            tables.add(table);
            tc = tc.plus(table.cost());
            if (checked.target().get(nu).equals(ExactRational.NEGATIVE_ONE))
                negatives++;
        }
        tc = tc.plus(new Cost((long) s * negatives, (long) s * n));
        var costs = new CostBreakdown(scale(pc, 4), scale(h.cost(), 2L * n), scale(analysis.cost(), 2L * s), tc,
                scale(synthesis.cost(), 2L * s), scale(ht.cost(), 2L * n), new Cost(2L * w * n, 3L * w * n), 4 * w,
                2 * s, 2 * s);
        return new PreparedSpectralBatch(certificate, kind, programs, h, ht, analysis, synthesis, tables,
                checked.target(), costs, direct, spectralDirect, retained);
    }

    private static long expressionCells(ExactMatrixExpression expression, OperatorChecks c) {
        c.work.charge(1);
        return switch (expression) {
            case ExactMatrixExpression.Matrix m ->
                32L * (1L + m.value().rows() + (long) m.value().rows() * m.value().columns());
            case ExactMatrixExpression.Identity ignored -> 32L;
            case ExactMatrixExpression.Product p ->
                Math.addExact(32, Math.addExact(expressionCells(p.left(), c), expressionCells(p.right(), c)));
            case ExactMatrixExpression.Sum a ->
                Math.addExact(32, Math.addExact(expressionCells(a.left(), c), expressionCells(a.right(), c)));
            default -> throw new OperatorChecks.Rejected(Status.UNSUPPORTED, "UNSUPPORTED_OPERATOR_SYNTAX");
        };
    }

    public SpectralBatchRepair.Problem problem() {
        return certificate.problem();
    }

    public SpectralBatchRepair.Certificate certificate() {
        return certificate;
    }

    public Kind kind() {
        return kind;
    }

    public Cost cost() {
        return breakdown.total();
    }

    public Cost directCost() {
        return directCost;
    }

    public Cost spectralDirectCost() {
        return spectralDirectCost;
    }

    public CostBreakdown costBreakdown() {
        return breakdown;
    }

    /**
     * Conservative scalar/reference-cell bound for the retained certificate and
     * compiled program.
     */
    public long retainedCells() {
        return retainedCells;
    }

    public Result<List<List<ExactRational>>> apply(List<List<ExactRational>> rows, IterationLimits limits,
            BooleanSupplier cancel) {
        var envelope = new CertificateWork(limits, cancel);
        try {
            var result = OperatorChecks.run(envelope.remainingLimits(retainedCells), cancel, c -> execute(rows, c));
            envelope.chargeCompleted(result.work());
            envelope.charge(0);
            return new Result<>(result.status(), result.detail(), result.value(), envelope.used());
        } catch (CertificateWork.Stop stop) {
            return new Result<>(Status.STOPPED, stop.getMessage(), Optional.empty(), envelope.used());
        }
    }

    private List<List<ExactRational>> execute(List<List<ExactRational>> rows, OperatorChecks c) {
        int w = problem().batchWidth(), n = problem().source().rows(), s = certificate.syndromeWidth();
        need(rows != null && rows.size() == w, "BATCH_DIMENSION_MISMATCH");
        c.reserve(w, n);
        var input = new ExactRational[w][n];
        for (int i = 0; i < w; i++) {
            need(rows.get(i) != null && rows.get(i).size() == n, "VECTOR_DIMENSION_MISMATCH");
            for (int j = 0; j < n; j++) {
                need(rows.get(i).get(j) != null, "MISSING_VECTOR_VALUE");
                input[i][j] = c.work.check(rows.get(i).get(j));
            }
        }
        var y = applyPredictors(input, c);
        if (s == 0)
            return snapshot(y, c);
        var u = applySame(analysis, mix(h, y, c), c);
        var v = applySame(analysis, mix(h, input, c), c);
        c.reserve(s, n);
        c.reserve(s, n);
        var a = new ExactRational[s][n];
        var b = new ExactRational[s][n];
        for (int nu = 0; nu < n; nu++) {
            c.reserve(s, 1);
            var r = new ExactRational[s];
            boolean positive = signs.get(nu).equals(ExactRational.ONE);
            for (int k = 0; k < s; k++)
                r[k] = positive ? c.work.sub(u[k][nu], v[k][nu]) : c.work.add(u[k][nu], v[k][nu]);
            var column = tables.get(nu).apply(r, c);
            for (int k = 0; k < s; k++) {
                a[k][nu] = column[k];
                b[k][nu] = positive ? column[k] : c.work.mul(ExactRational.NEGATIVE_ONE, column[k]);
            }
        }
        var z = mix(ht, applySame(synthesis, a, c), c);
        var fz = mix(ht, applySame(synthesis, b, c), c); // obtained from compressed checks, NOT an F application
        var p3 = applyPredictors(applyPredictors(y, c), c);
        var pz = applyPredictors(z, c);
        c.reserve(w, n);
        var out = new ExactRational[w][n];
        for (int i = 0; i < w; i++)
            for (int j = 0; j < n; j++) {
                var hermite = c.work.sub(c.work.mul(THREE_HALVES, y[i][j]), c.work.mul(HALF, p3[i][j]));
                out[i][j] = c.work.add(c.work.sub(hermite, pz[i][j]), fz[i][j]);
            }
        return snapshot(out, c);
    }

    private ExactRational[][] applyPredictors(ExactRational[][] input, OperatorChecks c) {
        c.reserve(input.length, input[0].length);
        var out = new ExactRational[input.length][];
        for (int i = 0; i < input.length; i++)
            out[i] = predictors.get(i).apply(input[i], c);
        return out;
    }

    private static ExactRational[][] applySame(PreparedOperator.Node program, ExactRational[][] input, OperatorChecks c) {
        c.reserve(input.length, program.rows());
        var out = new ExactRational[input.length][];
        for (int i = 0; i < input.length; i++)
            out[i] = program.apply(input[i], c);
        return out;
    }

    private static ExactRational[][] mix(PreparedOperator.Node program, ExactRational[][] input, OperatorChecks c) {
        int n = input[0].length;
        c.reserve(program.rows(), n);
        var out = new ExactRational[program.rows()][n];
        for (int nu = 0; nu < n; nu++) {
            c.reserve(input.length, 1);
            var column = new ExactRational[input.length];
            for (int i = 0; i < input.length; i++)
                column[i] = input[i][nu];
            var mixed = program.apply(column, c);
            for (int i = 0; i < mixed.length; i++)
                out[i][nu] = mixed[i];
        }
        return out;
    }

    private static List<List<ExactRational>> snapshot(ExactRational[][] values, OperatorChecks c) {
        c.reserve(values.length, values[0].length);
        var rows = new ArrayList<List<ExactRational>>();
        for (var row : values)
            rows.add(List.of(row));
        return List.copyOf(rows);
    }
}
