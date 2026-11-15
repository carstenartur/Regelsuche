package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Transition;
import de.regelsuche.scalar.ExactRational;

import java.util.ArrayList;
import java.util.List;

import static de.regelsuche.math.algorithms.oracle.StateChoiceOraclePanel.array;

final class RecurrenceExperimentTrajectories {
    private RecurrenceExperimentTrajectories() {}

    static List<Rational> vector(Object raw) {
        return array(raw).stream().map(v -> Rational.fromExact(ExactRational.parse((String) v))).toList();
    }

    static List<Rational> advance(Transition transition, List<Rational> state) {
        var next = new ArrayList<Rational>();
        for (int i = 0; i < state.size(); i++) {
            var value = transition.offset().get(i);
            for (int j = 0; j < state.size(); j++) {
                value = value.add(transition.linear().get(i, j).multiply(state.get(j)));
            }
            next.add(value);
        }
        return List.copyOf(next);
    }

    static Rational evaluate(Polynomial polynomial, List<Rational> state) {
        Rational sum = Rational.ZERO;
        for (var term : polynomial.terms().entrySet()) {
            var value = term.getValue();
            for (int i = 0; i < state.size(); i++) {
                int exponent = term.getKey().exponentOf("x" + i);
                for (int j = 0; j < exponent; j++) value = value.multiply(state.get(i));
            }
            sum = sum.add(value);
        }
        return sum;
    }
}
