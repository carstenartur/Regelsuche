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
import java.util.function.Predicate;

/** Checks values along exact trajectories independently of the lifted coefficient matrix. */
final class RecurrenceObservationProof {
    private RecurrenceObservationProof() { }

    record OrbitForm(int index, Polynomial observation) { }

    static boolean verify(Transition transition, Polynomial observation, int degree, List<Rational> coefficients, Work work) {
        return verifyAtSamples(transition.linear().columns(), degree, work,
            state -> holds(transition, observation, coefficients, state, work));
    }

    static boolean verifyForms(Transition transition, Polynomial observation, List<OrbitForm> forms, Work work) {
        if (forms.isEmpty()) return true;
        work.add(observation.termCount());
        return verifyAtSamples(transition.linear().columns(), observation.totalDegree(), work,
            initial -> formsHold(transition, observation, forms, initial, work));
    }

    private static boolean verifyAtSamples(int dimension, int degree, Work work, Predicate<List<Rational>> check) {
        if (!check.test(sample(dimension, work))) return false;
        if (degree == 0) return true;
        for (int i = 0; i < dimension; i++) {
            var axis = sample(dimension, work);
            axis.set(i, Rational.ONE);
            if (!check.test(axis)) return false;
            if (degree == 1) continue;
            axis.set(i, Rational.of(2));
            if (!check.test(axis)) return false;
            for (int j = i + 1; j < dimension; j++) {
                var pair = sample(dimension, work);
                pair.set(i, Rational.ONE);
                pair.set(j, Rational.ONE);
                if (!check.test(pair)) return false;
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

    private static boolean formsHold(Transition transition, Polynomial observation, List<OrbitForm> forms,
            List<Rational> initial, Work work) {
        var state = initial;
        int index = 0;
        for (var form : forms) {
            while (index < form.index()) {
                state = advance(transition, state, work);
                index++;
            }
            work.add(1);
            if (!evaluate(observation, state, work).equals(evaluate(form.observation(), initial, work))) return false;
        }
        return true;
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
