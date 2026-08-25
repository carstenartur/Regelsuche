package de.regelsuche.polynomial;

import java.util.Objects;

/** One nonconstant factor together with its positive multiplicity. */
public record PolynomialFactor<C>(
    SparsePolynomial<C> polynomial,
    int multiplicity
) {
    public PolynomialFactor {
        Objects.requireNonNull(polynomial, "polynomial");
        if (multiplicity < 1
                || polynomial.isZero()
                || polynomial.isConstant()) {
            throw new IllegalArgumentException(
                "polynomial factor must be nonconstant with positive multiplicity");
        }
    }
}
