package de.regelsuche.polynomial;

import java.math.BigInteger;
import java.util.Objects;

/**
 * Exact prime field over canonical {@link BigInteger} residues.
 *
 * <p>The modulus is validated deterministically in the supported positive
 * {@code int} range. The modulus is part of the stable coefficient-domain ID,
 * so polynomial rings over different prime fields cannot compare equal.</p>
 */
public final class PrimeField implements ExactField<BigInteger> {
    private static final String ID_PREFIX =
        "regelsuche.coefficients.prime-field/v1/p=";

    private final int prime;
    private final BigInteger modulus;
    private final String id;

    private PrimeField(int prime) {
        if (!isPrime(prime)) {
            throw new IllegalArgumentException(
                "prime-field modulus must be prime");
        }
        this.prime = prime;
        modulus = BigInteger.valueOf(prime);
        id = ID_PREFIX + prime;
    }

    public static PrimeField of(int prime) {
        return new PrimeField(prime);
    }

    public int prime() {
        return prime;
    }

    public BigInteger modulus() {
        return modulus;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public BigInteger characteristic() {
        return modulus;
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
        return Objects.requireNonNull(value, "value").mod(modulus);
    }

    @Override
    public BigInteger add(BigInteger left, BigInteger right) {
        return canonical(left).add(canonical(right)).mod(modulus);
    }

    @Override
    public BigInteger negate(BigInteger value) {
        BigInteger canonical = canonical(value);
        return canonical.signum() == 0
            ? BigInteger.ZERO
            : modulus.subtract(canonical);
    }

    @Override
    public BigInteger multiply(BigInteger left, BigInteger right) {
        return canonical(left).multiply(canonical(right)).mod(modulus);
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
        return canonical(value).bitLength();
    }

    @Override
    public BigInteger divide(
        BigInteger dividend,
        BigInteger divisor
    ) {
        BigInteger canonicalDivisor = canonical(divisor);
        if (canonicalDivisor.signum() == 0) {
            throw new ArithmeticException(
                "division by zero in prime field");
        }
        return canonical(dividend)
            .multiply(canonicalDivisor.modInverse(modulus))
            .mod(modulus);
    }

    private static boolean isPrime(int candidate) {
        if (candidate == 2) {
            return true;
        }
        if (candidate < 2 || (candidate & 1) == 0) {
            return false;
        }
        for (int divisor = 3;
                (long) divisor * divisor <= candidate;
                divisor += 2) {
            if (candidate % divisor == 0) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
            || other instanceof PrimeField field
                && prime == field.prime;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(prime);
    }

    @Override
    public String toString() {
        return "F_" + prime;
    }
}
