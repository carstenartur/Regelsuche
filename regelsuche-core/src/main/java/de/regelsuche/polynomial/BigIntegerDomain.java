package de.regelsuche.polynomial;

import java.math.BigInteger;
import java.util.Objects;

/** Authoritative integer coefficient domain. */
public final class BigIntegerDomain implements GcdDomain<BigInteger> {
    public static final String DOMAIN_ID =
        "regelsuche.coefficients.integer/v1";
    public static final BigIntegerDomain INSTANCE =
        new BigIntegerDomain();

    private BigIntegerDomain() {
    }

    @Override
    public String id() {
        return DOMAIN_ID;
    }

    @Override
    public BigInteger characteristic() {
        return BigInteger.ZERO;
    }

    @Override
    public BigInteger fromInteger(BigInteger value) {
        return canonical(value);
    }

    @Override
    public BigInteger zero() {
        return BigInteger.ZERO;
    }

    @Override
    public BigInteger one() {
        return BigInteger.ONE;
    }

    @Override
    public BigInteger canonical(BigInteger value) {
        return Objects.requireNonNull(value, "value");
    }

    @Override
    public BigInteger add(BigInteger left, BigInteger right) {
        return canonical(left).add(canonical(right));
    }

    @Override
    public BigInteger negate(BigInteger value) {
        return canonical(value).negate();
    }

    @Override
    public BigInteger multiply(BigInteger left, BigInteger right) {
        return canonical(left).multiply(canonical(right));
    }

    @Override
    public boolean isZero(BigInteger value) {
        return canonical(value).signum() == 0;
    }

    @Override
    public String canonicalText(BigInteger value) {
        return canonical(value).toString();
    }

    @Override
    public int bitLength(BigInteger value) {
        return canonical(value).abs().bitLength();
    }

    @Override
    public BigInteger gcd(BigInteger left, BigInteger right) {
        return canonical(left).abs().gcd(canonical(right).abs());
    }

    @Override
    public BigInteger divideExact(
        BigInteger dividend,
        BigInteger divisor
    ) {
        BigInteger checkedDivisor = canonical(divisor);
        if (checkedDivisor.signum() == 0) {
            throw new ArithmeticException(
                "integer exact division by zero");
        }
        BigInteger[] quotientAndRemainder = canonical(dividend)
            .divideAndRemainder(checkedDivisor);
        if (quotientAndRemainder[1].signum() != 0) {
            throw new ArithmeticException(
                "integer division is not exact");
        }
        return quotientAndRemainder[0];
    }
}
