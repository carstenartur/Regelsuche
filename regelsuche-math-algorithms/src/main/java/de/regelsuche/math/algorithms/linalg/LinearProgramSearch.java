package de.regelsuche.math.algorithms.linalg;

import static de.regelsuche.scalar.ExactRational.NEGATIVE_ONE;
import static de.regelsuche.scalar.ExactRational.ONE;
import static de.regelsuche.scalar.ExactRational.ZERO;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.linalg.LinearProgramCertificates.Certificate;
import de.regelsuche.math.algorithms.linalg.LinearProgramCertificates.Infeasible;
import de.regelsuche.math.algorithms.linalg.LinearProgramCertificates.Optimal;
import de.regelsuche.math.algorithms.linalg.LinearProgramCertificates.Problem;
import de.regelsuche.math.algorithms.linalg.LinearProgramCertificates.Unbounded;
import de.regelsuche.math.algorithms.linalg.LinearProgramVerifier.Status;
import de.regelsuche.scalar.ExactRational;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** Bounded exact two-phase simplex with independent checking on the original problem. */
public final class LinearProgramSearch {
    private LinearProgramSearch() {}

    public record Result(Status status, String reason, long work, Optional<Certificate> certificate) {}

    /**
     * Searches for infeasibility, optimality or an improving ray. Variables are unrestricted.
     * An exhausted budget returns UNAVAILABLE with no mathematical classification.
     */
    public static Result search(Problem problem, IterationLimits limits, BooleanSupplier cancellation) {
        var work = new CertificateWork(limits, cancellation);
        try {
            var certificate = search(problem, 0, work);
            work.charge(0);
            return new Result(Status.VERIFIED, "VERIFIED", work.used(), Optional.of(certificate));
        } catch (CertificateWork.Stop stop) {
            return new Result(Status.UNAVAILABLE, stop.getMessage(), work.used(), Optional.empty());
        }
    }

    /** The implication consumer shares this accounting object, including its deadline. */
    static Certificate search(Problem problem, long retainedCells, CertificateWork work) {
        Objects.requireNonNull(problem);
        var tableau = new Tableau(problem, retainedCells, work);
        var certificate = tableau.solve();
        var verified = LinearProgramVerifier.verify(problem, certificate, false,
            tableau.verificationRetainedCells(), work);
        if (!verified.valid()) throw new CertificateWork.Stop("OUTPUT_" + verified.reason());
        work.charge(0);
        return certificate;
    }

    /**
     * Rows represent M_B^-1 M z = M_B^-1 b. The last row contains negative reduced
     * costs and the objective value. Columns are x+, x-, original slacks, artificials.
     * The original slack columns retain the dual row transformation, including row signs.
     */
    private static final class Tableau {
        private final Problem problem;
        private final CertificateWork work;
        private final int rows;
        private final int variables;
        private final int ordinary;
        private final int columns;
        private final long retainedCells;
        private final long tableauCells;
        private final ExactRational[][] matrix;
        private final int[] basis;

        private Tableau(Problem problem, long retainedCells, CertificateWork work) {
            this.problem = problem;
            this.work = work;
            this.retainedCells = retainedCells;
            rows = problem.constraints();
            variables = problem.variables();
            work.cells(retainedCells);
            work.cells(problem.storageCells());
            // These individual bounds precede narrowing or multiplying dimensions.
            long width = 2L * variables + 2L * rows + 1;
            work.cells(width);
            columns = (int) width - 1;
            ordinary = 2 * variables + rows;
            tableauCells = (rows + 1L) * (columns + 2L) + rows;
            // Reserve matrix, row references, basis, output arrays and immutable snapshots.
            work.cells(retainedCells + problem.storageCells() + tableauCells
                + 4L * variables + 2L * rows + 16);
            work.charge(tableauCells);
            matrix = new ExactRational[rows + 1][columns + 1];
            basis = new int[rows];
            for (var row : matrix) {
                work.charge(columns + 1L);
                Arrays.fill(row, ZERO);
            }
            initialize();
        }

        private void initialize() {
            for (var coefficient : problem.c()) work.check(coefficient);
            for (int row = 0; row < rows; row++) {
                var bound = work.check(problem.b().get(row));
                boolean negative = bound.signum() < 0;
                for (int column = 0; column < variables; column++) {
                    var value = work.check(problem.a().get(row).get(column));
                    if (negative) value = work.sub(ZERO, value);
                    matrix[row][column] = value;
                    matrix[row][variables + column] = work.sub(ZERO, value);
                }
                matrix[row][2 * variables + row] = negative ? NEGATIVE_ONE : ONE;
                matrix[row][ordinary + row] = ONE;
                matrix[row][columns] = negative ? work.sub(ZERO, bound) : bound;
                basis[row] = ordinary + row;
                matrix[rows][ordinary + row] = ONE;
            }
            // Maximize minus the sum of artificial variables from their feasible initial basis.
            for (int row = 0; row < rows; row++) {
                for (int column = 0; column <= columns; column++) {
                    matrix[rows][column] = work.sub(matrix[rows][column], matrix[row][column]);
                }
            }
        }

        private Certificate solve() {
            if (optimize(columns) >= 0) throw new CertificateWork.Stop("PHASE_ONE_UNBOUNDED");
            int phaseOneSign = work.check(matrix[rows][columns]).signum();
            if (phaseOneSign < 0) return new Infeasible(problem, multiplier(true));
            if (phaseOneSign > 0) throw new CertificateWork.Stop("PHASE_ONE_INVARIANT");
            removeArtificialBasics();
            originalObjective();
            int entering = optimize(ordinary);
            var point = point();
            if (entering >= 0) return new Unbounded(problem, point, ray(entering));
            return new Optimal(problem, point, multiplier(false));
        }

