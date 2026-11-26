package de.regelsuche.math.algorithms.modular;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Untrusted, immutable integers and witnesses for deterministic primality proofs. */
public final class PrimeEvidence {
    static final int MAX_BITS = 4096;
    private static final int MAX_FACTORS = 256;

    private PrimeEvidence() {}

    public sealed interface Certificate permits SmallPrime, Lucas, EllipticOrder {
        BigInteger modulus();
    }

    /** Requests exact trial division in the positive int range. */
    public record SmallPrime(BigInteger modulus) implements Certificate {
        public SmallPrime { requireBounded(modulus); }
    }

    /** A claimed prime raised to its multiplicity in a factorization. */
    public record Factor(Certificate prime, int exponent) {
        public Factor { Objects.requireNonNull(prime); }
    }

    /** A candidate primitive root with a complete factorization of modulus minus one. */
    public record Lucas(BigInteger modulus, BigInteger witness, List<Factor> factors) implements Certificate {
        public Lucas {
            requireBounded(modulus);
            requireBounded(witness);
            var copy = new ArrayList<Factor>();
            for (var factor : factors) {
                if (copy.size() == MAX_FACTORS) throw new IllegalArgumentException("PRIME_FACTOR_LIMIT");
                copy.add(Objects.requireNonNull(factor));
            }
            factors = List.copyOf(copy);
        }
    }

    /** A finite point on y²=x³+ax+b and evidence for its claimed prime order. */
    public record EllipticOrder(BigInteger modulus, BigInteger a, BigInteger b, BigInteger x,
            BigInteger y, Certificate orderPrime) implements Certificate {
        public EllipticOrder {
            requireBounded(modulus);
            requireBounded(a);
            requireBounded(b);
            requireBounded(x);
            requireBounded(y);
            Objects.requireNonNull(orderPrime);
        }
    }

    static boolean bounded(BigInteger value) {
        // Reject subclasses before consulting overridable arithmetic or comparison methods.
        return value != null && value.getClass() == BigInteger.class && value.bitLength() <= MAX_BITS
                && value.abs().bitLength() <= MAX_BITS;
    }

    private static void requireBounded(BigInteger value) {
        if (!bounded(value)) throw new IllegalArgumentException("PRIME_INTEGER_LIMIT");
    }
}
