package de.regelsuche.math.algorithms.equivalence;

import java.math.BigDecimal;
import java.math.BigInteger;

public record Rational(BigInteger numerator, BigInteger denominator) implements Comparable<Rational> {
    public static final Rational ZERO = new Rational(BigInteger.ZERO, BigInteger.ONE);
    public static final Rational ONE = new Rational(BigInteger.ONE, BigInteger.ONE);
    public static final Rational NEGATIVE_ONE = new Rational(BigInteger.ONE.negate(), BigInteger.ONE);

    public Rational {
        if (denominator == null || denominator.signum() == 0) {
            throw new IllegalArgumentException("denominator must not be zero");
        }
        if (numerator == null) {
            throw new IllegalArgumentException("numerator must not be null");
        }
        if (numerator.signum() == 0) {
            denominator = BigInteger.ONE;
        } else {
            if (denominator.signum() < 0) {
                numerator = numerator.negate();
                denominator = denominator.negate();
            }
            if (!denominator.equals(BigInteger.ONE)) {
                BigInteger gcd = numerator.gcd(denominator);
                if (!gcd.equals(BigInteger.ONE)) {
                    numerator = numerator.divide(gcd);
                    denominator = denominator.divide(gcd);
                }
            }
        }
    }

    public static Rational of(long value) {
        if (value == 0) {
            return ZERO;
        }
        if (value == 1) {
            return ONE;
        }
        if (value == -1) {
            return NEGATIVE_ONE;
        }
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
        if (other.isZero()) {
            return this;
        }
        if (isZero()) {
            return other;
        }
        BigInteger denominatorGcd = denominator.gcd(other.denominator);
        if (denominatorGcd.equals(BigInteger.ONE)) {
            return new Rational(
                numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),
                denominator.multiply(other.denominator)
            );
        }

        BigInteger leftMultiplier = other.denominator.divide(denominatorGcd);
        BigInteger rightMultiplier = denominator.divide(denominatorGcd);
        BigInteger sum = numerator.multiply(leftMultiplier).add(other.numerator.multiply(rightMultiplier));
        if (sum.signum() == 0) {
            return ZERO;
        }
        BigInteger cancellation = sum.abs().gcd(denominatorGcd);
        return new Rational(
            sum.divide(cancellation),
            denominator.divide(cancellation).multiply(leftMultiplier)
        );
    }

    public Rational subtract(Rational other) {
        if (other.isZero()) {
            return this;
        }
        return add(other.negate());
    }

    public Rational multiply(Rational other) {
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

        BigInteger leftCancellation = numerator.abs().gcd(other.denominator);
        BigInteger rightCancellation = other.numerator.abs().gcd(denominator);
        BigInteger reducedLeftNumerator = numerator.divide(leftCancellation);
        BigInteger reducedRightDenominator = other.denominator.divide(leftCancellation);
        BigInteger reducedRightNumerator = other.numerator.divide(rightCancellation);
        BigInteger reducedLeftDenominator = denominator.divide(rightCancellation);
        return new Rational(
            reducedLeftNumerator.multiply(reducedRightNumerator),
            reducedLeftDenominator.multiply(reducedRightDenominator)
        );
    }

    public Rational divide(Rational other) {
        if (other.isZero()) {
            throw new ArithmeticException("division by zero");
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

        BigInteger numeratorCancellation = numerator.abs().gcd(other.numerator.abs());
        BigInteger denominatorCancellation = denominator.gcd(other.denominator);
        BigInteger reducedNumerator = numerator.divide(numeratorCancellation);
        BigInteger reducedDivisorNumerator = other.numerator.divide(numeratorCancellation);
        BigInteger reducedDenominator = denominator.divide(denominatorCancellation);
        BigInteger reducedDivisorDenominator = other.denominator.divide(denominatorCancellation);
        return new Rational(
            reducedNumerator.multiply(reducedDivisorDenominator),
            reducedDenominator.multiply(reducedDivisorNumerator)
        );
    }

    public Rational negate() {
        if (isZero()) {
            return ZERO;
        }
        if (isOne()) {
            return NEGATIVE_ONE;
        }
        if (isNegativeOne()) {
            return ONE;
        }
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

    public boolean isNegativeOne() {
        return numerator.equals(BigInteger.ONE.negate()) && denominator.equals(BigInteger.ONE);
    }

    @Override
    public int compareTo(Rational other) {
        if (this == other || equals(other)) {
            return 0;
        }
        BigInteger denominatorGcd = denominator.gcd(other.denominator);
        return numerator.multiply(other.denominator.divide(denominatorGcd))
            .compareTo(other.numerator.multiply(denominator.divide(denominatorGcd)));
    }

    @Override
    public String toString() {
        if (denominator.equals(BigInteger.ONE)) {
            return numerator.toString();
        }
        return numerator + "/" + denominator;
    }
}
