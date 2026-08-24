package de.regelsuche.scalar;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Canonical bounded univariate polynomial over exact rationals. */
public record ExactRationalPolynomial(
    List<ExactRational> coefficientsAscending
) {
    public static final int MAX_COEFFICIENTS =
        ExactRationalPolynomialContentNormalizer.MAX_DEGREE + 1;

    public ExactRationalPolynomial {
        Objects.requireNonNull(
            coefficientsAscending,
            "coefficientsAscending");
        if (coefficientsAscending.isEmpty()
                || coefficientsAscending.size() > MAX_COEFFICIENTS) {
            throw new IllegalArgumentException(
                "exact rational polynomial coefficient count is invalid");
        }
        List<ExactRational> normalized = new ArrayList<>(
            coefficientsAscending.size());
        for (ExactRational coefficient : coefficientsAscending) {
            ExactRational exact = Objects.requireNonNull(
                coefficient,
                "polynomial coefficient");
            if (exact.numerator().abs().bitLength()
                    > ExactRationalPolynomialContentNormalizer
                        .MAX_COEFFICIENT_BITS
                    || exact.denominator().bitLength()
                    > ExactRationalPolynomialContentNormalizer
                        .MAX_COEFFICIENT_BITS) {
                throw new IllegalArgumentException(
                    "exact rational polynomial coefficient exceeds v1 bound");
            }
            normalized.add(exact);
        }
        int last = normalized.size() - 1;
        while (last > 0 && normalized.get(last).isZero()) {
            last--;
        }
        coefficientsAscending = List.copyOf(
            normalized.subList(0, last + 1));
    }

    public static ExactRationalPolynomial of(
        ExactRational... coefficientsAscending
    ) {
        Objects.requireNonNull(
            coefficientsAscending,
            "coefficientsAscending");
        return new ExactRationalPolynomial(
            List.of(coefficientsAscending));
    }

    public int degree() {
        return isZero() ? -1 : coefficientsAscending.size() - 1;
    }

    public boolean isZero() {
        return coefficientsAscending.size() == 1
            && coefficientsAscending.getFirst().isZero();
    }

    public ExactRational coefficient(int exponent) {
        if (exponent < 0) {
            throw new IllegalArgumentException(
                "polynomial exponent must not be negative");
        }
        return exponent < coefficientsAscending.size()
            ? coefficientsAscending.get(exponent)
            : ExactRational.ZERO;
    }

    public ExactRational evaluate(ExactRational argument) {
        Objects.requireNonNull(argument, "argument");
        ExactRational result = ExactRational.ZERO;
        for (int exponent = coefficientsAscending.size() - 1;
                exponent >= 0;
                exponent--) {
            result = result.multiply(argument)
                .add(coefficientsAscending.get(exponent));
        }
        return result;
    }

    public String canonicalCoefficientText() {
        return coefficientsAscending.stream()
            .map(ExactRational::canonicalText)
            .toList()
            .toString();
    }
}
