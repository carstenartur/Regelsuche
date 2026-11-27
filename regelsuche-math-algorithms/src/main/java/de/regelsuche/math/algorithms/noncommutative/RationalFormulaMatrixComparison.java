package de.regelsuche.math.algorithms.noncommutative;

import de.regelsuche.math.algorithms.noncommutative.RationalFormulaComparison.Binding;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaComparison.Evidence;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaComparison.Invariant;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaComparison.Span;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaComparison.Witness;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaVerifier.Assignment;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaVerifier.Matrix;
import de.regelsuche.scalar.ExactRational;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

import static de.regelsuche.scalar.ExactRational.ONE;
import static de.regelsuche.scalar.ExactRational.ZERO;

/** Matrix-center reachability; certificates are checked in unnormalized source coordinates. */
final class RationalFormulaMatrixComparison {
    private RationalFormulaMatrixComparison() {}

    /** Applying a coefficient prepends its matrix-unit letter to the parent's word. */
    private record Path(int variable, int row, int column, Path suffix, int length) {}
    private record Reachable(ExactRational[] column, Path path) {}

    static Evidence search(Binding binding, RationalFormulaWork work) {
        var pencil = new MatrixCenteredDifferencePencil(binding, work);
        var inverse = pencil.inverseCenter();
        var span = new Span(pencil.order, work);
        var queue = new ArrayDeque<Reachable>();
        var columns = new ArrayList<List<ExactRational>>();
        for (int input = 0; input < pencil.centerOrder; input++) {
            var initial = pencil.apply(inverse, pencil.input(input));
            if (span.insert(initial)) {
                work.reserve(2);
                queue.add(new Reachable(initial, null));
            }
        }
        while (!queue.isEmpty()) {
            var state = queue.remove();
            if (!pencil.outputIsZero(state.column()))
                return new Witness(binding, point(binding.center(), state.path(), work));
            work.reserve(pencil.order + 1L);
            columns.add(List.of(state.column()));
            for (int variable : pencil.variables()) {
                for (int a = 0; a < pencil.centerOrder; a++) for (int b = 0; b < pencil.centerOrder; b++) {
                    var next = pencil.apply(inverse, pencil.negativeCoefficient(variable, a, b, state.column()));
                    if (span.insert(next)) {
                        work.reserve(8);
                        var path = new Path(variable, a, b, state.path(), state.path() == null ? 1 : state.path().length() + 1);
                        queue.add(new Reachable(next, path));
                    }
                }
            }
        }
        work.reserve((long) columns.size() * (pencil.order + 1));
        return new Invariant(binding, columns);
    }

    /**
     * Rebuild K, U, V and the full alphabet from the checked sources. No search inverse is used.
     * im(V) subset im(KB), D_t im(B) subset im(KB), and UB=0 imply invariance at every
     * ampliation of this center. Direct sums of m copies transfer equality to arbitrary sizes.
     */
    static boolean verify(Binding binding, List<List<ExactRational>> columns, RationalFormulaWork work) {
        var pencil = new MatrixCenteredDifferencePencil(binding, work);
        if (columns.size() > pencil.order) return false;
        var images = new Span(pencil.order, work);
        for (var column : columns) {
            if (column.size() != pencil.order) return false;
            work.reserve(column.size());
            var values = column.toArray(ExactRational[]::new);
            for (var value : values) work.ledger.check(value);
            if (!pencil.outputIsZero(values)) return false;
            images.insert(pencil.applyCenter(values));
        }
        for (int input = 0; input < pencil.centerOrder; input++)
            if (!images.contains(pencil.input(input))) return false;
        for (var column : columns) {
            work.reserve(column.size());
            var values = column.toArray(ExactRational[]::new);
            for (int variable : pencil.variables()) {
                for (int a = 0; a < pencil.centerOrder; a++) for (int b = 0; b < pencil.centerOrder; b++) {
                    if (!images.contains(pencil.negativeCoefficient(variable, a, b, values))) return false;
                }
            }
        }
        return true;
    }

    /** Repeated center diagonal blocks keep every original inverse defined; replay checks the result. */
    private static Assignment point(Assignment center, Path word, RationalFormulaWork work) {
        if (word == null) return center;
        int blocks = word.length() + 1;
        long expandedOrder = (long) center.order() * blocks;
        work.ledger.cells(expandedOrder);
        int size = (int) expandedOrder;
        var variables = new TreeMap<Integer, Matrix>();
        for (var entry : center.variables().entrySet()) {
            work.reserve(2L * size * size + 2);
            var values = new ExactRational[size * size];
            Arrays.fill(values, ZERO);
            for (int block = 0; block < blocks; block++) {
                for (int a = 0; a < center.order(); a++) for (int b = 0; b < center.order(); b++) {
                    work.ledger.charge(1);
                    values[(block * center.order() + a) * size + block * center.order() + b] =
                        entry.getValue().entries().get(a * center.order() + b);
                }
            }
            int block = 0;
            for (var path = word; path != null; path = path.suffix(), block++) {
                work.ledger.charge(1);
                if (path.variable() == entry.getKey())
                    values[(block * center.order() + path.row()) * size
                        + (block + 1) * center.order() + path.column()] = ONE;
            }
            work.ledger.charge(1L + Integer.SIZE - Integer.numberOfLeadingZeros(variables.size() + 1));
            variables.put(entry.getKey(), new Matrix(size, Arrays.asList(values)));
        }
        return new Assignment(size, variables);
    }
}
