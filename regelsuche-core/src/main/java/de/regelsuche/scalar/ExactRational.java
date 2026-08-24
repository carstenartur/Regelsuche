package de.regelsuche.scalar;

import java.math.BigInteger;
import java.util.Objects;

/**
 * Canonical arbitrary-precision rational number.
 *
 * <p>This is the authoritative exact rational arithmetic contract shared by
 * the core and legacy mathematical-algorithm adapters. The denominator is
 * always positive, numerator and denominator are reduced by their greatest
 * common divisor, and every zero is represented as {@code 0/1}. No binary
 * floating-point conversion is exposed.</p>
 */
public record ExactRational(
    BigInteger numerator,
    BigInteger denominator
) implements Comparable<ExactRational> {
    public static final ExactRational ZERO =
        new ExactRational(BigInteger.ZERO, BigInteger.ONE);
    public static final ExactRational ONE =
        new ExactRational(BigInteger.ONE, BigInteger.ONE);
    public static final ExactRational NEGATIVE_ONE =
        new ExactRational(BigInteger.ONE.negate(), BigInteger.ONE);

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
        if (value == 0) {
            return ZERO;
        }
        if (value == 1) {
            return ONE;
        }
        if (value == -1) {
            return NEGATIVE_ONE;
        }
        return new ExactRational(
            BigInteger.valueOf(value),
            BigInteger.ONE);
    }

    public static ExactRational integer(BigInteger value) {
        Objects.requireNonNull(value, "value");
        if (value.signum() == 0) {
            return ZERO;
        }
        if (value.equals(BigInteger.ONE)) {
            return ONE;
        }
        if (value.equals(BigInteger.ONE.negate())) {
            return NEGATIVE_ONE;
        }
        return new ExactRational(value, BigInteger.ONE);
    }

    public ExactRational add(ExactRational other) {
        Objects.requireNonNull(other, "other");
        if (other.isZero()) {
            return this;
        }
        if (isZero()) {
            return other;
        }
        BigInteger denominatorGcd = denominator.gcd(other.denominator);
        BigInteger leftMultiplier =
            other.denominator.divide(denominatorGcd);
        BigInteger rightMultiplier =
            denominator.divide(denominatorGcd);
        BigInteger sum = numerator.multiply(leftMultiplier)
            .add(other.numerator.multiply(rightMultiplier));
        if (sum.signum() == 0) {
            return ZERO;
        }
        BigInteger cancellation = sum.abs().gcd(denominatorGcd);
        return new ExactRational(
            sum.divide(cancellation),
            denominator.divide(cancellation).multiply(leftMultiplier));
    }

    public ExactRational subtract(ExactRational other) {
        return add(Objects.requireNonNull(other, "other").negate());
    }

    public ExactRational multiply(ExactRational other) {
        Objects.requireNonNull(other, "other");
        if (isZero() || other.isZero()) {
            return ZERO;
        }
        if (isOne()) {
            return other;
        }
        if (other.isOne()) {
            return this;
        }
        if (isNegativeOne()) {
            return other.negate();
        }
        if (other.isNegativeOne()) {
            return negate();
        }

        BigInteger leftCancellation =
            numerator.abs().gcd(other.denominator);
        BigInteger rightCancellation =
            other.numerator.abs().gcd(denominator);
        return new ExactRational(
            numerator.divide(leftCancellation)
                .multiply(other.numerator.divide(rightCancellation)),
            denominator.divide(rightCancellation)
                .multiply(other.denominator.divide(leftCancellation)));
    }

    public ExactRational divide(ExactRational other) {
        Objects.requireNonNull(other, "other");
        if (other.isZero()) {
            throw new ArithmeticException("division by zero rational");
        }
        if (isZero()) {
            return ZERO;
        }
        if (other.isOne()) {
            return this;
        }
        if (other.isNegativeOne()) {
            return negate();
        }

        BigInteger numeratorCancellation =
            numerator.abs().gcd(other.numerator.abs());
        BigInteger denominatorCancellation =
            denominator.gcd(other.denominator);
        return new ExactRational(
            numerator.divide(numeratorCancellation)
                .multiply(
                    other.denominator.divide(denominatorCancellation)),
            denominator.divide(denominatorCancellation)
                .multiply(
                    other.numerator.divide(numeratorCancellation)));
    }

    public ExactRational negate() {
        if (isZero()) {
            return ZERO;
        }
        if (isOne()) {
            return NEGATIVE_ONE;
        }
        if (isNegativeOne()) {
            return ONE;
        }
        return new ExactRational(numerator.negate(), denominator);
    }

    public ExactRational abs() {
        return numerator.signum() < 0 ? negate() : this;
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

    public int signum() {
        return numerator.signum();
    }

    public boolean isZero() {
        return numerator.signum() == 0;
    }

    public boolean isOne() {
        return numerator.equals(BigInteger.ONE)
            && denominator.equals(BigInteger.ONE);
    }

    public boolean isNegativeOne() {
        return numerator.equals(BigInteger.ONE.negate())
            && denominator.equals(BigInteger.ONE);
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
        if (this == other || equals(other)) {
            return 0;
        }
        BigInteger denominatorGcd =
            denominator.gcd(other.denominator);
        return numerator.multiply(
                other.denominator.divide(denominatorGcd))
            .compareTo(
                other.numerator.multiply(
                    denominator.divide(denominatorGcd)));
    }

    @Override
    public String toString() {
        return canonicalText();
    }
}
