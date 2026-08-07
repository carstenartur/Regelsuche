package de.regelsuche.math.algorithms.equivalence;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

public final class Polynomial {
    private static final MonomialOrder DEFAULT_ORDER = new GradedReverseLexOrder();
    private static final Polynomial ZERO = new Polynomial(new HashMap<>(), true);
    private static final Polynomial ONE = new Polynomial(Map.of(Monomial.constant(), Rational.ONE));
    private final Map<Monomial, Rational> terms;

    private Polynomial(Map<Monomial, Rational> terms) {
        Map<Monomial, Rational> normalized = new HashMap<>();
        terms.forEach((monomial, coefficient) -> mergeTerm(normalized, monomial, coefficient));
        this.terms = Collections.unmodifiableMap(normalized);
    }

    private Polynomial(Map<Monomial, Rational> terms, boolean normalizedOwned) {
        this.terms = Collections.unmodifiableMap(normalizedOwned ? terms : new HashMap<>(terms));
    }

    public static Polynomial zero() {
        return ZERO;
    }

    public static Polynomial constant(Rational value) {
        if (value.isZero()) {
            return ZERO;
        }
        if (value.isOne()) {
            return ONE;
        }
        return new Polynomial(Map.of(Monomial.constant(), value));
    }

    public static Polynomial variable(String variable) {
        return new Polynomial(Map.of(Monomial.variable(variable), Rational.ONE));
    }

    public static Polynomial term(Monomial monomial, Rational coefficient) {
        if (coefficient.isZero()) {
            return ZERO;
        }
        if (monomial.powers().isEmpty() && coefficient.isOne()) {
            return ONE;
        }
        return new Polynomial(Map.of(monomial, coefficient));
    }

    public boolean isZero() {
        return terms.isEmpty();
    }

    public Map<Monomial, Rational> terms() {
        return terms;
    }

    public int termCount() {
        return terms.size();
    }

    public int totalDegree() {
        int degree = 0;
        for (Monomial monomial : terms.keySet()) {
            degree = Math.max(degree, monomial.totalDegree());
        }
        return degree;
    }

    public Set<String> variables() {
        TreeSet<String> variables = new TreeSet<>();
        for (Monomial monomial : terms.keySet()) {
            variables.addAll(monomial.powers().keySet());
        }
        return Set.copyOf(variables);
    }

    public boolean coefficientMagnitudeExceeds(int maxCoefficient) {
        if (maxCoefficient <= 0) {
            return false;
        }
        java.math.BigInteger bound = java.math.BigInteger.valueOf(maxCoefficient);
        for (Rational coefficient : terms.values()) {
            if (coefficient.numerator().abs().compareTo(bound) > 0
                || coefficient.denominator().abs().compareTo(bound) > 0) {
                return true;
            }
        }
        return false;
    }

    public Optional<Term> leadingTerm(MonomialOrder order) {
        Map.Entry<Monomial, Rational> leading = null;
        for (Map.Entry<Monomial, Rational> entry : terms.entrySet()) {
            if (leading == null || order.compare(entry.getKey(), leading.getKey()) < 0) {
                leading = entry;
            }
        }
        return leading == null
            ? Optional.empty()
            : Optional.of(new Term(leading.getValue(), leading.getKey()));
    }

    public Polynomial monic(MonomialOrder order) {
        Optional<Term> leading = leadingTerm(order);
        if (leading.isEmpty() || leading.orElseThrow().coefficient().isOne()) {
            return this;
        }
        return multiply(Rational.ONE.divide(leading.orElseThrow().coefficient()));
    }

    public Polynomial add(Polynomial other) {
        if (other.isZero()) {
            return this;
        }
        if (isZero()) {
            return other;
        }
        Map<Monomial, Rational> merged = new HashMap<>(terms);
        for (Map.Entry<Monomial, Rational> entry : other.terms.entrySet()) {
            mergeTerm(merged, entry.getKey(), entry.getValue());
        }
        return owned(merged);
    }

    public Polynomial subtract(Polynomial other) {
        if (other.isZero()) {
            return this;
        }
        if (this == other) {
            return ZERO;
        }
        Map<Monomial, Rational> merged = new HashMap<>(terms);
        for (Map.Entry<Monomial, Rational> entry : other.terms.entrySet()) {
            mergeTerm(merged, entry.getKey(), entry.getValue().negate());
        }
        return owned(merged);
    }

    public Polynomial multiply(Rational scalar) {
        if (scalar.isZero() || isZero()) {
            return ZERO;
        }
        if (scalar.isOne()) {
            return this;
        }
        Map<Monomial, Rational> scaled = new HashMap<>(terms.size());
        for (Map.Entry<Monomial, Rational> entry : terms.entrySet()) {
            scaled.put(entry.getKey(), entry.getValue().multiply(scalar));
        }
        return owned(scaled);
    }

    public Polynomial multiply(Monomial monomial, Rational coefficient) {
        if (coefficient.isZero() || isZero()) {
            return ZERO;
        }
        if (monomial.powers().isEmpty()) {
            return multiply(coefficient);
        }
        Map<Monomial, Rational> multiplied = new HashMap<>(terms.size());
        for (Map.Entry<Monomial, Rational> entry : terms.entrySet()) {
            multiplied.put(
                entry.getKey().multiply(monomial),
                entry.getValue().multiply(coefficient)
            );
        }
        return owned(multiplied);
    }

    public Polynomial multiply(Polynomial other) {
        if (isZero() || other.isZero()) {
            return ZERO;
        }
        if (this == ONE) {
            return other;
        }
        if (other == ONE) {
            return this;
        }
        Map<Monomial, Rational> multiplied = new HashMap<>();
        for (Map.Entry<Monomial, Rational> left : terms.entrySet()) {
            for (Map.Entry<Monomial, Rational> right : other.terms.entrySet()) {
                Monomial monomial = left.getKey().multiply(right.getKey());
                Rational coefficient = left.getValue().multiply(right.getValue());
                mergeTerm(multiplied, monomial, coefficient);
            }
        }
        return owned(multiplied);
    }

    public Polynomial pow(int exponent) {
        Polynomial result = ONE;
        Polynomial factor = this;
        int remaining = exponent;
        while (remaining > 0) {
            if ((remaining & 1) != 0) {
                result = result.multiply(factor);
            }
            remaining >>>= 1;
            if (remaining > 0) {
                factor = factor.multiply(factor);
            }
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
        return Optional.of(new PolynomialArithmetic.LinearEquation(variableCoefficient, owned(restTerms)));
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

    private static Polynomial owned(Map<Monomial, Rational> normalizedTerms) {
        if (normalizedTerms.isEmpty()) {
            return ZERO;
        }
        return new Polynomial(normalizedTerms, true);
    }

    private static void mergeTerm(Map<Monomial, Rational> target, Monomial monomial, Rational coefficient) {
        if (coefficient.isZero()) {
            return;
        }
        Rational current = target.get(monomial);
        if (current == null) {
            target.put(monomial, coefficient);
            return;
        }
        Rational merged = current.add(coefficient);
        if (merged.isZero()) {
            target.remove(monomial);
        } else {
            target.put(monomial, merged);
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
