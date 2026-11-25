package de.regelsuche.math.algorithms.algebra;

import de.regelsuche.math.algorithms.algebra.BilinearFormDiscovery.Discovery;
import de.regelsuche.math.algorithms.algebra.BilinearProgram.Request;
import de.regelsuche.math.algorithms.algebra.BilinearProgram.Result;
import de.regelsuche.math.algorithms.algebra.BilinearProgram.Tensor;
import de.regelsuche.math.algorithms.algebra.BilinearProgram.Witness;
import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.scalar.ExactRational;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** Retain the paired factors of a verified training program and solve fresh output weights on a new tensor. */
public final class BilinearProductLearning {
    private BilinearProductLearning() {}

    /** The source label records provenance. Only input-side factors are transported to the next task. */
    public record Basis(String sourceId, int leftSize, int rightSize,
                        List<List<ExactRational>> left, List<List<ExactRational>> right) {
        public Basis {
            if (sourceId.isBlank() || leftSize < 1 || rightSize < 1 || left.size() != right.size()) {
                throw new IllegalArgumentException("INVALID_BILINEAR_BASIS");
            }
            left = copyForms(left, leftSize);
            right = copyForms(right, rightSize);
        }

        private static List<List<ExactRational>> copyForms(List<List<ExactRational>> forms, int width) {
            for (var row : forms) {
                if (row.size() != width) throw new IllegalArgumentException("INVALID_FORM_WIDTH");
            }
            return forms.stream().map(List::copyOf).toList();
        }

        private long retainedCells() {
            return 1L + sourceId.length() + BilinearProgram.matrixCells(left) + BilinearProgram.matrixCells(right);
        }
    }

    /** Invertible rational maps from new coordinates to old: x_old=L*x_new and y_old=R*y_new. */
    public record InputBases(List<List<ExactRational>> left, List<List<ExactRational>> right) {
        public InputBases {
            left = square(left);
            right = square(right);
        }
        private static List<List<ExactRational>> square(List<List<ExactRational>> matrix) {
            if (matrix.isEmpty()) throw new IllegalArgumentException("EMPTY_BASIS_MATRIX");
            return Basis.copyForms(matrix, matrix.size());
        }
        long retainedCells() { return 2L + BilinearProgram.matrixCells(left) + BilinearProgram.matrixCells(right); }
    }

    /** All inputs needed to replay the claim that these exact product forms came from this source. */
    public record Transfer(Request source, BilinearProgram sourceProgram, InputBases bases, Request target, Witness witness) {}

    /** Transform the paired forms of the supplied source program, preserving their order and output weights. */
    public static Result<Transfer> applyInputBases(Request source, BilinearProgram program, InputBases bases,
            Tensor target, IterationLimits limits, BooleanSupplier cancellation) {
        return BilinearProgram.run(limits, cancellation, work -> {
            long retained = BilinearProgramVerifier.checkedTransferSource(source, program, bases, target, 0, work);
            long forms = BilinearProgram.matrixCells(program.u()) + BilinearProgram.matrixCells(program.v());
            long rowBuffer = program.u().isEmpty() ? 0 : Math.max(bases.left().size(), bases.right().size());
            work.cells(retained + forms + rowBuffer + 4L * program.u().size() + 2L * program.w().size() + 8);
            var left = transport(program.u(), bases.left(), work);
            var right = transport(program.v(), bases.right(), work);
            work.charge(4L * program.u().size() + program.w().size());
            var moved = new BilinearProgram(left, right, program.w());
            var request = new Request(target, left, right, source.maximumProducts());
            long dictionaries = BilinearProgram.matrixCells(left) + BilinearProgram.matrixCells(right);
            var cost = BilinearProgramVerifier.checkTransport(program, bases, request, moved, retained + dictionaries, work);
            return new Transfer(source, program, bases, request, new Witness(moved, cost));
        });
    }

    // Row axpy: accumulate each scaled old basis row into the new-coordinate form.
    private static List<List<ExactRational>> transport(List<List<ExactRational>> forms, List<List<ExactRational>> matrix,
            CertificateWork work) {
        var result = new ArrayList<List<ExactRational>>(forms.size());
        for (var row : forms) {
            work.charge(matrix.size());
            var values = new ExactRational[matrix.size()];
            java.util.Arrays.fill(values, ExactRational.ZERO);
            for (int i = 0; i < row.size(); i++) {
                for (int j = 0; j < values.length; j++) {
                    values[j] = work.add(values[j], work.mul(row.get(i), matrix.get(i).get(j)));
                }
            }
            work.charge(values.length);
            result.add(List.of(values));
        }
        return result;
    }

