package de.regelsuche.polynomial;

import java.math.BigInteger;
import java.util.Objects;

/**
 * BigInteger modulus with an explicit prime-field assumption.
 *
 * <p>{@link #assumingPrime(BigInteger)} does not prove primality. Algorithms
 * must retain that hypothesis in their result. Only {@link #of(int)} supplies
 * the existing deterministic int-range primality guarantee. The guarantee is
 * part of the domain identity, so evidence cannot silently discard it.</p>
 */
public final class BigPrimeField implements ExactField<BigInteger> {
    public enum Primality { DETERMINISTIC_INT, ASSUMED_PRIME }

    private final BigInteger modulus;
    private final Primality primality;
    private final String id;

    private BigPrimeField(BigInteger modulus, Primality primality) {
        this.modulus = Objects.requireNonNull(modulus, "modulus");
        if (modulus.compareTo(BigInteger.TWO) < 0) {
            throw new IllegalArgumentException("prime modulus must be at least two");
        }
        if ((!modulus.testBit(0) && !modulus.equals(BigInteger.TWO))
                || (modulus.bitLength() <= 31 && !PrimeField.isPrimeModulus(modulus.intValueExact()))) {
            throw new IllegalArgumentException("composite prime-field modulus");
        }
        this.primality = Objects.requireNonNull(primality, "primality");
        id = "regelsuche.coefficients.big-prime-field/v1/p=" + modulus + "/" + primality;
    }

    public static BigPrimeField of(int prime) {
        return new BigPrimeField(PrimeField.of(prime).modulus(), Primality.DETERMINISTIC_INT);
    }

    /** Declare the hypothesis that modulus is prime; no probable-prime test is a proof. */
    public static BigPrimeField assumingPrime(BigInteger modulus) {
        return new BigPrimeField(modulus, Primality.ASSUMED_PRIME);
    }

    public BigInteger modulus() { return modulus; }
    public Primality primality() { return primality; }
    @Override public String id() { return id; }
    @Override public BigInteger characteristic() { return modulus; }
    @Override public BigInteger zero() { return BigInteger.ZERO; }
    @Override public BigInteger one() { return BigInteger.ONE; }
    @Override public BigInteger fromInteger(BigInteger value) { return canonical(value); }

    @Override public BigInteger canonical(BigInteger value) {
        Objects.requireNonNull(value, "value");
        return value.signum() >= 0 && value.compareTo(modulus) < 0 ? value : value.mod(modulus);
    }

    @Override public BigInteger add(BigInteger left, BigInteger right) {
        BigInteger sum = canonical(left).add(canonical(right));
        return sum.compareTo(modulus) >= 0 ? sum.subtract(modulus) : sum;
    }

    @Override public BigInteger negate(BigInteger value) {
        BigInteger residue = canonical(value);
        return residue.signum() == 0 ? BigInteger.ZERO : modulus.subtract(residue);
    }

    @Override public BigInteger multiply(BigInteger left, BigInteger right) {
        return canonical(left).multiply(canonical(right)).mod(modulus);
    }

    @Override public BigInteger divide(BigInteger dividend, BigInteger divisor) {
        BigInteger residue = canonical(divisor);
        if (residue.signum() == 0) throw new ArithmeticException("division by zero in prime field");
        return canonical(dividend).multiply(residue.modInverse(modulus)).mod(modulus);
    }

    @Override public boolean isZero(BigInteger value) { return canonical(value).signum() == 0; }
    @Override public String canonicalText(BigInteger value) { return canonical(value).toString(); }
    @Override public int bitLength(BigInteger value) { return canonical(value).bitLength(); }

    @Override public boolean equals(Object other) {
        return this == other || other instanceof BigPrimeField field
            && modulus.equals(field.modulus) && primality == field.primality;
    }
    @Override public int hashCode() { return Objects.hash(modulus, primality); }
    @Override public String toString() { return "F_" + modulus + "[" + primality + "]"; }
}
