package de.regelsuche.math.algorithms.algebra;

import de.regelsuche.math.algorithms.algebra.BilinearProgram.Request;
import de.regelsuche.math.algorithms.algebra.BilinearProgram.Result;
import de.regelsuche.math.algorithms.algebra.BilinearProgram.Tensor;
import de.regelsuche.math.algorithms.algebra.BilinearProgram.Witness;
import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Discovers rational input forms from the target coefficients without a caller-supplied dictionary. */
public final class BilinearFormDiscovery {
    private BilinearFormDiscovery() {}

    /** The generated request can be replayed with the ordinary verifier and evaluator. */
    public record Discovery(Request request, Witness witness, BilinearRankBound.Certificate lowerBound) {
        public Discovery {
            Objects.requireNonNull(request);
            Objects.requireNonNull(witness);
            Objects.requireNonNull(lowerBound);
        }
    }

    public static Result<Discovery> discover(Tensor tensor, int maximumProducts,
            IterationLimits limits, BooleanSupplier cancellation) {
        return BilinearProgram.run(limits, cancellation, work -> discover(tensor, maximumProducts, work));
    }

    static Discovery discover(Tensor tensor, int maximumProducts, CertificateWork work) {
        return discover(tensor, maximumProducts, 0, work);
    }

    static Discovery discover(Tensor tensor, int maximumProducts, long additionalRetained, CertificateWork work) {
        if (maximumProducts < 0) throw new IllegalArgumentException("NEGATIVE_PRODUCT_LIMIT");
        long sourceCells = additionalRetained + BilinearProgram.matrixCells(tensor.outputs());
        work.cells(sourceCells);
        BilinearProgram.checkEntries(tensor.outputs(), work);
        var bound = BilinearRankBound.derive(tensor, sourceCells, work);
        if (bound.lowerBound() > maximumProducts) throw new CertificateWork.Stop("PRODUCT_LIMIT_BELOW_BOUND");
        long extra = additionalRetained + bound.retainedCells();

        if (tensor.outputs().size() == 1 || bound.lowerBound() == 0) {
            var program = oneMatrixOrZero(tensor, bound.lowerBound(), sourceCells + bound.retainedCells(), work);
            long factors = BilinearProgram.matrixCells(program.u()) + BilinearProgram.matrixCells(program.v());
            work.cells(sourceCells + bound.retainedCells() + factors + BilinearProgram.matrixCells(program.w())
                + 2L * program.u().size());
            work.charge(2L * program.u().size());
            var request = new Request(tensor, program.u(), program.v(), maximumProducts);
            var cost = BilinearProgramVerifier.check(request, program, extra + request.checkInputs(work), work);
            return new Discovery(request, new Witness(program, cost), bound);
        }

        var request = generatedRequest(tensor, maximumProducts, sourceCells + bound.retainedCells(), false, work);
        var witness = BilinearProgramSearch.find(request, bound.lowerBound(), extra, work);
        return new Discovery(request, witness, bound);
    }

    static Request generatedRequest(Tensor tensor, int maximumProducts, long retained, boolean integral, CertificateWork work) {
        return new Forms(tensor, retained, integral, work).request(maximumProducts);
    }

    // Exact rank factorization for a single matrix: each nonzero pivot removes one rank-one outer product.
    private static BilinearProgram oneMatrixOrZero(Tensor tensor, int rank, long retained, CertificateWork work) {
        int height = tensor.leftSize();
        int width = tensor.rightSize();
        int cells = tensor.outputs().getFirst().size();
        work.cells(retained + (rank == 0 ? 0 : cells + (long) Math.max(width, height))
            + rank * (4L + height + width) + (long) tensor.outputs().size() * (2L + rank));
        var left = new ArrayList<List<ExactRational>>(rank);
        var right = new ArrayList<List<ExactRational>>(rank);
        if (rank > 0) {
            work.charge(cells);
            var residual = new ArrayList<>(tensor.outputs().getFirst());
            int pivot = 0;
            for (int step = 0; step < rank; step++) {
                while (pivot < cells && residual.get(pivot).isZero()) {
                    work.charge(1);
                    pivot++;
                }
                if (pivot == cells) throw new IllegalStateException("RANK_EXCEEDS_MATRIX");
                int column = pivot % width;
                int rowStart = pivot - column;
                var u = new ArrayList<ExactRational>(height);
                var v = new ArrayList<ExactRational>(width);
                for (int i = 0; i < height; i++) u.add(work.div(residual.get(i * width + column), residual.get(pivot)));
                work.charge(width);
                v.addAll(residual.subList(rowStart, rowStart + width));
                for (int i = 0; i < height; i++) {
                    for (int j = 0; j < width; j++) {
                        int index = i * width + j;
                        residual.set(index, work.sub(residual.get(index), work.mul(u.get(i), v.get(j))));
                    }
                }
                work.charge((long) height + width);
                left.add(List.copyOf(u));
                right.add(List.copyOf(v));
            }
        }
        work.charge(rank * (2L + width + height) + (long) tensor.outputs().size() * (rank + 1L));
        return new BilinearProgram(left, right,
            Collections.nCopies(tensor.outputs().size(), Collections.nCopies(rank, ExactRational.ONE)));
    }

