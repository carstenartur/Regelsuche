package de.regelsuche.math.algorithms.algebra;

import de.regelsuche.math.algorithms.algebra.BilinearProgram.Rejection;
import de.regelsuche.math.algorithms.algebra.BilinearProgram.Request;
import de.regelsuche.math.algorithms.algebra.BilinearProgram.Result;
import de.regelsuche.math.algorithms.algebra.BilinearProgram.Tensor;
import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.scalar.ExactRational;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Lower bounds on the number of rational bilinear products. Every product contributes an
 * outer product of three vectors, hence at most one matrix rank on each of the three cuts.
 * A nonzero minor of order r therefore rules out every decomposition with fewer than r
 * products, including decompositions outside the search dictionary.
 */
public final class BilinearRankBound {
    private BilinearRankBound() {}

    /** The remaining axes become columns, ordered as in the source (output, left, right). */
    public enum Axis { OUTPUT, LEFT, RIGHT }

    /** Indices are zero-based. Their order fixes the sign of the determinant. */
    public record Certificate(Tensor source, Axis axis, List<Integer> rows,
                              List<Integer> columns, ExactRational determinant) {
        public Certificate {
            Objects.requireNonNull(source);
            Objects.requireNonNull(axis);
            rows = List.copyOf(rows);
            columns = List.copyOf(columns);
            Objects.requireNonNull(determinant);
        }

        public int lowerBound() { return rows.size(); }

        /** Additional retained cells beyond the source tensor. */
        public long retainedCells() { return 5L + rows.size() + columns.size(); }
    }

    public static Result<Certificate> compute(Tensor tensor, IterationLimits limits, BooleanSupplier cancellation) {
        return BilinearProgram.run(limits, cancellation,
            work -> derive(tensor, checkSource(tensor, work), work));
    }

    public static Result<Integer> verify(Tensor tensor, Certificate certificate,
                                         IterationLimits limits, BooleanSupplier cancellation) {
        return BilinearProgram.run(limits, cancellation,
            work -> verifyMinor(tensor, certificate, checkSource(tensor, work), work));
    }

    /** Bounds on the minimum number of variable products over all rational bilinear programs. */
    public record Interval(int lowerBound, int upperBound) {
        public Interval {
            if (lowerBound < 0 || lowerBound > upperBound) throw new IllegalArgumentException("INVALID_PRODUCT_INTERVAL");
        }
        public boolean productOptimal() { return lowerBound == upperBound; }
    }

    /** Replay both certificates from their source before asserting anything about optimality. */
    public static Result<Interval> certify(Request request, BilinearProgram program, Certificate certificate,
                                           IterationLimits limits, BooleanSupplier cancellation) {
        return BilinearProgram.run(limits, cancellation, work -> {
            long sourceCells = request.checkInputs(work);
            var tensor = request.tensor();
            long boundCells = certificate.retainedCells();
            if (tensor != certificate.source()) boundCells += BilinearProgram.matrixCells(certificate.source().outputs());
            var cost = BilinearProgramVerifier.check(request, program, sourceCells + boundCells, work);
            long programCells = BilinearProgram.matrixCells(program.u()) + BilinearProgram.matrixCells(program.v())
                + BilinearProgram.matrixCells(program.w());
            int lower = verifyMinor(tensor, certificate, sourceCells + programCells, work);
            return new Interval(lower, Math.toIntExact(cost.variableMultiplications()));
        });
    }

    /** Coefficients of det(s A + t B) and the discriminant b^2 - 4ac over Q. */
    public record Pencil(ExactRational a, ExactRational b, ExactRational c, ExactRational discriminant) {
        public Pencil {
            Objects.requireNonNull(a);
            Objects.requireNonNull(b);
            Objects.requireNonNull(c);
            Objects.requireNonNull(discriminant);
        }
    }

    /** Extends the original flattening certificate only when the output pencil proves an additional bound. */
    public record RationalCertificate(Certificate minor, Optional<Pencil> pencil) {
        public RationalCertificate {
            Objects.requireNonNull(minor);
            Objects.requireNonNull(pencil);
        }
        public int lowerBound() { return pencil.isPresent() ? Math.max(3, minor.lowerBound()) : minor.lowerBound(); }
        long extraCells() { return pencil.isPresent() ? 8 : 3; }
    }

