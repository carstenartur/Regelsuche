package de.regelsuche.math.algorithms.algebra;

import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactMatrixAlgebra;
import de.regelsuche.math.algorithms.linalg.ExactMatrixExpression;
import de.regelsuche.math.algorithms.linalg.SymbolicLinearSystem.PolynomialMatrix;
import de.regelsuche.scalar.ExactRational;
import java.util.ArrayList;
import java.util.List;

import static de.regelsuche.math.algorithms.algebra.OperatorChecks.need;
import static de.regelsuche.math.algorithms.algebra.OperatorResidualPlans.*;

/** Bounded arithmetic shared by construction, verification and execution. */
final class SpectralBatchMath {
    private SpectralBatchMath() {}

    static final ExactRational TWO = ExactRational.integer(2);
    static final ExactRational HALF = new ExactRational(java.math.BigInteger.ONE, java.math.BigInteger.TWO);
    static final ExactRational THREE_HALVES = new ExactRational(java.math.BigInteger.valueOf(3),
            java.math.BigInteger.TWO);
    static final Cost ZERO_COST = new Cost(0, 0);

    static void batch(int width, OperatorChecks c) {
        need(width > 0, "INVALID_BATCH_WIDTH");
        if (width > ExactMatrixAlgebra.MAX_DIMENSION)
            throw new OperatorChecks.Rejected(Status.STOPPED, "BATCH_WIDTH_LIMIT");
    }

    static List<ExactRational> target(SpectralBatchRepair.Problem p, OperatorChecks c) {
        need(p != null, "MISSING_PROBLEM");
        batch(p.batchWidth(), c);
        c.input(p.source());
        c.input(p.basis());
        c.input(p.inverse());
        int n = p.source().rows();
        if (n != p.source().columns())
            throw new OperatorChecks.Rejected(Status.UNSUPPORTED, "SOURCE_NOT_SQUARE");
        need(p.basis().rows() == n && p.basis().columns() == n && p.inverse().rows() == n
                && p.inverse().columns() == n, "BASIS_DIMENSION_MISMATCH");
        var unit = c.unit(n);
        need(c.algebra.equal(c.times(p.basis(), p.inverse()), unit)
                && c.algebra.equal(c.times(p.inverse(), p.basis()), unit), "BASIS_INVERSE_MISMATCH");
        var values = diagonal(c.times(p.inverse(), c.times(p.source(), p.basis())), c);
        for (var z : values) {
            c.work.charge(1);
            if (!z.equals(ExactRational.ONE) && !z.equals(ExactRational.NEGATIVE_ONE))
                throw new OperatorChecks.Rejected(Status.UNSUPPORTED, "SOURCE_NOT_INVOLUTION");
        }
        return values;
    }

    static List<ExactRational> prediction(SpectralBatchRepair.Problem p, ExactMatrixExpression expression,
            OperatorChecks c) {
        var shape = c.inspect(expression, 0);
        need(shape.rows() == p.source().rows() && shape.columns() == p.source().columns(),
                "PREDICTOR_DIMENSION_MISMATCH");
        c.reserve(shape.rows(), shape.columns());
        var value = c.algebra.evaluate(expression);
        c.input(value);
        return diagonal(c.times(p.inverse(), c.times(value, p.basis())), c);
    }

    static List<ExactRational> diagonal(PolynomialMatrix m, OperatorChecks c) {
        c.reserve(m.rows(), 1);
        var result = new ArrayList<ExactRational>();
        for (int i = 0; i < m.rows(); i++)
            for (int j = 0; j < m.columns(); j++) {
                c.work.charge(1);
                need(i == j || m.get(i, j).isZero(), "NOT_COMMON_EIGENBASIS");
                if (i == j)
                    result.add(value(m.get(i, j), c));
            }
        return List.copyOf(result);
    }

    static ExactRational value(Polynomial p, OperatorChecks c) {
        c.work.charge(1);
        if (p.isZero())
            return ExactRational.ZERO;
        var r = p.terms().values().iterator().next();
        return c.work.check(new ExactRational(r.numerator(), r.denominator()));
    }

    static Polynomial scalar(ExactRational value) {
        return Polynomial.constant(Rational.fromExact(value));
    }

    static PolynomialMatrix transpose(PolynomialMatrix m, OperatorChecks c) {
        return c.matrix(m.columns(), m.rows(), (i, j) -> m.get(j, i));
    }

    static PolynomialMatrix diagonal(List<ExactRational> d, OperatorChecks c) {
        return c.matrix(d.size(), d.size(), (i, j) -> i.equals(j) ? scalar(d.get(i)) : Polynomial.zero());
    }

    static Cost scale(Cost cost, long factor) {
        return new Cost(Math.multiplyExact(cost.multiplies(), factor), Math.multiplyExact(cost.additions(), factor));
    }

