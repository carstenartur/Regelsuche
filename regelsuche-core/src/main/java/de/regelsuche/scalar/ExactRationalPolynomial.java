package de.regelsuche.scalar;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Canonical univariate polynomial over {@link ExactRational} coefficients.
 *
 * <p>Coefficients are stored in ascending exponent order. Trailing zero
 * coefficients are removed; the zero polynomial is represented by one zero
 * coefficient. The variable name is intentionally outside this coefficient
 * value so complete AST atoms can be bound separately by a representation
 * bridge.</p>
 */
public record ExactRationalPolynomial(
    List<ExactRational> coefficientsAscending
) {
    public ExactRationalPolynomial {
        Objects.requireNonNull(
            coefficientsAscending,
            "coefficientsAscending");
        if (coefficientsAscending.isEmpty()) {
            throw new IllegalArgumentException(
                "exact rational polynomial requires coefficients");
        }
        List<ExactRational> normalized = new ArrayList<>(
            coefficientsAscending.size());
        for (ExactRational coefficient : coefficientsAscending) {
            normalized.add(Objects.requireNonNull(
                coefficient,
                "polynomial coefficient"));
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
