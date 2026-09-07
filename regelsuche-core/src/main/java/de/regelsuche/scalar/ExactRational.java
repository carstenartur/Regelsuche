package de.regelsuche.scalar;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.math.BigInteger;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Objects;
import java.util.Optional;

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

    /** Exact source input under the separately enforced literal grammar and limits. */
    public static ExactRational parse(String literal) {
        return new ExactRationalDomain().parseValue(literal);
    }

    /**
     * Decodes normalized value text, independently of source-literal digit limits.
     * A 4096-character envelope bounds allocation and covers the canonical form
     * of every admitted source literal and every 4096-bit arithmetic result.
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ExactRational fromCanonicalText(String text) {
        if (text == null || text.length() > ExactRationalDomain.MAX_LITERAL_CHARACTERS
                || !text.matches("-?(0|[1-9][0-9]*)(/[1-9][0-9]*)?")) {
            throw new IllegalArgumentException("Invalid canonical rational text");
        }
        int slash = text.indexOf('/');
        ExactRational value = slash < 0
            ? integer(new BigInteger(text))
            : new ExactRational(new BigInteger(text.substring(0, slash)),
                new BigInteger(text.substring(slash + 1)));
        if (!value.canonicalText().equals(text)) {
            throw new IllegalArgumentException("Rational text is not normalized");
        }
        return value;
    }

    /** Exact conversion of an already typed decimal, without source provenance. */
    public static ExactRational fromDecimal(BigDecimal decimal) {
        Objects.requireNonNull(decimal, "decimal");
        int scale = decimal.scale();
        return scale < 0
            ? integer(decimal.unscaledValue().multiply(BigInteger.TEN.pow(Math.negateExact(scale))))
            : new ExactRational(decimal.unscaledValue(), BigInteger.TEN.pow(scale));
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

    public boolean equalsInteger(long value) {
        return isInteger() && numerator.equals(BigInteger.valueOf(value));
    }

    public int intValueExact() {
        if (!isInteger()) {
            throw new ArithmeticException("rational is not an integer");
        }
        return numerator.intValueExact();
    }

    public long longValueExact() {
        if (!isInteger()) {
            throw new ArithmeticException("rational is not an integer");
        }
        return numerator.longValueExact();
    }

    /** Explicitly rounded projection for numerical diagnostics, never equality. */
    public BigDecimal toBigDecimal(MathContext context) {
        return new BigDecimal(numerator).divide(new BigDecimal(denominator), context);
    }

    /** A root exists here only when both integer components are perfect squares. */
    public Optional<ExactRational> sqrtExact() {
        if (signum() < 0) {
            return Optional.empty();
        }
        BigInteger[] top = numerator.sqrtAndRemainder();
        BigInteger[] bottom = denominator.sqrtAndRemainder();
        return top[1].signum() == 0 && bottom[1].signum() == 0
            ? Optional.of(new ExactRational(top[0], bottom[0])) : Optional.empty();
    }

    @JsonValue
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
