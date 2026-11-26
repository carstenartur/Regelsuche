package de.regelsuche.math.algorithms.modular;

import java.math.BigInteger;
import java.util.Objects;

/** Conditional specialization in Fp[i], i²=-1, over a proved prime p congruent to 3 modulo 4. */
public record QuadraticRootContract(String sourceIdentity, BigInteger modulus, PrimeEvidence.Certificate primeEvidence,
        QuadraticRootProgram original, Observation observation) {
    public enum Observation { EXACT_PAIR, UP_TO_JOINT_SIGN }

    public QuadraticRootContract {
        Objects.requireNonNull(sourceIdentity);
        if (sourceIdentity.length() > 1024 || sourceIdentity.isBlank()) throw new IllegalArgumentException("FIELD_SOURCE_ID");
        SharedPowerPlan.bounded(modulus);
        Objects.requireNonNull(primeEvidence);
        Objects.requireNonNull(original);
        Objects.requireNonNull(observation);
    }

    /** The complete runtime input guard. Certification of the fixed modulus is a separate proof obligation. */
    public static boolean accepts(BigInteger modulus, BigInteger a, BigInteger b, BigInteger g) {
        if (modulus == null || modulus.getClass() != BigInteger.class || modulus.signum() <= 0
                || modulus.bitLength() > SharedPowerPlan.MAX_BITS) return false;
        if (!canonical(a, modulus) || !canonical(b, modulus) || !canonical(g, modulus) || b.signum() == 0) return false;
        return g.multiply(g).subtract(a.multiply(a)).subtract(b.multiply(b)).mod(modulus).signum() == 0;
    }

    private static boolean canonical(BigInteger scalar, BigInteger modulus) {
        return scalar != null && scalar.getClass() == BigInteger.class && scalar.signum() >= 0 && scalar.compareTo(modulus) < 0;
    }
}