        /** Bland's smallest entering index and smallest basic index among ratio ties. */
        private int optimize(int allowedColumns) {
            while (true) {
                int entering = -1;
                for (int column = 0; column < allowedColumns; column++) {
                    if (work.check(matrix[rows][column]).signum() < 0) {
                        entering = column;
                        break;
                    }
                }
                if (entering < 0) return -1;
                int leaving = -1;
                ExactRational minimum = null;
                for (int row = 0; row < rows; row++) {
                    var coefficient = work.check(matrix[row][entering]);
                    if (coefficient.signum() <= 0) continue;
                    var ratio = work.div(matrix[row][columns], coefficient);
                    int comparison = minimum == null ? -1 : work.sub(ratio, minimum).signum();
                    if (comparison < 0 || comparison == 0 && basis[row] < basis[leaving]) {
                        leaving = row;
                        minimum = ratio;
                    }
                }
                if (leaving < 0) return entering;
                pivot(leaving, entering);
            }
        }

        private void pivot(int leaving, int entering) {
            var pivot = work.check(matrix[leaving][entering]);
            for (int column = 0; column <= columns; column++) {
                if (column != entering) {
                    matrix[leaving][column] = work.div(matrix[leaving][column], pivot);
                }
            }
            matrix[leaving][entering] = ONE;
            for (int row = 0; row <= rows; row++) {
                if (row == leaving) continue;
                var factor = work.check(matrix[row][entering]);
                if (factor.isZero()) continue;
                for (int column = 0; column <= columns; column++) {
                    if (column != entering) {
                        matrix[row][column] = work.sub(matrix[row][column],
                            work.mul(factor, matrix[leaving][column]));
                    }
                }
                matrix[row][entering] = ZERO;
            }
            work.charge(1);
            basis[leaving] = entering;
        }

        private void removeArtificialBasics() {
            for (int row = 0; row < rows; row++) {
                work.charge(1);
                if (basis[row] < ordinary) continue;
                if (!work.check(matrix[row][columns]).isZero()) {
                    throw new CertificateWork.Stop("ARTIFICIAL_BASIS_INVARIANT");
                }
                int entering = 0;
                while (entering < ordinary && work.check(matrix[row][entering]).isZero()) entering++;
                // Original slacks make [A,-A,I] full row rank, even with redundant constraints.
                if (entering == ordinary) throw new CertificateWork.Stop("ARTIFICIAL_BASIS_INVARIANT");
                pivot(row, entering);
            }
        }

        private void originalObjective() {
            work.charge(columns + 1L);
            Arrays.fill(matrix[rows], ZERO);
            for (int column = 0; column < variables; column++) {
                var value = work.check(problem.c().get(column));
                matrix[rows][column] = work.sub(ZERO, value);
                matrix[rows][variables + column] = value;
            }
            for (int row = 0; row < rows; row++) {
                work.charge(1);
                int basic = basis[row];
                if (basic >= 2 * variables) continue;
                var cost = basic < variables ? problem.c().get(basic)
                    : work.sub(ZERO, problem.c().get(basic - variables));
                if (cost.isZero()) continue;
                for (int column = 0; column <= columns; column++) {
                    matrix[rows][column] = work.add(matrix[rows][column], work.mul(cost, matrix[row][column]));
                }
            }
        }

        private List<ExactRational> point() {
            work.charge(2L * variables);
            var result = new ExactRational[variables];
            Arrays.fill(result, ZERO);
            for (int row = 0; row < rows; row++) {
                work.charge(1);
                int basic = basis[row];
                if (basic < variables) result[basic] = work.add(result[basic], matrix[row][columns]);
                else if (basic < 2 * variables) {
                    int column = basic - variables;
                    result[column] = work.sub(result[column], matrix[row][columns]);
                }
            }
            return List.copyOf(Arrays.asList(result));
        }

        private List<ExactRational> multiplier(boolean normalizeContradiction) {
            work.charge(2L * rows);
            var result = new ExactRational[rows];
            var scale = normalizeContradiction ? work.div(NEGATIVE_ONE, matrix[rows][columns]) : ONE;
            for (int row = 0; row < rows; row++) {
                result[row] = work.mul(scale, matrix[rows][2 * variables + row]);
            }
            return List.copyOf(Arrays.asList(result));
        }

        private List<ExactRational> ray(int entering) {
            work.charge(2L * variables);
            var result = new ExactRational[variables];
            Arrays.fill(result, ZERO);
            if (entering < variables) result[entering] = ONE;
            else if (entering < 2 * variables) result[entering - variables] = NEGATIVE_ONE;
            for (int row = 0; row < rows; row++) {
                work.charge(1);
                int basic = basis[row];
                if (basic < variables) result[basic] = work.sub(result[basic], matrix[row][entering]);
                else if (basic < 2 * variables) {
                    int column = basic - variables;
                    result[column] = work.add(result[column], matrix[row][entering]);
                }
            }
            var gain = work.sub(ZERO, matrix[rows][entering]);
            for (int column = 0; column < variables; column++) result[column] = work.div(result[column], gain);
            return List.copyOf(Arrays.asList(result));
        }

        private long verificationRetainedCells() {
            return retainedCells + tableauCells + 2L * variables + rows + 16;
        }
    }
}
