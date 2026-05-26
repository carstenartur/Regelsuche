package de.regelsuche.math.algorithms.equivalence;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

public final class Polynomial {
    private static final MonomialOrder DEFAULT_ORDER = new GradedReverseLexOrder();
    private final Map<Monomial, Rational> terms;

    private Polynomial(Map<Monomial, Rational> terms) {
        this.terms = new HashMap<>();
        terms.forEach(this::addTerm);
    }

    public static Polynomial zero() {
        return new Polynomial(Map.of());
    }

    public static Polynomial constant(Rational value) {
        return new Polynomial(Map.of(Monomial.constant(), value));
    }

    public static Polynomial variable(String variable) {
        return new Polynomial(Map.of(Monomial.variable(variable), Rational.ONE));
    }

    public static Polynomial term(Monomial monomial, Rational coefficient) {
        return new Polynomial(Map.of(monomial, coefficient));
    }

    public boolean isZero() {
        return terms.isEmpty();
    }

    public Map<Monomial, Rational> terms() {
        return Map.copyOf(terms);
    }

    public int termCount() {
        return terms.size();
    }

    public int totalDegree() {
        return terms.keySet().stream().mapToInt(Monomial::totalDegree).max().orElse(0);
    }

    public Set<String> variables() {
        TreeSet<String> variables = new TreeSet<>();
        terms.keySet().forEach(monomial -> variables.addAll(monomial.powers().keySet()));
        return Set.copyOf(variables);
    }

    public boolean coefficientMagnitudeExceeds(int maxCoefficient) {
        if (maxCoefficient <= 0) {
            return false;
        }
        java.math.BigInteger bound = java.math.BigInteger.valueOf(maxCoefficient);
        return terms.values().stream().anyMatch(coefficient ->
            coefficient.numerator().abs().compareTo(bound) > 0
                || coefficient.denominator().abs().compareTo(bound) > 0);
    }

    public Optional<Term> leadingTerm(MonomialOrder order) {
        return terms.entrySet().stream()
            .sorted(Map.Entry.comparingByKey(order))
            .findFirst()
            .map(entry -> new Term(entry.getValue(), entry.getKey()));
    }

    public Polynomial monic(MonomialOrder order) {
        return leadingTerm(order)
            .map(term -> multiply(Rational.ONE.divide(term.coefficient())))
            .orElse(this);
    }

    public Polynomial add(Polynomial other) {
        Map<Monomial, Rational> merged = new HashMap<>(terms);
        other.terms.forEach((monomial, coefficient) -> merged.merge(monomial, coefficient, Rational::add));
        return new Polynomial(merged);
    }

    public Polynomial subtract(Polynomial other) {
        return add(other.multiply(Rational.ONE.negate()));
    }

    public Polynomial multiply(Rational scalar) {
        Map<Monomial, Rational> scaled = new HashMap<>();
        terms.forEach((monomial, coefficient) -> scaled.put(monomial, coefficient.multiply(scalar)));
        return new Polynomial(scaled);
    }

    public Polynomial multiply(Monomial monomial, Rational coefficient) {
        return multiply(term(monomial, coefficient));
    }

    public Polynomial multiply(Polynomial other) {
        Map<Monomial, Rational> multiplied = new HashMap<>();
        for (Map.Entry<Monomial, Rational> left : terms.entrySet()) {
            for (Map.Entry<Monomial, Rational> right : other.terms.entrySet()) {
                Monomial monomial = left.getKey().multiply(right.getKey());
                Rational coefficient = left.getValue().multiply(right.getValue());
                multiplied.merge(monomial, coefficient, Rational::add);
            }
        }
        return new Polynomial(multiplied);
    }

    public Polynomial pow(int exponent) {
        Polynomial result = constant(Rational.ONE);
        for (int i = 0; i < exponent; i++) {
            result = result.multiply(this);
        }
        return result;
    }

    public Optional<PolynomialArithmetic.LinearEquation> isolateLinear(String variable) {
        Rational variableCoefficient = Rational.ZERO;
        Map<Monomial, Rational> restTerms = new HashMap<>();
        for (Map.Entry<Monomial, Rational> entry : terms.entrySet()) {
            Monomial monomial = entry.getKey();
            int exponent = monomial.exponentOf(variable);
            if (exponent == 0) {
                restTerms.put(monomial, entry.getValue());
                continue;
            }
            if (exponent != 1 || monomial.without(variable).totalDegree() != 0) {
                return Optional.empty();
            }
            variableCoefficient = variableCoefficient.add(entry.getValue());
        }
        if (variableCoefficient.isZero()) {
            return Optional.empty();
        }
        return Optional.of(new PolynomialArithmetic.LinearEquation(variableCoefficient, new Polynomial(restTerms)));
    }

    public String toCanonicalString() {
        return toCanonicalString(DEFAULT_ORDER);
    }

    public String toCanonicalString(MonomialOrder order) {
        if (terms.isEmpty()) {
            return "0";
        }
        List<Map.Entry<Monomial, Rational>> ordered = terms.entrySet().stream()
            .sorted((left, right) -> {
                int comparison = order.compare(left.getKey(), right.getKey());
                return comparison != 0 ? comparison : left.getKey().key().compareTo(right.getKey().key());
            })
            .toList();
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<Monomial, Rational> entry : ordered) {
            Rational coefficient = entry.getValue();
            Monomial monomial = entry.getKey();
            boolean negative = coefficient.compareTo(Rational.ZERO) < 0;
            Rational absolute = coefficient.abs();
            String monomialKey = monomial.key();

            if (builder.length() > 0) {
                builder.append(negative ? " - " : " + ");
            } else if (negative) {
                builder.append('-');
            }

            boolean writeCoefficient = monomialKey.isEmpty() || !absolute.isOne();
            if (writeCoefficient) {
                builder.append(absolute);
            }
            if (!monomialKey.isEmpty()) {
                if (writeCoefficient) {
                    builder.append('*');
                }
                builder.append(monomialKey);
            }
        }
        return builder.toString();
    }

    private void addTerm(Monomial monomial, Rational coefficient) {
        if (coefficient.isZero()) {
            return;
        }
        Rational merged = terms.getOrDefault(monomial, Rational.ZERO).add(coefficient);
        if (merged.isZero()) {
            terms.remove(monomial);
        } else {
            terms.put(monomial, merged);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Polynomial polynomial)) {
            return false;
        }
        return terms.equals(polynomial.terms);
    }

    @Override
    public int hashCode() {
        return terms.hashCode();
    }
}
