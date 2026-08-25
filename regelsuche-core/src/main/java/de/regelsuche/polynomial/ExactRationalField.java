package de.regelsuche.polynomial;

import de.regelsuche.scalar.ExactRational;
import java.util.Objects;

/** Authoritative exact rational coefficient field. */
public final class ExactRationalField
        implements ExactField<ExactRational> {
    public static final String DOMAIN_ID =
        "regelsuche.coefficients.rational/v1";
    public static final ExactRationalField INSTANCE =
        new ExactRationalField();

    private ExactRationalField() {
    }

    @Override
    public String id() {
        return DOMAIN_ID;
    }

    @Override
    public ExactRational zero() {
        return ExactRational.ZERO;
    }

    @Override
    public ExactRational one() {
        return ExactRational.ONE;
    }

    @Override
    public ExactRational canonical(ExactRational value) {
        return Objects.requireNonNull(value, "value");
    }

    @Override
    public ExactRational add(
        ExactRational left,
        ExactRational right
    ) {
        return canonical(left).add(canonical(right));
    }

    @Override
    public ExactRational negate(ExactRational value) {
        return canonical(value).negate();
    }

    @Override
    public ExactRational multiply(
        ExactRational left,
        ExactRational right
    ) {
        return canonical(left).multiply(canonical(right));
    }

    @Override
    public boolean isZero(ExactRational value) {
        return canonical(value).isZero();
    }

    @Override
    public String canonicalText(ExactRational value) {
        return canonical(value).canonicalText();
    }

    @Override
    public int bitLength(ExactRational value) {
        ExactRational checked = canonical(value);
        return Math.addExact(
            checked.numerator().abs().bitLength(),
            checked.denominator().bitLength());
    }

    @Override
    public ExactRational divide(
        ExactRational dividend,
        ExactRational divisor
    ) {
        return canonical(dividend).divide(canonical(divisor));
    }
}
