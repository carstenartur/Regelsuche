package de.regelsuche.scalar;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
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
    private static final boolean CONSTANTS_INITIALIZED;
    public static final ExactRational ZERO =
        new ExactRational(BigInteger.ZERO, BigInteger.ONE);
    public static final ExactRational ONE =
        new ExactRational(BigInteger.ONE, BigInteger.ONE);
    public static final ExactRational NEGATIVE_ONE =
        new ExactRational(BigInteger.ONE.negate(), BigInteger.ONE);

    static {
        // Shared constants are class initialization, not a query operation. A
        // query budget must not abort initialization and poison this value type.
        CONSTANTS_INITIALIZED = true;
    }

    public ExactRational(BigInteger numerator, BigInteger denominator) {
        var owned = Arithmetic.observe(numerator, denominator, 4);
        Throwable primary = null;
        try {
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
                    numerator = keep(owned, 0, numerator.negate());
                    denominator = keep(owned, 1, denominator.negate());
                }
                BigInteger divisor = keep(owned, 2, numerator.gcd(denominator));
                numerator = keep(owned, 0, numerator.divide(divisor));
                denominator = keep(owned, 1, denominator.divide(divisor));
            }
            this.numerator = numerator;
            this.denominator = denominator;
            keep(owned, 3, this);
        } catch (RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            if (owned != null) owned.close(primary);
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
        if (value.equals(NEGATIVE_ONE.numerator)) {
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
        var owned = Arithmetic.observe(this, other, 8);
        Throwable primary = null;
        try {
            Objects.requireNonNull(other, "other");
            if (other.isZero()) {
                return keep(owned, 7, this);
            }
            if (isZero()) {
                return keep(owned, 7, other);
            }
            BigInteger denominatorGcd = keep(owned, 0, denominator.gcd(other.denominator));
            BigInteger leftMultiplier = keep(owned, 1, other.denominator.divide(denominatorGcd));
            BigInteger rightMultiplier = keep(owned, 2, denominator.divide(denominatorGcd));
            BigInteger leftSummand = keep(owned, 3, numerator.multiply(leftMultiplier));
            BigInteger rightSummand = keep(owned, 4, other.numerator.multiply(rightMultiplier));
            BigInteger sum = keep(owned, 5, leftSummand.add(rightSummand));
            if (sum.signum() == 0) {
                return keep(owned, 7, ZERO);
            }
            BigInteger absoluteSum = keep(owned, 6, sum.abs());
            BigInteger cancellation = keep(owned, 6, absoluteSum.gcd(denominatorGcd));
            BigInteger top = keep(owned, 3, sum.divide(cancellation));
            BigInteger reducedDenominator = keep(owned, 4, denominator.divide(cancellation));
            BigInteger bottom = keep(owned, 4, reducedDenominator.multiply(leftMultiplier));
            return keep(owned, 7, new ExactRational(top, bottom));
        } catch (RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            if (owned != null) owned.close(primary);
        }
    }

    public ExactRational subtract(ExactRational other) {
        var owned = Arithmetic.observe(this, other, 2);
        Throwable primary = null;
        try {
            var negated = keep(owned, 0, Objects.requireNonNull(other, "other").negate());
            return keep(owned, 1, add(negated));
        } catch (RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            if (owned != null) owned.close(primary);
        }
    }

    public ExactRational multiply(ExactRational other) {
        var owned = Arithmetic.observe(this, other, 7);
        Throwable primary = null;
        try {
            Objects.requireNonNull(other, "other");
            if (isZero() || other.isZero()) {
                return keep(owned, 6, ZERO);
            }
            if (isOne()) {
                return keep(owned, 6, other);
            }
            if (other.isOne()) {
                return keep(owned, 6, this);
            }
            if (isNegativeOne()) {
                return keep(owned, 6, other.negate());
            }
            if (other.isNegativeOne()) {
                return keep(owned, 6, negate());
            }

            BigInteger absoluteLeft = keep(owned, 0, numerator.abs());
            BigInteger leftCancellation = keep(owned, 0, absoluteLeft.gcd(other.denominator));
            BigInteger absoluteRight = keep(owned, 1, other.numerator.abs());
            BigInteger rightCancellation = keep(owned, 1, absoluteRight.gcd(denominator));
            BigInteger leftTop = keep(owned, 2, numerator.divide(leftCancellation));
            BigInteger rightTop = keep(owned, 3, other.numerator.divide(rightCancellation));
            BigInteger top = keep(owned, 4, leftTop.multiply(rightTop));
            BigInteger leftBottom = keep(owned, 2, denominator.divide(rightCancellation));
            BigInteger rightBottom = keep(owned, 3, other.denominator.divide(leftCancellation));
            BigInteger bottom = keep(owned, 5, leftBottom.multiply(rightBottom));
            return keep(owned, 6, new ExactRational(top, bottom));
        } catch (RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            if (owned != null) owned.close(primary);
        }
    }

    public ExactRational divide(ExactRational other) {
        var owned = Arithmetic.observe(this, other, 7);
        Throwable primary = null;
        try {
            Objects.requireNonNull(other, "other");
            if (other.isZero()) {
                throw new ArithmeticException("division by zero rational");
            }
            if (isZero()) {
                return keep(owned, 6, ZERO);
            }
            if (other.isOne()) {
                return keep(owned, 6, this);
            }
            if (other.isNegativeOne()) {
                return keep(owned, 6, negate());
            }

            BigInteger absoluteLeft = keep(owned, 0, numerator.abs());
            BigInteger absoluteRight = keep(owned, 1, other.numerator.abs());
            BigInteger numeratorCancellation = keep(owned, 0, absoluteLeft.gcd(absoluteRight));
            BigInteger denominatorCancellation = keep(owned, 1, denominator.gcd(other.denominator));
            BigInteger leftTop = keep(owned, 2, numerator.divide(numeratorCancellation));
            BigInteger rightTop = keep(owned, 3, other.denominator.divide(denominatorCancellation));
            BigInteger top = keep(owned, 4, leftTop.multiply(rightTop));
            BigInteger leftBottom = keep(owned, 2, denominator.divide(denominatorCancellation));
            BigInteger rightBottom = keep(owned, 3, other.numerator.divide(numeratorCancellation));
            BigInteger bottom = keep(owned, 5, leftBottom.multiply(rightBottom));
            return keep(owned, 6, new ExactRational(top, bottom));
        } catch (RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            if (owned != null) owned.close(primary);
        }
    }

    public ExactRational negate() {
        var owned = Arithmetic.observe(this, null, 2);
        Throwable primary = null;
        try {
            if (isZero()) {
                return keep(owned, 1, ZERO);
            }
            if (isOne()) {
                return keep(owned, 1, NEGATIVE_ONE);
            }
            if (isNegativeOne()) {
                return keep(owned, 1, ONE);
            }
            BigInteger top = keep(owned, 0, numerator.negate());
            return keep(owned, 1, new ExactRational(top, denominator));
        } catch (RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            if (owned != null) owned.close(primary);
        }
    }

    public ExactRational abs() {
        return numerator.signum() < 0 ? negate() : this;
    }

    public ExactRational reciprocal() {
        var owned = Arithmetic.observe(this, null, 1);
        Throwable primary = null;
        try {
            if (isZero()) {
                throw new ArithmeticException(
                    "zero rational has no reciprocal");
            }
            return keep(owned, 0, new ExactRational(denominator, numerator));
        } catch (RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            if (owned != null) owned.close(primary);
        }
    }

    public ExactRational pow(int exponent) {
        var owned = Arithmetic.observe(this, null, 3);
        Throwable primary = null;
        try {
            if (exponent < 0) {
                throw new IllegalArgumentException(
                    "exact rational exponent must not be negative");
            }
            if (exponent == 0) {
                return keep(owned, 2, ONE);
            }
            BigInteger top = keep(owned, 0, numerator.pow(exponent));
            BigInteger bottom = keep(owned, 1, denominator.pow(exponent));
            return keep(owned, 2, new ExactRational(top, bottom));
        } catch (RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            if (owned != null) owned.close(primary);
        }
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
        return numerator.equals(NEGATIVE_ONE.numerator)
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
        var owned = Arithmetic.observe(this, null, 4);
        Throwable primary = null;
        try {
            if (signum() < 0) {
                return keep(owned, 3, Optional.empty());
            }
            BigInteger[] top = keep(owned, 0, numerator.sqrtAndRemainder());
            BigInteger[] bottom = keep(owned, 1, denominator.sqrtAndRemainder());
            if (top[1].signum() != 0 || bottom[1].signum() != 0) {
                return keep(owned, 3, Optional.empty());
            }
            var root = keep(owned, 2, new ExactRational(top[0], bottom[0]));
            return keep(owned, 3, Optional.of(root));
        } catch (RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            if (owned != null) owned.close(primary);
        }
    }

    @JsonValue
    public String canonicalText() {
        return isInteger()
            ? numerator.toString()
            : numerator + "/" + denominator;
    }

    @Override
    public int compareTo(ExactRational other) {
        var owned = Arithmetic.observe(this, other, 5);
        Throwable primary = null;
        try {
            Objects.requireNonNull(other, "other");
            if (this == other || equals(other)) {
                return 0;
            }
            BigInteger denominatorGcd = keep(owned, 0, denominator.gcd(other.denominator));
            BigInteger leftMultiplier = keep(owned, 1, other.denominator.divide(denominatorGcd));
            BigInteger left = keep(owned, 3, numerator.multiply(leftMultiplier));
            BigInteger rightMultiplier = keep(owned, 2, denominator.divide(denominatorGcd));
            BigInteger right = keep(owned, 4, other.numerator.multiply(rightMultiplier));
            return left.compareTo(right);
        } catch (RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            if (owned != null) owned.close(primary);
        }
    }

    /** Publishes a real temporary before a debit can fail; does not charge arithmetic again. */
    private static <T> T keep(Arithmetic owned, int slot, T value) {
        if (owned != null) {
            Object previous = owned.temporaries[slot];
            owned.temporaries[slot] = value;
            try (var publication = RetainedOperation.retainCompleted(2, owned, previous)) {
                // Slot read/write and the old/new overlap are observer work, not another arithmetic debit.
            }
        }
        return value;
    }

    /** Fixed lexical scratch, absent for historical calls without a native observer. */
    private static final class Arithmetic implements RetainedGraph.View {
        private final Object[] temporaries;
        private final RetainedOperation.Frame inputs;
        private RetainedOperation.Frame frame;

        private Arithmetic(int slots, RetainedOperation.Frame inputs) {
            temporaries = new Object[slots];
            this.inputs = inputs;
        }

        private static Arithmetic observe(Object left, Object right, int slots) {
            if (!CONSTANTS_INITIALIZED) return null;
            var inputs = RetainedOperation.retain(left, right);
            if (inputs == null) return null;
            try {
                var owned = new Arithmetic(slots, inputs);
                owned.frame = RetainedOperation.retainCompleted(2, owned);
                return owned;
            } catch (RuntimeException | Error failure) {
                try { inputs.close(); }
                catch (RuntimeException | Error cleanup) {
                    if (cleanup != failure) failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        @Override public void retainedReferences(RetainedGraph.Visitor visitor) {
            visitor.reference(temporaries);
            visitor.reference(inputs);
            visitor.reference(frame);
        }

        private void close(Throwable primary) {
            try {
                close(frame, primary);
            } catch (RuntimeException | Error failure) {
                close(inputs, failure);
                throw failure;
            }
            close(inputs, primary);
        }

        private static void close(RetainedOperation.Frame frame, Throwable primary) {
            try { frame.close(); }
            catch (RuntimeException | Error cleanup) {
                if (primary == null) throw cleanup;
                if (cleanup != primary) primary.addSuppressed(cleanup);
            }
        }
    }

    @Override
    public String toString() {
        return canonicalText();
    }
}
