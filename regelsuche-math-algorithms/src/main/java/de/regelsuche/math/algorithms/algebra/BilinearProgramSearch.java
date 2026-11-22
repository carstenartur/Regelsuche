package de.regelsuche.math.algorithms.algebra;

import de.regelsuche.math.algorithms.algebra.BilinearProgram.Request;
import de.regelsuche.math.algorithms.algebra.BilinearProgram.Result;
import de.regelsuche.math.algorithms.algebra.BilinearProgram.Witness;
import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.scalar.ExactRational;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Enumerates finite subsets of the Cartesian dictionary and solves all output weights together. */
public final class BilinearProgramSearch {
    private BilinearProgramSearch() {}

    public static Result<Witness> search(Request request, IterationLimits limits, BooleanSupplier cancellation) {
        return BilinearProgram.run(limits, cancellation, work -> find(request, work));
    }

    private static Witness find(Request request, CertificateWork work) {
        long sourceCells = request.checkInputs(work);
        long pairs = (long) request.leftForms().size() * request.rightForms().size();
        int dimension = request.tensor().outputs().getFirst().size();
        int maximum = (int) Math.min(pairs, Math.min(dimension, request.maximumProducts()));
        for (int rank = 0; rank <= maximum; rank++) {
            work.cells(sourceCells + rank);
            long[] subset = new long[rank];
            for (int i = 0; i < rank; i++) {
                work.charge(1);
                subset[i] = i;
            }
            do {
                work.charge(1);
                var program = solve(request, subset, sourceCells, work);
                if (program != null) {
                    var cost = BilinearProgramVerifier.check(request, program, sourceCells, work);
                    return new Witness(program, cost);
                }
            } while (nextSubset(subset, pairs, work));
        }
        throw new CertificateWork.Stop("DICTIONARY_EXHAUSTED");
    }

    private static boolean nextSubset(long[] subset, long pairs, CertificateWork work) {
        for (int i = subset.length - 1; i >= 0; i--) {
            work.charge(1);
            if (subset[i] < pairs - subset.length + i) {
                subset[i]++;
                for (int j = i + 1; j < subset.length; j++) {
                    work.charge(1);
                    subset[j] = subset[j - 1] + 1;
                }
                return true;
            }
        }
        return false;
    }

    private static BilinearProgram solve(Request request, long[] subset, long sourceCells, CertificateWork work) {
        int rank = subset.length;
        var tensor = request.tensor();
        int rows = tensor.outputs().getFirst().size();
        int outputs = tensor.outputs().size();
        long retained = sourceCells + 3L * rank + rows * (1L + rank + outputs);
        work.cells(retained);
        var matrix = new ExactRational[rows][rank + outputs];
        var left = new ArrayList<List<ExactRational>>(rank);
        var right = new ArrayList<List<ExactRational>>(rank);
        for (int column = 0; column < rank; column++) {
            int rightCount = request.rightForms().size();
            var u = request.leftForms().get((int) (subset[column] / rightCount));
            var v = request.rightForms().get((int) (subset[column] % rightCount));
            left.add(u);
            right.add(v);
            for (int i = 0; i < u.size(); i++) {
                for (int j = 0; j < v.size(); j++) {
                    matrix[i * v.size() + j][column] = work.mul(u.get(i), v.get(j));
                }
            }
        }
        for (int k = 0; k < outputs; k++) {
            var target = tensor.outputs().get(k);
            for (int row = 0; row < rows; row++) {
                work.charge(1);
                matrix[row][rank + k] = target.get(row);
            }
        }
        if (!reduce(matrix, rank, work)) return null;
        work.cells(retained + (long) outputs * (1L + rank));
        var weights = new ArrayList<List<ExactRational>>(outputs);
        for (int k = 0; k < outputs; k++) {
            work.charge(1L + rank);
            var output = new ArrayList<ExactRational>(rank);
            for (int i = 0; i < rank; i++) output.add(matrix[i][rank + k]);
            weights.add(output);
        }
        return new BilinearProgram(left, right, weights);
    }

    // Independent columns suffice: removing a dependent column preserves their joint span.
    private static boolean reduce(ExactRational[][] matrix, int rank, CertificateWork work) {
        int width = matrix[0].length;
        for (int col = 0; col < rank; col++) {
            int pivot = col;
            while (pivot < matrix.length && matrix[pivot][col].isZero()) {
                work.charge(1);
                pivot++;
            }
            if (pivot == matrix.length) return false;
            var swapped = matrix[pivot];
            matrix[pivot] = matrix[col];
            matrix[col] = swapped;
            var divisor = swapped[col];
            for (int j = col; j < width; j++) swapped[j] = work.div(swapped[j], divisor);
            for (int row = 0; row < matrix.length; row++) {
                work.charge(1);
                var factor = matrix[row][col];
                if (row == col || factor.isZero()) continue;
                for (int j = col; j < width; j++) {
                    matrix[row][j] = work.sub(matrix[row][j], work.mul(factor, swapped[j]));
                }
            }
        }
        for (int row = rank; row < matrix.length; row++) {
            for (int j = rank; j < width; j++) {
                work.charge(1);
                if (!matrix[row][j].isZero()) return false;
            }
        }
        return true;
    }
}
