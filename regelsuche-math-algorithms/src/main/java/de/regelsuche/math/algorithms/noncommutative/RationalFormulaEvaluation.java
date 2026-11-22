package de.regelsuche.math.algorithms.noncommutative;

import de.regelsuche.math.algorithms.noncommutative.RationalFormula.Inverse;
import de.regelsuche.math.algorithms.noncommutative.RationalFormula.Product;
import de.regelsuche.math.algorithms.noncommutative.RationalFormula.Scalar;
import de.regelsuche.math.algorithms.noncommutative.RationalFormula.Sum;
import de.regelsuche.math.algorithms.noncommutative.RationalFormula.Variable;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaRealization.Certificate;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaRealization.Constant;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaRealization.Status;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaRealization.VariableTerm;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaVerifier.Assignment;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaVerifier.Evaluation;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaVerifier.Matrix;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaVerifier.Rejected;
import de.regelsuche.scalar.ExactRational;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static de.regelsuche.scalar.ExactRational.ONE;
import static de.regelsuche.scalar.ExactRational.ZERO;

/** Compares the checked pencil and its domain obligations with a direct tree interpreter. */
final class RationalFormulaEvaluation {
    private final RationalFormula source;
    private final Certificate certificate;
    private final Assignment assignment;
    private final int dimension;
    private final RationalFormulaWork work;
    private int nextOccurrence;

    RationalFormulaEvaluation(RationalFormula source, Certificate certificate, Assignment assignment,
                              RationalFormulaWork work) {
        this.source = source;
        this.certificate = certificate;
        this.assignment = assignment;
        this.work = work;
        dimension = assignment.order();
    }

    Evaluation check() {
        validateAssignment();
        OptionalInt singular = firstSingularObligation();
        List<ExactRational> direct;
        try {
            direct = interpret(source, 1);
        } catch (Undefined undefined) {
            if (singular.orElse(-1) != undefined.occurrence) {
                throw new Rejected(Status.INVALID_CERTIFICATE, "DOMAIN_EVALUATION_MISMATCH");
            }
            work.ledger.charge(0);
            return new Evaluation(Status.UNDEFINED, source, assignment, Optional.empty(), singular,
                "SINGULAR_INVERSE_OPERAND", work.ledger.used());
        }
        if (singular.isPresent()) throw new Rejected(Status.INVALID_CERTIFICATE, "DOMAIN_EVALUATION_MISMATCH");

        ExactRational[] viaPencil = projectRoot();
        for (int i = 0; i < viaPencil.length; i++) {
            if (!work.ledger.check(viaPencil[i]).equals(work.ledger.check(direct.get(i)))) {
                throw new Rejected(Status.INVALID_CERTIFICATE, "PENCIL_VALUE_MISMATCH");
            }
        }
        work.reserve(viaPencil.length);
        var matrix = new Matrix(dimension, Arrays.asList(viaPencil));
        work.ledger.charge(0);
        return new Evaluation(Status.DEFINED, source, assignment, Optional.of(matrix), OptionalInt.empty(), "",
            work.ledger.used());
    }

    private void validateAssignment() {
        long square = (long) dimension * dimension;
        work.ledger.cells(square);
        work.ledger.cells(square * assignment.variables().size());
        for (Matrix value : assignment.variables().values()) {
            work.ledger.charge(1);
            if (value.order() != dimension) throw new Rejected(Status.INVALID_ASSIGNMENT, "MATRIX_ORDER");
            for (ExactRational scalar : value.entries()) work.ledger.check(scalar);
        }
        for (var entry : certificate.pencil().entries()) {
            work.ledger.charge(1);
            if (entry instanceof VariableTerm variable && matrixFor(variable.variableId()) == null) {
                throw new Rejected(Status.INVALID_ASSIGNMENT, "MISSING_VARIABLE");
            }
        }
    }

    private OptionalInt firstSingularObligation() {
        for (var obligation : certificate.obligations()) {
            int order = obligation.order() * dimension;
            ExactRational[] data = pencilSystem(obligation.offset(), obligation.order(), 0);
            if (!reduce(data, order, order)) return OptionalInt.of(obligation.occurrence());
        }
        return OptionalInt.empty();
    }

    private ExactRational[] projectRoot() {
        int blockOrder = certificate.pencil().order();
        int order = blockOrder * dimension;
        int width = order + dimension;
        ExactRational[] data = pencilSystem(0, blockOrder, dimension);
        for (int block = 0; block < blockOrder; block++) {
            for (int index = 0; index < dimension; index++) {
                work.ledger.charge(1);
                data[(block * dimension + index) * width + order + index] = certificate.v().get(block);
            }
        }
        if (!reduce(data, order, width)) throw new Rejected(Status.INVALID_CERTIFICATE, "SINGULAR_ROOT_PENCIL");

        ExactRational[] value = table(dimension, dimension);
        for (int block = 0; block < blockOrder; block++) {
            work.ledger.charge(1);
            ExactRational selector = certificate.u().get(block);
            if (selector.isZero()) continue;
            for (int row = 0; row < dimension; row++) {
                for (int column = 0; column < dimension; column++) {
                    int index = row * dimension + column;
                    ExactRational solved = data[(block * dimension + row) * width + order + column];
                    value[index] = work.ledger.add(value[index], work.ledger.mul(selector, solved));
                }
            }
        }
        return value;
    }

