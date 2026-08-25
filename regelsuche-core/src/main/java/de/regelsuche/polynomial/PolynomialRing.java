package de.regelsuche.polynomial;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Immutable polynomial ring identity over an explicit coefficient domain and
 * ordered variables.
 */
public final class PolynomialRing<C> {
    private final CoefficientDomain<C> coefficientDomain;
    private final List<PolynomialVariable> variables;

    public PolynomialRing(
        CoefficientDomain<C> coefficientDomain,
        List<PolynomialVariable> variables
    ) {
        this.coefficientDomain = Objects.requireNonNull(
            coefficientDomain,
            "coefficientDomain");
        this.variables = List.copyOf(
            Objects.requireNonNull(variables, "variables"));
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
        java.util.ArrayList<PolynomialVariable> extended =
            new java.util.ArrayList<>(variables);
        extended.add(variable);
        return new PolynomialRing<>(coefficientDomain, extended);
    }

    public String canonicalMaterial() {
        StringBuilder result = new StringBuilder();
        append(result, coefficientDomain.id());
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
                && variables.equals(ring.variables);
    }

    @Override
    public int hashCode() {
        return Objects.hash(coefficientDomain.id(), variables);
    }

    @Override
    public String toString() {
        return coefficientDomain.id() + variables;
    }
}
