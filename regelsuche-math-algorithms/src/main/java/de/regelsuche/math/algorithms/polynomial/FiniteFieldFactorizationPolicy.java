package de.regelsuche.math.algorithms.polynomial;

import java.util.Objects;

/** Algorithm selection and algorithm-specific enumeration bound. */
public record FiniteFieldFactorizationPolicy(
    Algorithm algorithm,
    int maxEnumeratedFieldElements
) {
    public static final int MAX_FIELD_ELEMENTS = 1_000_000;

    public FiniteFieldFactorizationPolicy {
        Objects.requireNonNull(algorithm, "algorithm");
        if (maxEnumeratedFieldElements < 2
                || maxEnumeratedFieldElements > MAX_FIELD_ELEMENTS) {
            throw new IllegalArgumentException(
                "finite-field factorization policy is invalid");
        }
    }

    public static FiniteFieldFactorizationPolicy
            deterministicBerlekamp(
        int maxEnumeratedFieldElements
    ) {
        return new FiniteFieldFactorizationPolicy(
            Algorithm.DETERMINISTIC_BERLEKAMP_V1,
            maxEnumeratedFieldElements);
    }

    public String canonicalMaterial() {
        return algorithm.id()
            + ':' + maxEnumeratedFieldElements;
    }

    public enum Algorithm {
        DETERMINISTIC_BERLEKAMP_V1(
            "regelsuche.berlekamp-factorization/v1");

        private final String id;

        Algorithm(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }
}
