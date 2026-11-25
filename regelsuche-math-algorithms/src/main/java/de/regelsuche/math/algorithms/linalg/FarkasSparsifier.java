package de.regelsuche.math.algorithms.linalg;

import static de.regelsuche.scalar.ExactRational.NEGATIVE_ONE;
import static de.regelsuche.scalar.ExactRational.ONE;
import static de.regelsuche.scalar.ExactRational.ZERO;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.linalg.LinearProgramCertificates.Infeasible;
import de.regelsuche.math.algorithms.linalg.LinearProgramCertificates.Problem;
import de.regelsuche.math.algorithms.linalg.LinearProgramVerifier.Status;
import de.regelsuche.scalar.ExactRational;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** Reduces an already supplied Farkas multiplier by exact dependence elimination. */
public final class FarkasSparsifier {
    private FarkasSparsifier() {}

    public record Result(Status status, String reason, long work, Optional<Infeasible> certificate) {}

    /**
     * Returns y with b^T y = -1 supported on at most rank(A) + 1 of the originally selected rows.
     * Each cancellation preserves the augmented sum and strictly reduces the support.
     */
    public static Result sparsify(Problem problem, Infeasible certificate,
            IterationLimits limits, BooleanSupplier cancellation) {
        var work = new CertificateWork(limits, cancellation);
        try {
            var initial = LinearProgramVerifier.verify(problem, certificate, false, 0, work);
            if (!initial.valid()) return empty(initial.status(), initial.reason(), work);

            long inputCells = LinearProgramVerifier.storageCells(problem, certificate, work);
            int rows = problem.constraints();
            int prefix = Math.min(rows, problem.variables() + 2);
            long workingCells = inputCells + rows + prefix;
            work.cells(workingCells);
            work.charge(rows + (long) prefix);
            var weights = new ExactRational[rows];
            var active = new int[prefix];
            var factor = work.div(NEGATIVE_ONE, LinearProgramVerifier.dot(problem.b(), certificate.y(), work));
            for (int row = 0; row < rows; row++) weights[row] = work.mul(factor, certificate.y().get(row));

            while (true) {
                int count = activePrefix(weights, active, work);
                var direction = dependency(problem, active, count, workingCells, work);
                if (direction == null) break;
                cancel(weights, active, direction, work);
            }

            // Reserve the snapshot and the transient reference array used to build it.
            work.cells(workingCells + 2L * rows);
            work.charge(2L * rows);
            var sparse = new Infeasible(problem, Arrays.asList(weights));
            var verified = LinearProgramVerifier.verify(problem, sparse, true,
                workingCells - problem.storageCells(), work);
            if (!verified.valid()) return empty(Status.UNAVAILABLE, "OUTPUT_" + verified.reason(), work);
            work.charge(0);
            return new Result(Status.VERIFIED, "VERIFIED", work.used(), Optional.of(sparse));
        } catch (CertificateWork.Stop stop) {
            return empty(Status.UNAVAILABLE, stop.getMessage(), work);
        }
    }

    private static int activePrefix(ExactRational[] weights, int[] active, CertificateWork work) {
        int size = 0;
        for (int row = 0; row < weights.length && size < active.length; row++) {
            if (!work.check(weights[row]).isZero()) active[size++] = row;
        }
        return size;
    }

    /** Finds the first dependent augmented column by elimination on an active prefix. */
    private static ExactRational[] dependency(Problem problem, int[] active, int count,
            long retainedCells, CertificateWork work) {
        int variables = problem.variables();
        int dimensions = variables + 1;
        // Augmented coordinates are rows here, constraints in the active prefix are columns.
        long cells = dimensions * (count + 1L) + count;
        work.cells(retainedCells + cells);
        work.charge(cells);
        var matrix = new ExactRational[dimensions][count];
        for (int coordinate = 0; coordinate < dimensions; coordinate++) {
            for (int column = 0; column < count; column++) {
                int sourceRow = active[column];
                var value = coordinate == variables ? problem.b().get(sourceRow)
                    : problem.a().get(sourceRow).get(coordinate);
                matrix[coordinate][column] = work.check(value);
            }
        }

        for (int column = 0; column < count; column++) {
            int pivot = column;
            while (pivot < dimensions && work.check(matrix[pivot][column]).isZero()) pivot++;
            if (pivot == dimensions) return backSubstitute(matrix, column, work);
            work.charge(1);
            var row = matrix[pivot];
            matrix[pivot] = matrix[column];
            matrix[column] = row;
            for (int below = column + 1; below < dimensions; below++) {
                var entry = work.check(matrix[below][column]);
                if (entry.isZero()) continue;
                var factor = work.div(entry, row[column]);
                matrix[below][column] = ZERO;
                for (int right = column + 1; right < count; right++) {
                    matrix[below][right] = work.sub(matrix[below][right], work.mul(factor, row[right]));
                }
            }
        }
        return null;
    }

    /** Gives the free column coefficient +1, so minimum-ratio cancellation always has a positive entry. */
    private static ExactRational[] backSubstitute(ExactRational[][] matrix, int free, CertificateWork work) {
        work.charge(free + 1L);
        var direction = new ExactRational[free + 1];
        direction[free] = ONE;
        for (int row = free - 1; row >= 0; row--) {
            var residual = ZERO;
            for (int column = row + 1; column <= free; column++) {
                residual = work.sub(residual, work.mul(matrix[row][column], direction[column]));
            }
            direction[row] = work.div(residual, matrix[row][row]);
        }
        return direction;
    }

    private static void cancel(ExactRational[] weights, int[] active, ExactRational[] direction,
            CertificateWork work) {
        int last = direction.length - 1;
        var step = work.check(weights[active[last]]);
        for (int index = 0; index < last; index++) {
            var coefficient = work.check(direction[index]);
            if (coefficient.signum() > 0) {
                var ratio = work.div(weights[active[index]], coefficient);
                if (work.sub(ratio, step).signum() < 0) step = ratio;
            }
        }
        for (int index = 0; index < direction.length; index++) {
            int row = active[index];
            weights[row] = work.sub(weights[row], work.mul(step, direction[index]));
        }
    }

    private static Result empty(Status status, String reason, CertificateWork work) {
        return new Result(status, reason, work.used(), Optional.empty());
    }
}
