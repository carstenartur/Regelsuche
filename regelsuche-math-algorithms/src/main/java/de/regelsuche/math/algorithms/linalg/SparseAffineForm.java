package de.regelsuche.math.algorithms.linalg;

import static de.regelsuche.math.algorithms.linalg.RecurrenceObservationArithmetic.add;
import static de.regelsuche.math.algorithms.linalg.RecurrenceObservationArithmetic.multiply;

import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSupport.Work;
import java.util.ArrayList;
import java.util.List;

/** A sparse linear combination with a constant term, compiled once for repeated evaluation. */
record SparseAffineForm(List<Term> terms, Rational offset) {
    static SparseAffineForm compile(List<Rational> weights, Rational offset, Work work) {
        work.add(1L + weights.size());
        var terms = new ArrayList<Term>();
        for (int i = 0; i < weights.size(); i++) {
            var weight = weights.get(i);
            if (!weight.isZero()) terms.add(new Term(i, weight));
        }
        work.add(terms.size());
        return new SparseAffineForm(List.copyOf(terms), offset);
    }

    Rational evaluate(List<Rational> state, Work work) {
        work.add(1);
        var result = offset;
        for (var term : terms) {
            work.add(1);
            var value = state.get(term.coordinate());
            if (value.isZero()) continue;
            if (term.weight().isNegativeOne()) {
                work.add(1);
                value = value.negate();
            } else if (!term.weight().isOne()) {
                value = multiply(term.weight(), value, work);
            }
            result = result.isZero() ? value : add(result, value, work);
        }
        return result;
    }

    static List<Rational> evaluateAll(List<SparseAffineForm> forms, List<Rational> state, Work work) {
        work.add(1L + forms.size());
        var values = new ArrayList<Rational>(forms.size());
        for (var form : forms) values.add(form.evaluate(state, work));
        return List.copyOf(values);
    }

    record Term(int coordinate, Rational weight) { }

    /** Prepares only the changed rows and evaluates them against the same prior state. */
    record Update(List<Assignment> changes, boolean affectsOutputs) {
        static Update compile(List<SparseAffineForm> rows, List<SparseAffineForm> outputs, Work work) {
            work.add(1L + 2L * rows.size());
            var changed = new boolean[rows.size()];
            var changes = new ArrayList<Assignment>();
            for (int row = 0; row < rows.size(); row++) {
                var form = rows.get(row);
                if (form.offset().isZero() && form.terms().size() == 1) {
                    var term = form.terms().getFirst();
                    if (term.coordinate() == row && term.weight().isOne()) continue;
                }
                changed[row] = true;
                changes.add(new Assignment(row, form));
            }
            work.add(changes.size());
            return new Update(List.copyOf(changes), affectsOutputs(outputs, changed, work));
        }

        private static boolean affectsOutputs(List<SparseAffineForm> outputs, boolean[] changed, Work work) {
            for (var output : outputs) {
                work.add(1);
                for (var term : output.terms()) {
                    work.add(1);
                    if (changed[term.coordinate()]) return true;
                }
            }
            return false;
        }

        void apply(List<Rational> state, Work work) {
            work.add(1);
            if (changes.isEmpty()) return;
            if (changes.size() == 1) {
                var change = changes.getFirst();
                work.add(1);
                state.set(change.coordinate(), change.form().evaluate(state, work));
                return;
            }
            work.add(1L + 2L * changes.size());
            var next = new ArrayList<Rational>(changes.size());
            for (var change : changes) next.add(change.form().evaluate(state, work));
            for (int i = 0; i < changes.size(); i++) state.set(changes.get(i).coordinate(), next.get(i));
        }
    }

    record Assignment(int coordinate, SparseAffineForm form) { }
}
