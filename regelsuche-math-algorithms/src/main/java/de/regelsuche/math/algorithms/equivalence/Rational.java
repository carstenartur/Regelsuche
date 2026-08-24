package de.regelsuche.math.algorithms.equivalence;

import de.regelsuche.scalar.ExactRational;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;

/**
 * Compatibility facade for the historical mathematical-algorithms API.
 *
 * <p>All exact normalization and arithmetic delegates to the authoritative
 * {@link ExactRational} contract in {@code regelsuche-core}. New core code
 * should use {@code ExactRational} directly. {@link #fromDouble(double)} is a
 * legacy approximate-input adapter and must not be used to authorize an exact
 * source-language claim.</p>
 */
public record Rational(
    BigInteger numerator,
    BigInteger denominator
) implements Comparable<Rational> {
    public static final Rational ZERO =
        fromExact(ExactRational.ZERO);
    public static final Rational ONE =
        fromExact(ExactRational.ONE);
    public static final Rational NEGATIVE_ONE =
        fromExact(ExactRational.NEGATIVE_ONE);

    public Rational {
        if (numerator == null) {
            throw new IllegalArgumentException(
                "numerator must not be null");
        }
        if (denominator == null || denominator.signum() == 0) {
            throw new IllegalArgumentException(
                "denominator must not be zero");
        }
        ExactRational normalized =
            new ExactRational(numerator, denominator);
        numerator = normalized.numerator();
        denominator = normalized.denominator();
    }

    public static Rational of(long value) {
        return fromExact(ExactRational.integer(value));
    }

    /**
     * Converts an already rounded binary floating-point value through its
     * canonical decimal rendering. This preserves legacy behavior only.
     */
    public static Rational fromDouble(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(
                "number must be finite");
        }
        BigDecimal decimal =
            BigDecimal.valueOf(value).stripTrailingZeros();
        BigInteger numerator = decimal.unscaledValue();
        BigInteger denominator = BigInteger.ONE;
        if (decimal.scale() > 0) {
            denominator = BigInteger.TEN.pow(decimal.scale());
        } else if (decimal.scale() < 0) {
            numerator = numerator.multiply(
                BigInteger.TEN.pow(-decimal.scale()));
        }
        return new Rational(numerator, denominator);
    }

    public static Rational fromExact(ExactRational value) {
        Objects.requireNonNull(value, "value");
        return new Rational(
            value.numerator(),
            value.denominator());
    }

    public ExactRational exactValue() {
        return new ExactRational(numerator, denominator);
    }

    public Rational add(Rational other) {
        return fromExact(exactValue().add(
            Objects.requireNonNull(other, "other").exactValue()));
    }

    public Rational subtract(Rational other) {
        return fromExact(exactValue().subtract(
            Objects.requireNonNull(other, "other").exactValue()));
    }

    public Rational multiply(Rational other) {
        return fromExact(exactValue().multiply(
            Objects.requireNonNull(other, "other").exactValue()));
    }

    public Rational divide(Rational other) {
        return fromExact(exactValue().divide(
            Objects.requireNonNull(other, "other").exactValue()));
    }

    public Rational negate() {
        return fromExact(exactValue().negate());
    }

    public Rational abs() {
        return fromExact(exactValue().abs());
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

    @Override
    public int compareTo(Rational other) {
        return exactValue().compareTo(
            Objects.requireNonNull(other, "other").exactValue());
    }

    @Override
    public String toString() {
        return exactValue().canonicalText();
    }
}
