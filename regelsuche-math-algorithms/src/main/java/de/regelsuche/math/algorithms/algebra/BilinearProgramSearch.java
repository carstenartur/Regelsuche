package de.regelsuche.math.algorithms.algebra;

import de.regelsuche.math.algorithms.algebra.BilinearProgram.Request;
import de.regelsuche.math.algorithms.algebra.BilinearProgram.Result;
import de.regelsuche.math.algorithms.algebra.BilinearProgram.Tensor;
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
        return BilinearProgram.run(limits, cancellation, work -> find(request, 0, 0, work));
    }

    /** Verify the supplied lower bound on this source before skipping any product counts. */
    public static Result<Witness> searchWithRationalBound(Request request, BilinearRankBound.RationalCertificate proof,
            IterationLimits limits, BooleanSupplier cancellation) {
        return BilinearProgram.run(limits, cancellation, work -> {
            int lower = BilinearRankBound.verifyRational(request.tensor(), proof, request.checkInputs(work), work);
            if (lower > request.maximumProducts()) throw new CertificateWork.Stop("PRODUCT_LIMIT_BELOW_BOUND");
            long retained = proof.extraCells() + proof.minor().retainedCells();
            if (proof.minor().source() != request.tensor())
                retained += BilinearProgram.matrixCells(proof.minor().source().outputs());
            return find(request, lower, retained, work);
        });
    }

    static Witness find(Request request, int minimumRank, long additionalRetained, CertificateWork work) {
        long sourceCells = request.checkInputs(work) + additionalRetained;
        work.cells(sourceCells);
        long pairs = (long) request.leftForms().size() * request.rightForms().size();
        int dimension = request.tensor().outputs().getFirst().size();
        int maximum = (int) Math.min(pairs, Math.min(dimension, request.maximumProducts()));
        for (int rank = minimumRank; rank <= maximum; rank++) {
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

    static boolean nextSubset(long[] subset, long pairs, CertificateWork work) {
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

    static BilinearProgram solve(Request request, long[] subset, long sourceCells, CertificateWork work) {
        int rank = subset.length;
        work.cells(sourceCells + 3L * rank);
        var left = new ArrayList<List<ExactRational>>(rank);
        var right = new ArrayList<List<ExactRational>>(rank);
        int rightCount = request.rightForms().size();
        for (long index : subset) {
            work.charge(1);
            left.add(request.leftForms().get((int) (index / rightCount)));
            right.add(request.rightForms().get((int) (index % rightCount)));
        }
        return solveWeights(request.tensor(), left, right, sourceCells + 3L * rank, work);
    }

    /** Refit output weights to an ordered list of independent paired forms. */
    static BilinearProgram fitProducts(Tensor tensor, List<List<ExactRational>> left,
                                       List<List<ExactRational>> right, long additionalRetained,
                                       CertificateWork work) {
        int rank = left.size();
        if (rank != right.size() || rank > tensor.outputs().getFirst().size()) return null;
        long cells = additionalRetained + BilinearProgram.matrixCells(tensor.outputs())
            + rank * (2L + tensor.leftSize() + tensor.rightSize());
        work.cells(cells);
        for (int i = 0; i < rank; i++) {
            work.charge(1);
            if (left.get(i).size() != tensor.leftSize() || right.get(i).size() != tensor.rightSize()) return null;
        }
        BilinearProgram.checkEntries(left, work);
        BilinearProgram.checkEntries(right, work);
        BilinearProgram.checkEntries(tensor.outputs(), work);
        return solveWeights(tensor, left, right, cells, work);
    }

    private static BilinearProgram solveWeights(Tensor tensor, List<List<ExactRational>> left,
            List<List<ExactRational>> right, long sourceCells, CertificateWork work) {
        int rank = left.size();
        int rows = tensor.outputs().getFirst().size();
        int outputs = tensor.outputs().size();
        long retained = sourceCells + rows * (1L + rank + outputs);
        work.cells(retained);
        var matrix = new ExactRational[rows][rank + outputs];
        for (int column = 0; column < rank; column++) {
            var u = left.get(column);
            var v = right.get(column);
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
        work.cells(retained + (long) outputs * (2L + rank) + 3L * rank + 1);
        var weights = new ArrayList<List<ExactRational>>(outputs);
        for (int k = 0; k < outputs; k++) {
            work.charge(1L + rank);
            var output = new ArrayList<ExactRational>(rank);
            for (int i = 0; i < rank; i++) output.add(matrix[i][rank + k]);
            work.charge(rank);
            weights.add(List.copyOf(output));
        }
        work.charge(2L * rank + outputs);
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
