package de.regelsuche.math.algorithms.algebra;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
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
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static de.regelsuche.math.algorithms.algebra.OperatorResidualPlans.*;

/** Shared parent account. Child work is posted before observing cancellation on return. */
final class ComposedBilinearChecks {
    final CertificateWork work;
    final BooleanSupplier cancelled;
    long retained;

    private ComposedBilinearChecks(IterationLimits limits, BooleanSupplier cancelled) {
        this.work = new CertificateWork(limits, cancelled);
        this.cancelled = cancelled;
    }

    static <T> Result<T> run(IterationLimits limits, BooleanSupplier cancelled,
                             Function<ComposedBilinearChecks, T> operation) {
        var checks = new ComposedBilinearChecks(limits, cancelled);
        try {
            checks.work.charge(0);
            T result = operation.apply(checks);
            checks.work.charge(0);
            return new Result<>(Status.VERIFIED, "", Optional.of(result), checks.work.used());
        } catch (CertificateWork.Stop stop) {
            return new Result<>(Status.STOPPED, stop.getMessage(), Optional.empty(), checks.work.used());
        } catch (BilinearProgram.Rejection invalid) {
            return new Result<>(Status.INVALID, invalid.getMessage(), Optional.empty(), checks.work.used());
        } catch (Failure failure) {
            return new Result<>(failure.status, failure.getMessage(), Optional.empty(), checks.work.used());
        }
    }

    static final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final Status status;
        Failure(Status status, String reason) { super(reason, null, false, false); this.status = status; }
    }

    static void need(boolean condition, String reason) {
        if (!condition) throw new Failure(Status.INVALID, reason);
    }

    void retain(long cells) {
        if (cells < 0 || cells > Long.MAX_VALUE - retained) throw new CertificateWork.Stop("TERM_LIMIT");
        long next = retained + cells;
        work.cells(next);
        retained = next;
    }

    <T> Result<T> child(Function<IterationLimits, Result<T>> operation) {
        var allowance = work.remainingLimits(retained);
        var result = operation.apply(allowance);
        if (result == null || result.work() < 0 || result.work() > allowance.maximumWork())
            throw new IllegalStateException("INVALID_CHILD_WORK");
        work.chargeCompleted(result.work());
        work.charge(0);
        if ((result.status() == Status.VERIFIED) != result.value().isPresent())
            throw new IllegalStateException("INVALID_CHILD_RESULT");
        return result;
    }

    <T> T require(Result<T> result) {
        if (result.status() != Status.VERIFIED) throw new Failure(result.status(), result.detail());
        return result.requireValue();
    }

    long requestCells(BilinearProgram.Request request) {
        return BilinearProgram.matrixCells(request.tensor().outputs())
            + BilinearProgram.matrixCells(request.leftForms()) + BilinearProgram.matrixCells(request.rightForms()) + 4;
    }

    long programCells(BilinearProgram program) {
        return BilinearProgram.matrixCells(program.u()) + BilinearProgram.matrixCells(program.v())
            + BilinearProgram.matrixCells(program.w()) + 4;
    }

    long matrixCells(PolynomialMatrix matrix) {
        work.charge(1);
        return (long) matrix.rows() * (matrix.columns() + 1L) + 1;
    }

    /** A conservative scalar/reference bound, charging the complete source tree before compilation. */
    long expressionCells(ExactMatrixExpression expression, int depth) {
        work.charge(1);
        if (depth > ExactMatrixAlgebra.MAX_DEPTH) throw new CertificateWork.Stop("EXPRESSION_DEPTH_LIMIT");
        long result = switch (expression) {
            case ExactMatrixExpression.Matrix matrix -> 2 + matrixCells(matrix.value());
            case ExactMatrixExpression.Identity ignored -> 2;
            case ExactMatrixExpression.Product product -> 2 + expressionCells(product.left(), depth + 1)
                + expressionCells(product.right(), depth + 1);
            case ExactMatrixExpression.Sum sum -> 2 + expressionCells(sum.left(), depth + 1)
                + expressionCells(sum.right(), depth + 1);
            default -> throw new Failure(Status.UNSUPPORTED, "UNSUPPORTED_OPERATOR_SYNTAX");
        };
        work.cells(retained + result);
        return result;
    }

    long linearCells(OperatorResidualPlans.Plan plan) {
        long cells = 4 + matrixCells(plan.source()) + expressionCells(plan.predictor(), 0);
        if (plan.correction().isPresent()) {
            var factors = plan.correction().orElseThrow();
            cells += 4 + matrixCells(factors.lift()) + matrixCells(factors.coordinates());
        }
        work.cells(retained + cells);
        return cells;
    }

    long problemCells(SpectralBatchRepair.Problem problem) {
        return 4 + matrixCells(problem.source()) + matrixCells(problem.basis()) + matrixCells(problem.inverse());
    }

    long spectralCells(ComposedBilinearPlan.Spectral stage) {
        var certificate = stage.certificate();
        long cells = 8 + problemCells(stage.problem()) + problemCells(certificate.problem());
        cells += certificate.predictors().size() + (long) certificate.nodes().size() + certificate.frequencies().size();
        work.cells(retained + cells);
        for (var predictor : certificate.predictors()) {
            cells += expressionCells(predictor, 0);
            work.cells(retained + cells);
        }
        if (certificate.checks().isPresent()) cells += matrixCells(certificate.checks().orElseThrow());
        for (var frequency : certificate.frequencies()) {
            work.charge(1);
            cells += 2L + frequency.support().size() + matrixCells(frequency.repair());
            work.cells(retained + cells);
        }
        return cells;
    }

    long stageCells(ComposedBilinearPlan.Stage stage) {
        return switch (stage) {
            case ComposedBilinearPlan.Linear linear -> linearCells(linear.plan());
            case ComposedBilinearPlan.Spectral spectral -> spectralCells(spectral);
        };
    }

    PolynomialMatrix matrix(List<List<ExactRational>> source) {
        need(!source.isEmpty() && !source.getFirst().isEmpty(), "EMPTY_OPERATOR_STAGE");
        int rows = source.size();
        int columns = source.getFirst().size();
        if (rows > ExactMatrixAlgebra.MAX_DIMENSION || columns > ExactMatrixAlgebra.MAX_DIMENSION)
            throw new CertificateWork.Stop("MATRIX_DIMENSION_LIMIT");
        retain(2L * rows * (columns + 1L) + 2);
        var entries = new ArrayList<List<Polynomial>>(rows);
        for (var row : source) {
            need(row.size() == columns, "PROGRAM_SHAPE");
            work.charge(1);
            var values = new ArrayList<Polynomial>(columns);
            for (var value : row) values.add(Polynomial.constant(Rational.fromExact(work.check(value))));
            entries.add(List.copyOf(values));
        }
        return new PolynomialMatrix(entries);
    }

    static OperatorResidualPlans.Cost scale(OperatorResidualPlans.Cost cost, int batchWidth) {
        return new OperatorResidualPlans.Cost(Math.multiplyExact(cost.multiplies(), batchWidth),
            Math.multiplyExact(cost.additions(), batchWidth));
    }

}