    public static Result<RationalCertificate> computeRational(Tensor tensor, IterationLimits limits,
            BooleanSupplier cancellation) {
        return BilinearProgram.run(limits, cancellation, work -> {
            long source = checkSource(tensor, work);
            var minor = derive(tensor, source, work);
            long retained = source + minor.retainedCells() + 8;
            work.cells(retained);
            if (!isPencil(tensor)) return new RationalCertificate(minor, Optional.empty());
            var first = tensor.outputs().get(0);
            var second = tensor.outputs().get(1);
            var a = det2(first, work);
            var c = det2(second, work);
            var b = work.sub(work.add(work.mul(first.get(0), second.get(3)), work.mul(second.get(0), first.get(3))),
                work.add(work.mul(first.get(1), second.get(2)), work.mul(second.get(1), first.get(2))));
            var p = new Pencil(a, b, c, discriminant(a, b, c, work));
            if (!nonsquare(p.discriminant(), retained, work)) return new RationalCertificate(minor, Optional.empty());
            verifyPencil(tensor, p, retained, work);
            return new RationalCertificate(minor, Optional.of(p));
        });
    }

    public static Result<Integer> verifyRational(Tensor tensor, RationalCertificate proof, IterationLimits limits,
            BooleanSupplier cancellation) {
        return BilinearProgram.run(limits, cancellation, work ->
            verifyRational(tensor, proof, checkSource(tensor, work), work));
    }

    public static Result<Interval> certifyRational(Request request, BilinearProgram program, RationalCertificate proof,
            IterationLimits limits, BooleanSupplier cancellation) {
        return BilinearProgram.run(limits, cancellation, work -> {
            long source = request.checkInputs(work);
            long programCells = BilinearProgram.matrixCells(program.u()) + BilinearProgram.matrixCells(program.v())
                + BilinearProgram.matrixCells(program.w());
            int lower = verifyRational(request.tensor(), proof, source + programCells, work);
            long retained = source + proof.minor().retainedCells() + proof.extraCells();
            if (request.tensor() != proof.minor().source())
                retained += BilinearProgram.matrixCells(proof.minor().source().outputs());
            var cost = BilinearProgramVerifier.check(request, program, retained, work);
            return new Interval(lower, Math.toIntExact(cost.variableMultiplications()));
        });
    }

    static int verifyRational(Tensor tensor, RationalCertificate proof, long retained, CertificateWork work) {
        retained += proof.extraCells();
        int minor = verifyMinor(tensor, proof.minor(), retained, work);
        if (proof.pencil().isEmpty()) return minor;
        retained += proof.minor().retainedCells();
        if (tensor != proof.minor().source()) retained += BilinearProgram.matrixCells(proof.minor().source().outputs());
        verifyPencil(tensor, proof.pencil().orElseThrow(), retained, work);
        return Math.max(minor, 3);
    }

    private static boolean isPencil(Tensor t) {
        return t.leftSize() == 2 && t.rightSize() == 2 && t.outputs().size() == 2;
    }

    private static ExactRational det2(List<ExactRational> matrix, CertificateWork work) {
        return work.sub(work.mul(matrix.get(0), matrix.get(3)), work.mul(matrix.get(1), matrix.get(2)));
    }

    private static ExactRational discriminant(ExactRational a, ExactRational b, ExactRational c, CertificateWork work) {
        return work.sub(work.mul(b, b), work.mul(ExactRational.integer(4), work.mul(a, c)));
    }

    // Use the existing exact root primitive, reserving limb buffers and a conservative quadratic work tariff.
    private static boolean nonsquare(ExactRational value, long retained, CertificateWork work) {
        work.check(value);
        if (value.signum() < 0) return true;
        long words = 1L + Math.max(value.numerator().bitLength(), value.denominator().bitLength()) / 32;
        work.cells(retained + 12 * words + 16);
        work.charge(2 * words * words);
        return value.sqrtExact().isEmpty();
    }

