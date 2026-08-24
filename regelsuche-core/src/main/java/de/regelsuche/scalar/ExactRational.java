package de.regelsuche.scalar;

import java.math.BigInteger;
import java.util.Objects;

/**
 * Canonical arbitrary-precision rational number.
 *
 * <p>The denominator is always positive, numerator and denominator are reduced
 * by their greatest common divisor, and every zero is represented as
 * {@code 0/1}. No binary floating-point conversion is exposed.</p>
 */
public record ExactRational(
    BigInteger numerator,
    BigInteger denominator
) implements Comparable<ExactRational> {
    public static final ExactRational ZERO =
        new ExactRational(BigInteger.ZERO, BigInteger.ONE);
    public static final ExactRational ONE =
        new ExactRational(BigInteger.ONE, BigInteger.ONE);

    public ExactRational {
        Objects.requireNonNull(numerator, "numerator");
        Objects.requireNonNull(denominator, "denominator");
        if (denominator.signum() == 0) {
            throw new ArithmeticException(
                "rational denominator must not be zero");
        }
        if (numerator.signum() == 0) {
            numerator = BigInteger.ZERO;
            denominator = BigInteger.ONE;
        } else {
            if (denominator.signum() < 0) {
                numerator = numerator.negate();
                denominator = denominator.negate();
            }
            BigInteger divisor = numerator.gcd(denominator);
            numerator = numerator.divide(divisor);
            denominator = denominator.divide(divisor);
        }
    }

    public static ExactRational integer(long value) {
        return integer(BigInteger.valueOf(value));
    }

    public static ExactRational integer(BigInteger value) {
        return new ExactRational(
            Objects.requireNonNull(value, "value"),
            BigInteger.ONE);
    }

    public ExactRational add(ExactRational other) {
        Objects.requireNonNull(other, "other");
        return new ExactRational(
            numerator.multiply(other.denominator)
                .add(other.numerator.multiply(denominator)),
            denominator.multiply(other.denominator));
    }

    public ExactRational subtract(ExactRational other) {
        return add(Objects.requireNonNull(other, "other").negate());
    }

    public ExactRational multiply(ExactRational other) {
        Objects.requireNonNull(other, "other");
        return new ExactRational(
            numerator.multiply(other.numerator),
            denominator.multiply(other.denominator));
    }

    public ExactRational divide(ExactRational other) {
        Objects.requireNonNull(other, "other");
        if (other.isZero()) {
            throw new ArithmeticException("division by zero rational");
        }
        return new ExactRational(
            numerator.multiply(other.denominator),
            denominator.multiply(other.numerator));
    }

    public ExactRational negate() {
        return isZero()
            ? ZERO
            : new ExactRational(numerator.negate(), denominator);
    }

    public ExactRational reciprocal() {
        if (isZero()) {
            throw new ArithmeticException(
                "zero rational has no reciprocal");
        }
        return new ExactRational(denominator, numerator);
    }

    public ExactRational pow(int exponent) {
        if (exponent < 0) {
            throw new IllegalArgumentException(
                "exact rational exponent must not be negative");
        }
        if (exponent == 0) {
            return ONE;
        }
        return new ExactRational(
            numerator.pow(exponent),
            denominator.pow(exponent));
    }

    public boolean isZero() {
        return numerator.signum() == 0;
    }

    public boolean isInteger() {
        return denominator.equals(BigInteger.ONE);
    }

    public String canonicalText() {
        return isInteger()
            ? numerator.toString()
            : numerator + "/" + denominator;
    }

    @Override
    public int compareTo(ExactRational other) {
        Objects.requireNonNull(other, "other");
        return numerator.multiply(other.denominator)
            .compareTo(other.numerator.multiply(denominator));
    }

    @Override
    public String toString() {
        return canonicalText();
    }
}
