package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.math.algorithms.equivalence.Monomial;
import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.representation.RepresentationBridge.WorkLedger;
import java.util.ArrayList;
import java.util.List;
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