    // Independent interpolation at (1,0), (0,1), (1,1), using the source slices only.
    private static void verifyPencil(Tensor tensor, Pencil p, long retained, CertificateWork work) {
        if (!isPencil(tensor)) throw new Rejection("PENCIL_SHAPE");
        work.cells(retained + 16);
        var first = tensor.outputs().get(0);
        var second = tensor.outputs().get(1);
        var sum = new ArrayList<ExactRational>(4);
        for (int i = 0; i < 4; i++) sum.add(work.add(first.get(i), second.get(i)));
        var a = det2(first, work);
        var c = det2(second, work);
        var b = work.sub(work.sub(det2(sum, work), a), c);
        var d = discriminant(a, b, c, work);
        if (!a.equals(work.check(p.a())) || !b.equals(work.check(p.b())) || !c.equals(work.check(p.c()))
                || !d.equals(work.check(p.discriminant()))) throw new Rejection("PENCIL_MISMATCH");
        if (!nonsquare(d, retained + 16, work)) throw new Rejection("SPLIT_PENCIL");
    }

    private static long checkSource(Tensor tensor, CertificateWork work) {
        long cells = BilinearProgram.matrixCells(tensor.outputs());
        work.cells(cells);
        BilinearProgram.checkEntries(tensor.outputs(), work);
        return cells;
    }

    /** Caller checked all source entries and includes all already-live data in sourceCells. */
    static Certificate derive(Tensor tensor, long sourceCells, CertificateWork work) {
        var best = new Certificate(tensor, Axis.OUTPUT, List.of(), List.of(), ExactRational.ONE);
        work.cells(sourceCells + best.retainedCells());
        for (Axis axis : Axis.values()) {
            int rows = rowCount(tensor, axis);
            int columns = columnCount(tensor, axis);
            if (Math.min(rows, columns) <= best.lowerBound()) continue;
            var candidate = findMinor(tensor, axis, rows, columns, sourceCells + best.retainedCells(), work);
            if (candidate.lowerBound() > best.lowerBound()) best = candidate;
        }
        verifyMinor(tensor, best, sourceCells, work);
        return best;
    }

    private static int rowCount(Tensor tensor, Axis axis) {
        return switch (axis) {
            case OUTPUT -> tensor.outputs().size();
            case LEFT -> tensor.leftSize();
            case RIGHT -> tensor.rightSize();
        };
    }

    private static int columnCount(Tensor tensor, Axis axis) {
        return switch (axis) {
            case OUTPUT -> tensor.outputs().getFirst().size();
            case LEFT -> Math.multiplyExact(tensor.outputs().size(), tensor.rightSize());
            case RIGHT -> Math.multiplyExact(tensor.outputs().size(), tensor.leftSize());
        };
    }

    private static ExactRational entry(Tensor tensor, Axis axis, int row, int column) {
        return switch (axis) {
            case OUTPUT -> tensor.outputs().get(row).get(column);
            case LEFT -> tensor.outputs().get(column / tensor.rightSize())
                .get(row * tensor.rightSize() + column % tensor.rightSize());
            case RIGHT -> tensor.outputs().get(column / tensor.leftSize())
                .get((column % tensor.leftSize()) * tensor.rightSize() + row);
        };
    }

    // Gaussian row elimination selects an independent minor and keeps original row identities.
    private static Certificate findMinor(Tensor tensor, Axis axis, int rows, int columns,
                                         long retained, CertificateWork work) {
        int capacity = Math.min(rows, columns);
        work.cells(retained + (long) rows * (2L + columns) + 4L * capacity + 6);
        var matrix = new ExactRational[rows][columns];
        var originalRow = new int[rows];
        for (int i = 0; i < rows; i++) {
            work.charge(1L + columns);
            originalRow[i] = i;
            for (int j = 0; j < columns; j++) matrix[i][j] = entry(tensor, axis, i, j);
        }
        var pivotRows = new ArrayList<Integer>(capacity);
        var pivotColumns = new ArrayList<Integer>(capacity);
        var determinant = ExactRational.ONE;
        for (int j = 0; j < columns && pivotRows.size() < rows; j++) {
            int k = pivotRows.size();
            int pivot = k;
            while (pivot < rows && matrix[pivot][j].isZero()) {
                work.charge(1);
                pivot++;
            }
            if (pivot == rows) continue;
            // In the order reported in pivotRows, no determinant sign correction is needed.
            var pivotRow = matrix[pivot];
            matrix[pivot] = matrix[k];
            matrix[k] = pivotRow;
            pivotRows.add(originalRow[pivot]);
            originalRow[pivot] = originalRow[k];
            pivotColumns.add(j);
            determinant = work.mul(determinant, pivotRow[j]);
            for (int i = k + 1; i < rows; i++) {
                work.charge(1);
                if (matrix[i][j].isZero()) continue;
                var factor = work.div(matrix[i][j], pivotRow[j]);
                for (int c = j + 1; c < columns; c++)
                    matrix[i][c] = work.sub(matrix[i][c], work.mul(factor, pivotRow[c]));
            }
        }
        return new Certificate(tensor, axis, pivotRows, pivotColumns, determinant);
    }

