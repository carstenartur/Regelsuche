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

/** Direct sum of the two checked operand pencils, with output selector (u_left, -u_right). */
final class CenteredDifferencePencil {
    record Term(int row, int column, ExactRational coefficient) {}
    final int order;
    final ExactRational[] center;
    final ExactRational[] v;
    final Map<Integer, List<Term>> coefficients = new TreeMap<>();
    private final ExactRational[] u;
    private final RationalFormulaWork work;

    CenteredDifferencePencil(Binding binding, RationalFormulaWork work) {
        this.work = work;
        order = binding.left().pencil().order() + binding.right().pencil().order();
        work.reserve((long) order * order + 2L * order);
        center = new ExactRational[order * order];
        Arrays.fill(center, ZERO);
        u = new ExactRational[order];
        v = new ExactRational[order];
        copy(binding.left(), binding.center(), 0, false);
        copy(binding.right(), binding.center(), binding.left().pencil().order(), true);
    }

    private void copy(Certificate operand, Assignment assignment, int offset, boolean subtract) {
        int n = operand.pencil().order();
        for (int i = 0; i < n; i++) {
            u[offset + i] = subtract ? operand.u().get(i).negate() : operand.u().get(i);
            v[offset + i] = operand.v().get(i);
            for (int j = 0; j < n; j++) {
                work.ledger.charge(1);
                int row = offset + i, column = offset + j;
                center[row * order + column] = switch (operand.pencil().entry(i, j)) {
                    case Constant constant -> constant.value();
                    case VariableTerm term -> {
                        work.reserve(4);
                        int levels = Integer.SIZE - Integer.numberOfLeadingZeros(assignment.variables().size());
                        work.ledger.charge(2L + 4L * levels);
                        var list = coefficients.computeIfAbsent(term.variableId(), id -> new ArrayList<>());
                        list.add(new Term(row, column, term.coefficient()));
                        yield work.ledger.mul(term.coefficient(), assignment.variables().get(term.variableId()).entries().getFirst());
                    }
                };
            }
        }
    }

    ExactRational[] inverseCenter() {
        int width = 2 * order;
        work.reserve((long) order * width);
        var data = new ExactRational[order * width];
        Arrays.fill(data, ZERO);
        for (int i = 0; i < order; i++) {
            work.ledger.charge(order);
            System.arraycopy(center, i * order, data, i * width, order);
            data[i * width + order + i] = ONE;
        }
        if (!RationalFormulaEvaluation.reduce(data, order, width, work)) {
            throw new IllegalStateException("DEFINED_CENTER_HAS_SINGULAR_PENCIL");
        }
        work.reserve((long) order * order);
        var inverse = new ExactRational[order * order];
        for (int i = 0; i < order; i++)
            System.arraycopy(data, i * width + order, inverse, i * order, order);
        return inverse;
    }

    ExactRational[] apply(ExactRational[] matrix, ExactRational[] column) {
        work.reserve(order);
        var result = new ExactRational[order];
        for (int i = 0; i < order; i++) {
            var sum = ZERO;
            for (int j = 0; j < order; j++) {
                work.ledger.charge(1);
                var coefficient = matrix[i * order + j];
                if (!coefficient.isZero() && !column[j].isZero())
                    sum = work.ledger.add(sum, work.ledger.mul(coefficient, column[j]));
            }
            result[i] = sum;
        }
        return result;
    }

    ExactRational[] negativeCoefficient(int variable, ExactRational[] column) {
        work.reserve(order);
        var result = new ExactRational[order];
        Arrays.fill(result, ZERO);
        for (var term : coefficients.get(variable)) {
            result[term.row()] = work.ledger.sub(result[term.row()], work.ledger.mul(term.coefficient(), column[term.column()]));
        }
        return result;
    }

    ExactRational output(ExactRational[] column) {
        var sum = ZERO;
        for (int i = 0; i < order; i++) sum = work.ledger.add(sum, work.ledger.mul(u[i], column[i]));
        return sum;
    }
}
