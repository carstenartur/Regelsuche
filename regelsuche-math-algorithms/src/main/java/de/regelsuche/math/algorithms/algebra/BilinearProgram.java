package de.regelsuche.math.algorithms.algebra;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.scalar.ExactRational;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** U and V hold one row per product; W holds one row of product weights per output. */
public record BilinearProgram(List<List<ExactRational>> u, List<List<ExactRational>> v,
                             List<List<ExactRational>> w) {
    public BilinearProgram {
        u = copyRows(u);
        v = copyRows(v);
        w = copyRows(w);
    }

    private static List<List<ExactRational>> copyRows(List<List<ExactRational>> rows) {
        return rows.stream().map(List::copyOf).toList();
    }

    /** One flattened, left-major coefficient matrix per output. */
    public record Tensor(int leftSize, int rightSize, List<List<ExactRational>> outputs) {
        public Tensor {
            outputs = copyRows(outputs);
            if (leftSize < 1 || rightSize < 1 || outputs.isEmpty()) {
                throw new IllegalArgumentException("EMPTY_TENSOR_DIMENSION");
            }
            requireWidth(outputs, (long) leftSize * rightSize);
        }
    }

    /** Every pair from the left and right dictionaries is eligible, in the supplied order. */
    public record Request(Tensor tensor, List<List<ExactRational>> leftForms,
                          List<List<ExactRational>> rightForms, int maximumProducts) {
        public Request {
            Objects.requireNonNull(tensor);
            leftForms = copyRows(leftForms);
            rightForms = copyRows(rightForms);
            requireWidth(leftForms, tensor.leftSize());
            requireWidth(rightForms, tensor.rightSize());
            if (maximumProducts < 0) throw new IllegalArgumentException("NEGATIVE_PRODUCT_LIMIT");
        }

        long checkInputs(CertificateWork work) {
            long cells = matrixCells(tensor.outputs()) + matrixCells(leftForms) + matrixCells(rightForms);
            work.cells(cells);
            checkEntries(tensor.outputs(), work);
            checkEntries(leftForms, work);
            checkEntries(rightForms, work);
            return cells;
        }
    }

    private static void requireWidth(List<List<ExactRational>> rows, long width) {
        for (var row : rows) {
            if (row.size() != width) throw new IllegalArgumentException("INVALID_ROW_WIDTH");
        }
    }

    // Rectangular matrices only. A row descriptor counts even for zero-width output weights.
    static long matrixCells(List<List<ExactRational>> rows) {
        return rows.isEmpty() ? 0 : (long) rows.size() * (1L + rows.getFirst().size());
    }

    static void checkEntries(List<List<ExactRational>> rows, CertificateWork work) {
        for (var row : rows) {
            work.charge(1);
            for (var value : row) work.check(value);
        }
    }

    /** Straight-line schedule counts with nonunit scaling (including -1) and peak scalar temporaries. */
    public record Cost(long variableMultiplications, long additions,
                       long constantMultiplications, long peakTemporaries) {}

    public record Witness(BilinearProgram program, Cost cost) {
        public Witness {
            Objects.requireNonNull(program);
            Objects.requireNonNull(cost);
        }
    }

    public enum Status { VERIFIED, REJECTED, INCONCLUSIVE }

    public record Result<T>(Status status, String reason, Optional<T> value, long work) {
        public Result {
            Objects.requireNonNull(status);
            Objects.requireNonNull(reason);
            Objects.requireNonNull(value);
            if ((status == Status.VERIFIED) != value.isPresent() || work < 0) {
                throw new IllegalArgumentException("INVALID_BILINEAR_RESULT");
            }
        }
    }

    static <T> Result<T> run(IterationLimits limits, BooleanSupplier cancellation,
                             Function<CertificateWork, T> operation) {
        var work = new CertificateWork(limits, cancellation);
        try {
            work.charge(0);
            T verified = operation.apply(work);
            work.charge(0);
            return new Result<>(Status.VERIFIED, "VERIFIED", Optional.of(verified), work.used());
        } catch (CertificateWork.Stop stop) {
            return new Result<>(Status.INCONCLUSIVE, stop.getMessage(), Optional.empty(), work.used());
        } catch (Rejection invalid) {
            return new Result<>(Status.REJECTED, invalid.getMessage(), Optional.empty(), work.used());
        }
    }

    static final class Rejection extends RuntimeException {
        private static final long serialVersionUID = 1L;
        Rejection(String reason) { super(reason, null, false, false); }
    }
}