    private static int verifyMinor(Tensor tensor, Certificate certificate, long retained, CertificateWork work) {
        int size = certificate.lowerBound();
        Axis axis = certificate.axis();
        if (certificate.columns().size() != size || size > Math.min(rowCount(tensor, axis), columnCount(tensor, axis)))
            throw new Rejection("MINOR_SHAPE");
        retained += certificate.retainedCells();
        retained = checkBoundSource(tensor, certificate.source(), retained, work);
        work.cells(retained + (long) size * (1L + size));
        work.check(certificate.determinant());
        if (certificate.determinant().isZero()) throw new Rejection("ZERO_DETERMINANT");
        for (int i = 0; i < size; i++) {
            work.charge(1);
            int r = certificate.rows().get(i), c = certificate.columns().get(i);
            if (r < 0 || r >= rowCount(tensor, axis) || c < 0 || c >= columnCount(tensor, axis))
                throw new Rejection("MINOR_INDEX");
        }
        var minor = new ExactRational[size][size];
        for (int i = 0; i < size; i++) {
            work.charge(size);
            for (int j = 0; j < size; j++)
                minor[i][j] = entry(tensor, axis, certificate.rows().get(i), certificate.columns().get(j));
        }
        if (!determinant(minor, work).equals(certificate.determinant()))
            throw new Rejection("DETERMINANT_MISMATCH");
        return size;
    }

    private static long checkBoundSource(Tensor expected, Tensor claimed, long retained, CertificateWork work) {
        if (expected == claimed) return retained;
        if (expected.leftSize() != claimed.leftSize() || expected.rightSize() != claimed.rightSize()
            || expected.outputs().size() != claimed.outputs().size()) throw new Rejection("TENSOR_MISMATCH");
        retained += BilinearProgram.matrixCells(claimed.outputs());
        work.cells(retained);
        for (int k = 0; k < expected.outputs().size(); k++) {
            var expectedRow = expected.outputs().get(k);
            var claimedRow = claimed.outputs().get(k);
            for (int i = 0; i < expectedRow.size(); i++)
                if (!expectedRow.get(i).equals(work.check(claimedRow.get(i)))) throw new Rejection("TENSOR_MISMATCH");
        }
        return retained;
    }

    // Replay only the claimed square minor from its original entries, using Bareiss elimination.
    static ExactRational determinant(ExactRational[][] minor, CertificateWork work) {
        int size = minor.length;
        if (size == 0) return ExactRational.ONE;
        var previous = ExactRational.ONE;
        boolean negative = false;
        for (int k = 0; k < size - 1; k++) {
            int pivot = k;
            while (pivot < size && minor[pivot][k].isZero()) {
                work.charge(1);
                pivot++;
            }
            if (pivot == size) return ExactRational.ZERO;
            if (pivot != k) {
                var swap = minor[k];
                minor[k] = minor[pivot];
                minor[pivot] = swap;
                negative = !negative;
            }
            for (int i = k + 1; i < size; i++) {
                for (int j = k + 1; j < size; j++) {
                    var value = work.sub(work.mul(minor[k][k], minor[i][j]), work.mul(minor[i][k], minor[k][j]));
                    minor[i][j] = work.div(value, previous);
                }
            }
            previous = minor[k][k];
        }
        var value = minor[size - 1][size - 1];
        return negative ? work.sub(ExactRational.ZERO, value) : value;
    }
}
