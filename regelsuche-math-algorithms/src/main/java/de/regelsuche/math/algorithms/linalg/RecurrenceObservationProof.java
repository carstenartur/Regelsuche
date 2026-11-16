package de.regelsuche.math.algorithms.linalg;

import static de.regelsuche.math.algorithms.linalg.RecurrenceObservationArithmetic.add;
import static de.regelsuche.math.algorithms.linalg.RecurrenceObservationArithmetic.addProduct;
import static de.regelsuche.math.algorithms.linalg.RecurrenceObservationArithmetic.multiply;

import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSupport.Work;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Transition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Checks values along exact trajectories independently of the lifted coefficient matrix. */
final class RecurrenceObservationProof {
    private RecurrenceObservationProof() { }

    static boolean verify(Transition transition, Polynomial observation, int degree, List<Rational> coefficients, Work work) {
        int dimension = transition.linear().columns();
        var origin = sample(dimension, work);
        if (!holds(transition, observation, coefficients, origin, work)) return false;
        if (degree == 0) return true;
        for (int i = 0; i < dimension; i++) {
            var axis = sample(dimension, work);
            axis.set(i, Rational.ONE);
            if (!holds(transition, observation, coefficients, axis, work)) return false;
            if (degree == 1) continue;
            axis.set(i, Rational.of(2));
            if (!holds(transition, observation, coefficients, axis, work)) return false;
            for (int j = i + 1; j < dimension; j++) {
                var pair = sample(dimension, work);
                pair.set(i, Rational.ONE);
                pair.set(j, Rational.ONE);
                if (!holds(transition, observation, coefficients, pair, work)) return false;
            }
        }
        return true;
    }

    private static List<Rational> sample(int dimension, Work work) {
        work.add(1L + dimension);
        return new ArrayList<>(Collections.nCopies(dimension, Rational.ZERO));
    }

    private static boolean holds(Transition transition, Polynomial observation, List<Rational> coefficients,
            List<Rational> state, Work work) {
        var sum = Rational.ZERO;
        for (Rational coefficient : coefficients) {
            sum = addProduct(sum, coefficient, evaluate(observation, state, work), work);
            state = advance(transition, state, work);
        }
        work.add(1);
        return evaluate(observation, state, work).equals(sum);
    }

    static Rational evaluate(Polynomial observation, List<Rational> state, Work work) {
        work.add(1);
        var sum = Rational.ZERO;
        for (var term : observation.terms().entrySet()) {
            work.add(1);
            var value = term.getValue();
            for (var variable : term.getKey().powers().entrySet()) {
                work.add(1);
                var coordinate = state.get(variable.getKey().charAt(1) - '0');
                for (int exponent = 0; exponent < variable.getValue(); exponent++) {
                    value = multiply(value, coordinate, work);
                }
            }
            sum = add(sum, value, work);
        }
        return sum;
    }

    static List<Rational> advance(Transition transition, List<Rational> state, Work work) {
        var next = new ArrayList<Rational>(state.size());
        for (int row = 0; row < state.size(); row++) {
            work.add(1);
            var value = transition.offset().get(row);
            for (int column = 0; column < state.size(); column++) {
                value = addProduct(value, transition.linear().get(row, column), state.get(column), work);
            }
            next.add(value);
        }
        return next;
    }
}
