package de.regelsuche.polynomial;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Immutable polynomial ring identity over an explicit coefficient domain,
 * ordered variables and explicit monomial order.
 */
public final class PolynomialRing<C> {
    private final CoefficientDomain<C> coefficientDomain;
    private final List<PolynomialVariable> variables;
    private final MonomialOrder monomialOrder;

    public PolynomialRing(
        CoefficientDomain<C> coefficientDomain,
        List<PolynomialVariable> variables,
        MonomialOrder monomialOrder
    ) {
        this.coefficientDomain = Objects.requireNonNull(
            coefficientDomain,
            "coefficientDomain");
        this.variables = List.copyOf(
            Objects.requireNonNull(variables, "variables"));
        this.monomialOrder = Objects.requireNonNull(
            monomialOrder,
            "monomialOrder");
        if (this.variables.stream().anyMatch(Objects::isNull)
                || new HashSet<>(this.variables).size()
                    != this.variables.size()) {
            throw new IllegalArgumentException(
                "polynomial ring variables must be non-null and unique");
        }
    }

    public CoefficientDomain<C> coefficientDomain() {
        return coefficientDomain;
    }

    public List<PolynomialVariable> variables() {
        return variables;
    }

    public MonomialOrder monomialOrder() {
        return monomialOrder;
    }

    public Comparator<Monomial> monomialComparator() {
        return monomialOrder.comparator();
    }

    public int variableCount() {
        return variables.size();
    }

    public PolynomialRing<C> appendVariable(
        PolynomialVariable variable
    ) {
        Objects.requireNonNull(variable, "variable");
        if (variables.contains(variable)) {
            throw new IllegalArgumentException(
                "polynomial variable already belongs to ring");
        }
        ArrayList<PolynomialVariable> extended =
            new ArrayList<>(variables);
        extended.add(variable);
        return new PolynomialRing<>(
            coefficientDomain,
            extended,
            monomialOrder);
    }

    public String canonicalMaterial() {
        StringBuilder result = new StringBuilder();
        append(result, coefficientDomain.id());
        append(result, monomialOrder.id());
        variables.forEach(variable -> append(result, variable.id()));
        return result.toString();
    }

    private static void append(StringBuilder target, String value) {
        target.append('|')
            .append(value.length())
            .append(':')
            .append(value);
    }

    @Override
    public boolean equals(Object other) {
        return this == other
            || other instanceof PolynomialRing<?> ring
                && coefficientDomain.id().equals(
                    ring.coefficientDomain.id())
                && variables.equals(ring.variables)
                && monomialOrder == ring.monomialOrder;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            coefficientDomain.id(),
            variables,
            monomialOrder);
    }

    @Override
    public String toString() {
        return coefficientDomain.id()
            + '[' + monomialOrder.id() + ']'
            + variables;
    }

    /** Standard leading-term orders, with the largest monomial first. */
    public enum MonomialOrder {
        LEXICOGRAPHIC("lex") {
            @Override
            int compareSameArity(Monomial left, Monomial right) {
                return compareLexicographically(left, right);
            }
        },
        GRADED_LEXICOGRAPHIC("grlex") {
            @Override
            int compareSameArity(Monomial left, Monomial right) {
                int degree = Integer.compare(
                    right.totalDegree(),
                    left.totalDegree());
                return degree != 0
                    ? degree
                    : compareLexicographically(left, right);
            }
        },
        GRADED_REVERSE_LEXICOGRAPHIC("grevlex") {
            @Override
            int compareSameArity(Monomial left, Monomial right) {
                int degree = Integer.compare(
                    right.totalDegree(),
                    left.totalDegree());
                if (degree != 0) {
                    return degree;
                }
                for (int index = left.arity() - 1;
                        index >= 0;
                        index--) {
                    int comparison = Integer.compare(
                        left.exponent(index),
                        right.exponent(index));
                    if (comparison != 0) {
                        return comparison;
                    }
                }
                return 0;
            }
        };

        private final String id;

        MonomialOrder(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public Comparator<Monomial> comparator() {
            return this::compare;
        }

        public int compare(Monomial left, Monomial right) {
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
            left.requireSameArity(right);
            return compareSameArity(left, right);
        }

        abstract int compareSameArity(
            Monomial left,
            Monomial right
        );

        private static int compareLexicographically(
            Monomial left,
            Monomial right
        ) {
            for (int index = 0; index < left.arity(); index++) {
                int comparison = Integer.compare(
                    right.exponent(index),
                    left.exponent(index));
                if (comparison != 0) {
                    return comparison;
                }
            }
            return 0;
        }
    }
}
