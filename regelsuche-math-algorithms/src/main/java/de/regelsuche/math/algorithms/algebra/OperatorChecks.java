package de.regelsuche.math.algorithms.algebra;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.linalg.ExactMatrixAlgebra;
import de.regelsuche.math.algorithms.linalg.ExactMatrixExpression;
import de.regelsuche.math.algorithms.linalg.SymbolicLinearSystem.PolynomialMatrix;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static de.regelsuche.math.algorithms.algebra.OperatorResidualPlans.*;

/** Shared accounting and domain guards for the two finite operator checks. */
final class OperatorChecks {
    final CertificateWork work;
    final ExactMatrixAlgebra algebra;
    private long materialized;
    static final Polynomial MINUS_ONE = Polynomial.constant(Rational.NEGATIVE_ONE);

    private OperatorChecks(IterationLimits limits, BooleanSupplier cancel) {
        work = new CertificateWork(limits, cancel);
        algebra = new ExactMatrixAlgebra(new ExactMatrixAlgebra.Work(Integer.MAX_VALUE, work::charge));
    }

    static <T> Result<T> run(IterationLimits limits, BooleanSupplier cancel, Function<OperatorChecks, T> action) {
        var checks = new OperatorChecks(limits, cancel);
        try {
            checks.work.charge(1);
            T result = action.apply(checks);
            checks.work.charge(0);
            return new Result<>(Status.VERIFIED, "", Optional.of(result), checks.work.used());
        } catch (CertificateWork.Stop stop) {
            return new Result<>(Status.STOPPED, stop.getMessage(), Optional.empty(), checks.work.used());
        } catch (ExactMatrixAlgebra.Exhausted stop) {
            return new Result<>(Status.STOPPED, "MATRIX_WORK_LIMIT", Optional.empty(), checks.work.used());
        } catch (ExactMatrixAlgebra.Unsupported stop) {
            Status status = stop.getMessage().endsWith("_LIMIT") ? Status.STOPPED : Status.INVALID;
            return new Result<>(status, stop.getMessage(), Optional.empty(), checks.work.used());
        } catch (Rejected rejected) {
            return new Result<>(rejected.status, rejected.getMessage(), Optional.empty(), checks.work.used());
        }
    }

    static final class Rejected extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final Status status;
        Rejected(Status status, String reason) {
            super(reason, null, false, false);
            this.status = status;
        }
    }

    static void need(boolean condition, String reason) {
        if (!condition) throw new Rejected(Status.INVALID, reason);
    }

    private static void dimensions(int rows, int cols) {
        if (rows > ExactMatrixAlgebra.MAX_DIMENSION || cols > ExactMatrixAlgebra.MAX_DIMENSION)
            throw new Rejected(Status.STOPPED, "MATRIX_DIMENSION_LIMIT");
    }

    void reserve(int rows, int cols) {
        dimensions(rows, cols);
        materialized += (long) rows * cols;
        work.cells(materialized);
    }

    void input(PolynomialMatrix matrix) {
        need(matrix != null, "MISSING_MATRIX");
        dimensions(matrix.rows(), matrix.columns());
        work.cells((long) matrix.rows() * matrix.columns());
        for (var row : matrix.entries()) for (var entry : row) {
            work.charge(1);
            if (entry.isZero()) continue;
            if (entry.termCount() != 1)
                throw new Rejected(Status.UNSUPPORTED, "NON_RATIONAL_MATRIX");
            var term = entry.terms().entrySet().iterator().next();
            if (!term.getKey().powers().isEmpty())
                throw new Rejected(Status.UNSUPPORTED, "NON_RATIONAL_MATRIX");
            var value = term.getValue();
            int bits = Math.max(value.numerator().bitLength(), value.denominator().bitLength());
            if (bits > ExactMatrixAlgebra.MAX_COEFFICIENT_BITS)
                throw new Rejected(Status.STOPPED, "COEFFICIENT_SIZE_LIMIT");
            work.charge(1L + bits / Long.SIZE);
        }
    }

    PolynomialMatrix matrix(int rows, int cols, BiFunction<Integer, Integer, Polynomial> entries) {
        reserve(rows, cols);
        return algebra.matrix(rows, cols, entries);
    }

    PolynomialMatrix unit(int n) {
        reserve(n, n);
        return algebra.identity(n);
    }

    PolynomialMatrix times(PolynomialMatrix left, PolynomialMatrix right) {
        reserve(left.rows(), right.columns());
        return algebra.multiply(left, right);
    }

    PolynomialMatrix plus(PolynomialMatrix left, PolynomialMatrix right) {
        reserve(left.rows(), left.columns());
        return algebra.add(left, right);
    }

    record Shape(int rows, int columns, Cost cost) {}

    /** Inspect before expansion. Charge each staged matvec occurrence, including shared subexpressions. */
    Shape inspect(ExactMatrixExpression expression, int depth) {
        work.charge(1);
        need(expression != null, "MISSING_OPERATOR");
        if (depth > ExactMatrixAlgebra.MAX_DEPTH) throw new Rejected(Status.STOPPED, "EXPRESSION_DEPTH_LIMIT");
        Shape result = switch (expression) {
            case ExactMatrixExpression.Identity i -> new Shape(i.dimension(), i.dimension(), new Cost(0, 0));
            case ExactMatrixExpression.Matrix m -> {
                input(m.value());
                yield new Shape(m.value().rows(), m.value().columns(), Cost.dense(m.value()));
            }
            case ExactMatrixExpression.Product p -> {
                var left = inspect(p.left(), depth + 1);
                var right = inspect(p.right(), depth + 1);
                need(left.columns == right.rows, "PRODUCT_DIMENSION_MISMATCH");
                yield new Shape(left.rows, right.columns, left.cost.plus(right.cost));
            }
            case ExactMatrixExpression.Sum s -> {
                var left = inspect(s.left(), depth + 1);
                var right = inspect(s.right(), depth + 1);
                need(left.rows == right.rows && left.columns == right.columns, "SUM_DIMENSION_MISMATCH");
                yield new Shape(left.rows, left.columns, left.cost.plus(right.cost).updates(left.rows));
            }
            default -> throw new Rejected(Status.UNSUPPORTED, "UNSUPPORTED_OPERATOR_SYNTAX");
        };
        reserve(result.rows, result.columns);
        return result;
    }
}
