package de.regelsuche.math.algorithms.noncommutative;

import de.regelsuche.math.algorithms.noncommutative.RationalFormulaComparison.Binding;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaRealization.Certificate;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaRealization.Constant;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaRealization.VariableTerm;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaVerifier.Assignment;
import de.regelsuche.scalar.ExactRational;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static de.regelsuche.scalar.ExactRational.ONE;
import static de.regelsuche.scalar.ExactRational.ZERO;

/** L(C) and every L_i tensor E_ab, in (pencil coordinate, center coordinate) order. */
final class MatrixCenteredDifferencePencil {
    private record Term(int row, int column, ExactRational coefficient) {}
    final int centerOrder;
    final int order;
    private final int pencilOrder;
    private final ExactRational[] center;
    private final ExactRational[] u;
    private final ExactRational[] v;
    private final Map<Integer, List<Term>> coefficients = new TreeMap<>();
    private final RationalFormulaWork work;

    MatrixCenteredDifferencePencil(Binding binding, RationalFormulaWork work) {
        this.work = work;
        centerOrder = binding.center().order();
        long originalOrder = (long) binding.left().pencil().order() + binding.right().pencil().order();
        work.ledger.cells(originalOrder);
        pencilOrder = (int) originalOrder;
        long expandedOrder = originalOrder * centerOrder;
        work.ledger.cells(expandedOrder);
        order = (int) expandedOrder;
        work.reserve((long) order * order + 2L * pencilOrder);
        center = new ExactRational[order * order];
        Arrays.fill(center, ZERO);
        u = new ExactRational[pencilOrder];
        v = new ExactRational[pencilOrder];
        copy(binding.left(), binding.center(), 0, false);
        copy(binding.right(), binding.center(), binding.left().pencil().order(), true);
    }

    private void copy(Certificate operand, Assignment assignment, int offset, boolean subtract) {
        int size = operand.pencil().order();
        for (int row = 0; row < size; row++) {
            u[offset + row] = subtract ? operand.u().get(row).negate() : operand.u().get(row);
            v[offset + row] = operand.v().get(row);
            for (int column = 0; column < size; column++) {
                work.ledger.charge(1);
                int top = (offset + row) * centerOrder, left = (offset + column) * centerOrder;
                switch (operand.pencil().entry(row, column)) {
                    case Constant constant -> {
                        work.ledger.charge(centerOrder);
                        for (int a = 0; a < centerOrder; a++)
                            center[(top + a) * order + left + a] = constant.value();
                    }
                    case VariableTerm term -> {
                        work.reserve(4);
                        int levels = Integer.SIZE - Integer.numberOfLeadingZeros(assignment.variables().size());
                        work.ledger.charge(2L + 4L * levels);
                        coefficients.computeIfAbsent(term.variableId(), id -> new ArrayList<>())
                            .add(new Term(offset + row, offset + column, term.coefficient()));
                        var value = assignment.variables().get(term.variableId()).entries();
                        for (int a = 0; a < centerOrder; a++) for (int b = 0; b < centerOrder; b++) {
                            center[(top + a) * order + left + b] =
                                work.ledger.mul(term.coefficient(), value.get(a * centerOrder + b));
                        }
                    }
                }
            }
        }
    }

    Iterable<Integer> variables() { return coefficients.keySet(); }

    ExactRational[] input(int column) {
        work.reserve(order);
        var result = new ExactRational[order];
        Arrays.fill(result, ZERO);
        for (int row = 0; row < pencilOrder; row++) {
            work.ledger.charge(1);
            result[row * centerOrder + column] = v[row];
        }
        return result;
    }

    ExactRational[] inverseCenter() {
        int width = 2 * order;
        work.reserve((long) order * width);
        var data = new ExactRational[order * width];
        Arrays.fill(data, ZERO);
        for (int row = 0; row < order; row++) {
            work.ledger.charge(order);
            System.arraycopy(center, row * order, data, row * width, order);
            data[row * width + order + row] = ONE;
        }
        if (!RationalFormulaEvaluation.reduce(data, order, width, work))
            throw new IllegalStateException("DEFINED_CENTER_HAS_SINGULAR_PENCIL");
        work.reserve((long) order * order);
        var inverse = new ExactRational[order * order];
        for (int row = 0; row < order; row++) {
            work.ledger.charge(order);
            System.arraycopy(data, row * width + order, inverse, row * order, order);
        }
        return inverse;
    }

    ExactRational[] applyCenter(ExactRational[] column) { return apply(center, column); }

    ExactRational[] apply(ExactRational[] matrix, ExactRational[] column) {
        work.reserve(order);
        var result = new ExactRational[order];
        for (int row = 0; row < order; row++) {
            var sum = ZERO;
            for (int index = 0; index < order; index++) {
                work.ledger.charge(1);
                var coefficient = matrix[row * order + index];
                if (!coefficient.isZero() && !column[index].isZero())
                    sum = work.ledger.add(sum, work.ledger.mul(coefficient, column[index]));
            }
            result[row] = sum;
        }
        return result;
    }

    /** Apply -(L_i tensor E_ab) without materializing any of the m^2 coefficient matrices. */
    ExactRational[] negativeCoefficient(int variable, int a, int b, ExactRational[] column) {
        work.reserve(order);
        var result = new ExactRational[order];
        Arrays.fill(result, ZERO);
        for (var term : coefficients.get(variable)) {
            int row = term.row() * centerOrder + a;
            var value = column[term.column() * centerOrder + b];
            result[row] = work.ledger.sub(result[row], work.ledger.mul(term.coefficient(), value));
        }
        return result;
    }

    /** Every one of the m output rows must vanish. */
    boolean outputIsZero(ExactRational[] column) {
        for (int a = 0; a < centerOrder; a++) {
            var sum = ZERO;
            for (int row = 0; row < pencilOrder; row++) {
                work.ledger.charge(1);
                if (!u[row].isZero() && !column[row * centerOrder + a].isZero())
                    sum = work.ledger.add(sum, work.ledger.mul(u[row], column[row * centerOrder + a]));
            }
            if (!sum.isZero()) return false;
        }
        return true;
    }
}
