package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.math.algorithms.equivalence.Monomial;
import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.representation.RepresentationBridge.WorkLedger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Shared finite representation helpers; arithmetic stays in the existing exact polynomial core. */
final class RecurrenceInvariantSupport {
    private RecurrenceInvariantSupport() { }

    static void requireBounded(Rational number) {
        Objects.requireNonNull(number, "coefficient");
        if (number.numerator().bitLength() > 128 || number.denominator().bitLength() > 128)
            throw new IllegalArgumentException("recurrence inputs are limited to 128-bit exact coefficients");
    }

    static List<String> variables(int dimension) {
        var variables = new ArrayList<String>();
        for (int i = 0; i < dimension; i++) variables.add("x" + i);
        return List.copyOf(variables);
    }

    static List<Monomial> basis(int dimension, int degree) {
        var result = new ArrayList<Monomial>();
        result.add(Monomial.constant());
        var variables = variables(dimension);
        for (String variable : variables) result.add(Monomial.variable(variable));
        if (degree == 2) {
            for (int i = 0; i < dimension; i++)
                for (int j = i; j < dimension; j++)
                    result.add(Monomial.variable(variables.get(i)).multiply(Monomial.variable(variables.get(j))));
        }
        return List.copyOf(result);
    }

    static List<String> images(RecurrenceInvariantSynthesis.Transition transition, Work work) {
        int dimension = transition.linear().columns();
        var result = new ArrayList<String>();
        for (int row = 0; row < dimension; row++) {
            StringBuilder image = new StringBuilder("(" + transition.offset().get(row) + ")");
            for (int column = 0; column < dimension; column++) {
                work.add(1);
                image.append("+(").append(transition.linear().get(row, column)).append(")*x").append(column);
            }
            result.add(image.toString());
        }
        return List.copyOf(result);
    }

    /** Exact images of the coefficient basis, shared by both evolution signs. */
    static Map<Monomial, Polynomial> liftedImages(RecurrenceInvariantSynthesis.Transition transition,
            List<Monomial> basis, Work work) {
        int dimension = transition.linear().columns();
        var variables = variables(dimension);
        var coordinates = variables.stream().map(Monomial::variable).toList();
        work.add(dimension);
        var coordinateImages = new ArrayList<Polynomial>(dimension);
        for (int row = 0; row < dimension; row++) {
            work.add(1);
            var polynomial = Polynomial.constant(transition.offset().get(row));
            for (int col = 0; col < dimension; col++) {
                work.add(1);
                var coefficient = transition.linear().get(row, col);
                if (coefficient.isZero()) continue;
                work.add(1L + polynomial.termCount());
                polynomial = polynomial.add(Polynomial.term(coordinates.get(col), coefficient));
            }
            coordinateImages.add(polynomial);
        }

        var images = new HashMap<Monomial, Polynomial>();
        for (var monomial : basis) {
            images.put(monomial, liftedMonomial(monomial, variables, coordinateImages, work));
        }
        return Map.copyOf(images);
    }

    private static Polynomial liftedMonomial(Monomial monomial, List<String> variables,
            List<Polynomial> coordinateImages, Work work) {
        var image = Polynomial.constant(Rational.ONE);
        work.add(1L + variables.size());
        for (int i = 0; i < variables.size(); i++) {
            int power = monomial.exponentOf(variables.get(i));
            var coordinateImage = coordinateImages.get(i);
            for (int j = 0; j < power; j++) {
                work.add(1L + (long) image.termCount() * coordinateImage.termCount());
                image = image.multiply(coordinateImage);
            }
        }
        return image;
    }

    static Polynomial translate(Polynomial polynomial, List<Rational> shift, Work work) {
        var variables = variables(shift.size());
        var images = new ArrayList<Polynomial>();
        for (int i = 0; i < shift.size(); i++) {
            work.add(2);
            images.add(Polynomial.variable(variables.get(i)).add(Polynomial.constant(shift.get(i).negate())));
        }
        work.add(polynomial.termCount());
        var monomials = polynomial.terms().keySet().stream().sorted(Comparator.comparing(Monomial::key)).toList();
        var translated = Polynomial.zero();
        for (var monomial : monomials) {
            var image = liftedMonomial(monomial, variables, images, work);
            work.add(1L + translated.termCount() + 2L * image.termCount());
            translated = translated.add(image.multiply(polynomial.terms().get(monomial)));
        }
        return translated;
    }

    static Rational evaluate(Polynomial polynomial, List<Rational> state, Work work) {
        var variables = variables(state.size());
        var total = Rational.ZERO;
        for (var term : polynomial.terms().entrySet()) {
            var value = term.getValue();
            work.add(1L + state.size() + term.getKey().totalDegree());
            for (int coordinate = 0; coordinate < state.size(); coordinate++) {
                int exponent = term.getKey().exponentOf(variables.get(coordinate));
                for (int i = 0; i < exponent; i++) value = value.multiply(state.get(coordinate));
            }
            total = total.add(value);
        }
        return total;
    }

    /** Simultaneous substitution, never sequential textual replacement of variable names. */
    static String monomial(Monomial monomial, List<String> images, Work work) {
        var result = new StringBuilder("1");
        for (int i = 0; i < images.size(); i++) {
            work.add(1);
            int power = monomial.exponentOf("x" + i);
            if (power != 0) result.append("*(").append(images.get(i)).append(")^").append(power);
        }
        work.add(result.length());
        return result.toString();
    }

    static Polynomial substitute(Polynomial polynomial, List<String> images, Work work) {
        var result = Polynomial.zero();
        // Project each monomial separately; do not raise the existing syntax guard for a large substitution.
        for (var monomial : polynomial.terms().keySet().stream().sorted(java.util.Comparator.comparing(Monomial::key)).toList()) {
            String term = "(" + polynomial.terms().get(monomial) + ")*(" + monomial(monomial, images, work) + ")";
            var projected = project(term, work);
            work.add(1L + result.termCount() + projected.termCount());
            result = result.add(projected);
        }
        return result;
    }

    static Polynomial project(String expression, Work work) {
        work.add(expression.length());
        return new ExactPolynomialAnalysis(work::add).normalForm(expression);
    }

    static final class Work {
        private final int configured;
        private int consumed;
        Work(int configured) { this.configured = configured; }
        void add(long units) {
            if (units < 0) throw new IllegalArgumentException("negative work");
            if (units > configured - consumed) {
                consumed = configured;
                throw new Exhausted();
            }
            consumed += (int) units;
        }
        int remaining() { return configured - consumed; }
        WorkLedger ledger() { return WorkLedger.of(configured, consumed); }
    }

    static final class Exhausted extends RuntimeException { }
}