    private ExactRational[] pencilSystem(int offset, int blockOrder, int rightColumns) {
        int order = blockOrder * dimension;
        int width = order + rightColumns;
        ExactRational[] data = table(order, width);
        for (int row = 0; row < blockOrder; row++) {
            for (int column = 0; column < blockOrder; column++) {
                work.ledger.charge(1);
                int top = row * dimension;
                int left = column * dimension;
                switch (certificate.pencil().entry(offset + row, offset + column)) {
                    case Constant constant -> {
                        if (!constant.value().isZero()) {
                            work.ledger.charge(dimension);
                            for (int i = 0; i < dimension; i++) {
                                data[(top + i) * width + left + i] = constant.value();
                            }
                        }
                    }
                    case VariableTerm variable -> {
                        var entries = matrixFor(variable.variableId()).entries();
                        for (int i = 0; i < dimension; i++) {
                            for (int j = 0; j < dimension; j++) {
                                data[(top + i) * width + left + j] =
                                    work.ledger.mul(variable.coefficient(), entries.get(i * dimension + j));
                            }
                        }
                    }
                }
            }
        }
        return data;
    }

    private Matrix matrixFor(int variable) {
        // The immutable assignment is backed by a balanced tree; reserve its comparison bound.
        int levels = Integer.SIZE - Integer.numberOfLeadingZeros(assignment.variables().size());
        work.ledger.charge(1L + 2L * levels);
        return assignment.variables().get(variable);
    }

    /** Interprets the source without consulting the pencil, selectors or obligations. */
    private List<ExactRational> interpret(RationalFormula formula, int depth) {
        work.enter(depth);
        List<ExactRational> result = switch (formula) {
            case Scalar scalar -> scalarMatrix(scalar.value());
            case Variable variable -> matrixFor(variable.id()).entries();
            case Sum sum -> combine(interpret(sum.left(), depth + 1), interpret(sum.right(), depth + 1), false);
            case Product product -> combine(interpret(product.left(), depth + 1), interpret(product.right(), depth + 1),
                true);
            case Inverse inverse -> inverse(interpret(inverse.operand(), depth + 1));
        };
        nextOccurrence++;
        return result;
    }

    private List<ExactRational> scalarMatrix(ExactRational scalar) {
        ExactRational[] matrix = table(dimension, dimension);
        for (int i = 0; i < dimension; i++) {
            work.ledger.charge(1);
            matrix[i * dimension + i] = scalar;
        }
        return Arrays.asList(matrix);
    }

    private List<ExactRational> combine(List<ExactRational> left, List<ExactRational> right, boolean product) {
        ExactRational[] value = table(dimension, dimension);
        if (!product) {
            for (int i = 0; i < value.length; i++) value[i] = work.ledger.add(left.get(i), right.get(i));
            return Arrays.asList(value);
        }
        for (int row = 0; row < dimension; row++) {
            for (int column = 0; column < dimension; column++) {
                ExactRational sum = ZERO;
                for (int i = 0; i < dimension; i++) {
                    sum = work.ledger.add(sum, work.ledger.mul(left.get(row * dimension + i),
                        right.get(i * dimension + column)));
                }
                value[row * dimension + column] = sum;
            }
        }
        return Arrays.asList(value);
    }

    private List<ExactRational> inverse(List<ExactRational> operand) {
        int width = 2 * dimension;
        ExactRational[] data = table(dimension, width);
        for (int row = 0; row < dimension; row++) {
            for (int column = 0; column < dimension; column++) {
                work.ledger.charge(1);
                data[row * width + column] = operand.get(row * dimension + column);
            }
            data[row * width + dimension + row] = ONE;
        }
        if (!reduce(data, dimension, width)) throw new Undefined(nextOccurrence);
        ExactRational[] value = table(dimension, dimension);
        for (int row = 0; row < dimension; row++) {
            work.ledger.charge(dimension);
            System.arraycopy(data, row * width + dimension, value, row * dimension, dimension);
        }
        return Arrays.asList(value);
    }

    /** Exact Gauss-Jordan elimination, with optional trailing right hand sides. */
    private boolean reduce(ExactRational[] data, int order, int width) {
        for (int pivot = 0; pivot < order; pivot++) {
            int pivotRow = pivot;
            while (pivotRow < order && data[pivotRow * width + pivot].isZero()) {
                work.ledger.charge(1);
                pivotRow++;
            }
            if (pivotRow == order) return false;
            int start = pivot * width;
            if (pivotRow != pivot) {
                int swap = pivotRow * width;
                for (int column = pivot; column < width; column++) {
                    work.ledger.charge(1);
                    ExactRational saved = data[start + column];
                    data[start + column] = data[swap + column];
                    data[swap + column] = saved;
                }
            }
            ExactRational scale = data[start + pivot];
            for (int column = pivot + 1; column < width; column++) {
                data[start + column] = work.ledger.div(data[start + column], scale);
            }
            data[start + pivot] = ONE;
            for (int row = 0; row < order; row++) {
                work.ledger.charge(1);
                int rowStart = row * width;
                ExactRational factor = data[rowStart + pivot];
                if (row == pivot || factor.isZero()) continue;
                for (int column = pivot + 1; column < width; column++) {
                    data[rowStart + column] = work.ledger.sub(data[rowStart + column],
                        work.ledger.mul(factor, data[start + column]));
                }
                data[rowStart + pivot] = ZERO;
            }
        }
        return true;
    }

    private ExactRational[] table(int rows, int columns) {
        work.reserve((long) rows * columns);
        ExactRational[] entries = new ExactRational[rows * columns];
        Arrays.fill(entries, ZERO);
        return entries;
    }

    private static final class Undefined extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int occurrence;

        Undefined(int occurrence) {
            super(null, null, false, false);
            this.occurrence = occurrence;
        }
    }
}