    /** Empty provenance identifies a fresh search after the transfer attempt. */
    public record Application(Discovery discovery, Optional<String> reusedSource) {}

    public static Result<Basis> train(String sourceId, Tensor tensor, int maximumProducts,
                                      IterationLimits limits, BooleanSupplier cancellation) {
        return BilinearProgram.run(limits, cancellation, work -> {
            long labelCells = 1L + sourceId.length();
            work.cells(labelCells);
            work.charge(sourceId.length());
            if (sourceId.isBlank()) throw new IllegalArgumentException("EMPTY_SOURCE_ID");
            var discovery = BilinearFormDiscovery.discover(tensor, maximumProducts, labelCells, work);
            var program = discovery.witness().program();
            long factors = BilinearProgram.matrixCells(program.u()) + BilinearProgram.matrixCells(program.v());
            work.cells(labelCells + BilinearProgram.matrixCells(tensor.outputs())
                + BilinearProgram.matrixCells(discovery.request().leftForms())
                + BilinearProgram.matrixCells(discovery.request().rightForms())
                + factors + BilinearProgram.matrixCells(program.w()) + discovery.lowerBound().retainedCells()
                + 2L * program.u().size());
            work.charge(sourceId.length() + factors);
            return new Basis(sourceId, tensor.leftSize(), tensor.rightSize(), program.u(), program.v());
        });
    }

    /** Trial, equality check, optimality check and any fallback all debit the same per-call budget. */
    public static Result<Application> apply(Basis basis, Tensor tensor, int maximumProducts,
                                            IterationLimits limits, BooleanSupplier cancellation) {
        Objects.requireNonNull(basis);
        return BilinearProgram.run(limits, cancellation, work -> {
            if (maximumProducts < 0) throw new IllegalArgumentException("NEGATIVE_PRODUCT_LIMIT");
            work.cells(basis.retainedCells());
            work.charge(1);
            if (basis.leftSize() == tensor.leftSize() && basis.rightSize() == tensor.rightSize()
                && basis.left().size() <= maximumProducts) {
                var candidate = tryBasis(basis, tensor, maximumProducts, work);
                if (candidate != null) return improveOrReuse(basis, candidate, work);
            }
            return new Application(BilinearFormDiscovery.discover(tensor, maximumProducts, basis.retainedCells(), work),
                Optional.empty());
        });
    }

    private static Application improveOrReuse(Basis basis, Discovery candidate, CertificateWork work) {
        int products = candidate.witness().program().u().size();
        if (products > candidate.lowerBound().lowerBound()) {
            var upper = candidate.witness().program();
            long retained = basis.retainedCells() + candidate.lowerBound().retainedCells()
                + BilinearProgram.matrixCells(upper.u()) + BilinearProgram.matrixCells(upper.v())
                + BilinearProgram.matrixCells(upper.w()) + 2L * products;
            try {
                var cheaper = BilinearFormDiscovery.discover(candidate.request().tensor(), products - 1, retained, work);
                return new Application(cheaper, Optional.empty());
            } catch (CertificateWork.Stop stop) {
                // Exhausting the finite dictionary leaves the earlier upper bound valid. Resource stops propagate.
                if (!"DICTIONARY_EXHAUSTED".equals(stop.getMessage())) throw stop;
            }
        }
        return new Application(candidate, Optional.of(basis.sourceId()));
    }

    private static Discovery tryBasis(Basis basis, Tensor tensor, int maximumProducts, CertificateWork work) {
        long labelCells = 1L + basis.sourceId().length();
        var program = BilinearProgramSearch.fitProducts(tensor, basis.left(), basis.right(), labelCells, work);
        if (program == null) return null;
        long sourceCells = basis.retainedCells() + BilinearProgram.matrixCells(tensor.outputs());
        long programCells = BilinearProgram.matrixCells(program.u()) + BilinearProgram.matrixCells(program.v())
            + BilinearProgram.matrixCells(program.w());
        var bound = BilinearRankBound.derive(tensor, sourceCells + programCells, work);
        work.charge(2L * program.u().size());
        var request = new Request(tensor, basis.left(), basis.right(), maximumProducts);
        var cost = BilinearProgramVerifier.check(request, program, sourceCells + bound.retainedCells(), work);
        return new Discovery(request, new Witness(program, cost), bound);
    }
}