    private static final class Forms {
        private final Tensor tensor;
        private final CertificateWork work;
        private final boolean integral;
        private final List<List<ExactRational>> left = new ArrayList<>();
        private final List<List<ExactRational>> right = new ArrayList<>();
        private long retained;

        Forms(Tensor tensor, long sourceCells, boolean integral, CertificateWork work) {
            this.tensor = tensor;
            this.integral = integral;
            this.retained = sourceCells;
            this.work = work;
        }

        Request request(int maximumProducts) {
            int height = tensor.leftSize();
            int width = tensor.rightSize();
            for (var output : tensor.outputs()) {
                for (int j = 0; j < width; j++) addFiber(left, output, j, width, height);
                for (int i = 0; i < height; i++) addFiber(right, output, i * width, 1, width);
            }
            addCoordinateForms(left, height);
            addCoordinateForms(right, width);
            work.cells(retained + left.size() + right.size());
            work.charge((long) left.size() + right.size());
            return new Request(tensor, left, right, maximumProducts);
        }

        /** Rational forms have leading coefficient one; integer forms are signed primitive vectors. */
        private void addFiber(List<List<ExactRational>> forms, List<ExactRational> output,
                              int start, int stride, int size) {
            var first = ExactRational.ZERO;
            for (int k = 0; k < size && first.isZero(); k++) {
                work.charge(1);
                first = output.get(start + k * stride);
            }
            if (first.isZero()) return;
            var divisor = first;
            if (integral && !first.numerator().abs().equals(BigInteger.ONE)) {
                var content = first.numerator().abs();
                for (int k = 0; k < size && !content.equals(BigInteger.ONE); k++) {
                    var coefficient = output.get(start + k * stride).numerator();
                    work.check(coefficient);
                    work.check(content);
                    content = work.check(content.gcd(coefficient));
                }
                divisor = ExactRational.integer(first.numerator().signum() < 0 ? content.negate() : content);
            }
            work.cells(retained + size + 1L);
            var vector = new ArrayList<ExactRational>(size);
            for (int k = 0; k < size; k++) vector.add(work.div(output.get(start + k * stride), divisor));
            addDistinct(forms, vector);
        }

        private void addCoordinateForms(List<List<ExactRational>> forms, int size) {
            work.cells(retained + size + 1L);
            work.charge(size);
            var vector = new ArrayList<>(Collections.nCopies(size, ExactRational.ZERO));
            for (int i = 0; i < size; i++) {
                vector.set(i, ExactRational.ONE);
                addDistinct(forms, vector);
                for (int j = i + 1; j < size; j++) {
                    vector.set(j, ExactRational.ONE);
                    addDistinct(forms, vector);
                    vector.set(j, ExactRational.NEGATIVE_ONE);
                    addDistinct(forms, vector);
                    vector.set(j, ExactRational.ZERO);
                }
                vector.set(i, ExactRational.ZERO);
            }
        }

        private void addDistinct(List<List<ExactRational>> forms, List<ExactRational> candidate) {
            long cells = candidate.size() + 1L;
            for (var form : forms) {
                work.charge(cells);
                if (form.equals(candidate)) return;
            }
            work.cells(retained + 2 * cells);
            work.charge(cells);
            forms.add(List.copyOf(candidate));
            retained += cells;
        }
    }
}
