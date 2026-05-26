package de.regelsuche.math.algorithms.equivalence;

import java.math.BigDecimal;
import java.math.BigInteger;

public record Rational(BigInteger numerator, BigInteger denominator) implements Comparable<Rational> {
    public static final Rational ZERO = new Rational(BigInteger.ZERO, BigInteger.ONE);
    public static final Rational ONE = new Rational(BigInteger.ONE, BigInteger.ONE);

    public Rational {
        if (denominator == null || denominator.signum() == 0) {
            throw new IllegalArgumentException("denominator must not be zero");
        }
        if (numerator == null) {
            throw new IllegalArgumentException("numerator must not be null");
        }
        if (denominator.signum() < 0) {
            numerator = numerator.negate();
            denominator = denominator.negate();
        }
        BigInteger gcd = numerator.gcd(denominator);
        numerator = numerator.divide(gcd);
        denominator = denominator.divide(gcd);
    }

    public static Rational of(long value) {
        return new Rational(BigInteger.valueOf(value), BigInteger.ONE);
    }

    public static Rational fromDouble(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("number must be finite");
        }
        BigDecimal decimal = BigDecimal.valueOf(value).stripTrailingZeros();
        BigInteger numerator = decimal.unscaledValue();
        BigInteger denominator = BigInteger.ONE;
        if (decimal.scale() > 0) {
            denominator = BigInteger.TEN.pow(decimal.scale());
        } else if (decimal.scale() < 0) {
            numerator = numerator.multiply(BigInteger.TEN.pow(-decimal.scale()));
        }
        return new Rational(numerator, denominator);
    }

    public Rational add(Rational other) {
        return new Rational(
            numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),
            denominator.multiply(other.denominator)
        );
    }

    public Rational subtract(Rational other) {
        return add(other.negate());
    }

    public Rational multiply(Rational other) {
        return new Rational(numerator.multiply(other.numerator), denominator.multiply(other.denominator));
    }

    public Rational divide(Rational other) {
        if (other.isZero()) {
            throw new ArithmeticException("division by zero");
        }
        return new Rational(numerator.multiply(other.denominator), denominator.multiply(other.numerator));
    }

    public Rational negate() {
        return new Rational(numerator.negate(), denominator);
    }

    public Rational abs() {
        return numerator.signum() < 0 ? negate() : this;
    }

    public boolean isZero() {
        return numerator.signum() == 0;
    }

    public boolean isOne() {
        return numerator.equals(BigInteger.ONE) && denominator.equals(BigInteger.ONE);
    }

    @Override
    public int compareTo(Rational other) {
        return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator));
    }

    @Override
    public String toString() {
        if (denominator.equals(BigInteger.ONE)) {
            return numerator.toString();
        }
        return numerator + "/" + denominator;
    }
}
