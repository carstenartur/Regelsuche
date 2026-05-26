package de.regelsuche.math.algorithms.equivalence;

public record Term(Rational coefficient, Monomial monomial) {
    public Term {
        if (coefficient == null || monomial == null) {
            throw new IllegalArgumentException("term fields must not be null");
        }
    }
}