    /**
     * Add a conservative retained-program bound to the same cumulative ledger as
     * temporary matrices, before allocating the compiled nodes. This also retains
     * every previous incumbent while discovery prepares a competing program.
     */
    static void reserveRetained(long cells, OperatorChecks c) {
        c.work.cells(cells);
        int side = ExactMatrixAlgebra.MAX_DIMENSION;
        int block = side * side;
        while (cells >= block) {
            c.reserve(side, side);
            cells -= block;
        }
        int rows = (int) cells / side;
        if (rows > 0)
            c.reserve(rows, side);
        int tail = (int) cells % side;
        if (tail > 0)
            c.reserve(1, tail);
    }

    static PolynomialMatrix inverse(PolynomialMatrix input, OperatorChecks c) {
        int n = input.rows();
        need(n == input.columns(), "INVERSE_DIMENSION_MISMATCH");
        c.reserve(n, n);
        c.reserve(n, n);
        var a = new ExactRational[n][n];
        var b = new ExactRational[n][n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++) {
                a[i][j] = value(input.get(i, j), c);
                b[i][j] = i == j ? ExactRational.ONE : ExactRational.ZERO;
            }
        for (int k = 0; k < n; k++) {
            int pivot = k;
            while (pivot < n) {
                c.work.charge(1);
                if (!a[pivot][k].isZero())
                    break;
                pivot++;
            }
            need(pivot < n, "SINGULAR_MATRIX");
            var swap = a[k];
            a[k] = a[pivot];
            a[pivot] = swap;
            swap = b[k];
            b[k] = b[pivot];
            b[pivot] = swap;
            var d = a[k][k];
            for (int j = 0; j < n; j++) {
                a[k][j] = c.work.div(a[k][j], d);
                b[k][j] = c.work.div(b[k][j], d);
            }
            for (int i = 0; i < n; i++) {
                c.work.charge(1);
                if (i == k || a[i][k].isZero())
                    continue;
                var factor = a[i][k];
                for (int j = 0; j < n; j++) {
                    a[i][j] = c.work.sub(a[i][j], c.work.mul(factor, a[k][j]));
                    b[i][j] = c.work.sub(b[i][j], c.work.mul(factor, b[k][j]));
                }
            }
        }
        return c.matrix(n, n, (i, j) -> scalar(b[i][j]));
    }

    /** Independent columns of F+I and F-I span the rational eigenspaces. */
    static SpectralBatchRepair.Problem derive(PolynomialMatrix source, int width, OperatorChecks c) {
        batch(width, c);
        c.input(source);
        int n = source.rows();
        if (n != source.columns())
            throw new OperatorChecks.Rejected(Status.UNSUPPORTED, "SOURCE_NOT_SQUARE");
        var unit = c.unit(n);
        if (!c.algebra.equal(c.times(source, source), unit))
            throw new OperatorChecks.Rejected(Status.UNSUPPORTED, "SOURCE_NOT_INVOLUTION");
        c.reserve(n, n);
        c.reserve(n, n);
        c.reserve(n, 1);
        var originals = new ArrayList<ExactRational[]>();
        var reduced = new ArrayList<ExactRational[]>();
        var pivots = new ArrayList<Integer>();
        for (int sign : new int[] { 1, -1 })
            for (int column = 0; column < n && originals.size() < n; column++) {
                c.reserve(n, 1);
                c.reserve(n, 1);
                var original = new ExactRational[n];
                var v = new ExactRational[n];
                for (int i = 0; i < n; i++) {
                    original[i] = c.work.add(value(source.get(i, column), c),
                            ExactRational.integer(i == column ? sign : 0));
                    v[i] = original[i];
                }
                for (int k = 0; k < reduced.size(); k++) {
                    var factor = v[pivots.get(k)];
                    for (int i = 0; i < n; i++)
                        v[i] = c.work.sub(v[i], c.work.mul(factor, reduced.get(k)[i]));
                }
                int pivot = 0;
                while (pivot < n) {
                    c.work.charge(1);
                    if (!v[pivot].isZero())
                        break;
                    pivot++;
                }
                if (pivot == n)
                    continue;
                var divisor = v[pivot];
                for (int i = 0; i < n; i++)
                    v[i] = c.work.div(v[i], divisor);
                originals.add(original);
                reduced.add(v);
                pivots.add(pivot);
            }
        need(originals.size() == n, "INCOMPLETE_INVOLUTION_BASIS");
        var basis = c.matrix(n, n, (i, j) -> scalar(originals.get(j)[i]));
        var result = new SpectralBatchRepair.Problem(source, basis, inverse(basis, c), width);
        target(result, c);
        return result;
    }
}
