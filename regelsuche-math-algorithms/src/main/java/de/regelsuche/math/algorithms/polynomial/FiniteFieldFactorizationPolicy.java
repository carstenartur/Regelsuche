package de.regelsuche.math.algorithms.polynomial;

import java.util.Objects;

/** Algorithm selection and algorithm-specific resource bounds. */
public record FiniteFieldFactorizationPolicy(
    Algorithm algorithm,
    int maxEnumeratedFieldElements,
    long maxMatrixCells
) {
    public static final int MAX_FIELD_ELEMENTS = 1_000_000;
    public static final long MAX_MATRIX_CELLS = 16_000_000L;

    public FiniteFieldFactorizationPolicy {
        Objects.requireNonNull(algorithm, "algorithm");
        if (maxEnumeratedFieldElements < 2
                || maxEnumeratedFieldElements > MAX_FIELD_ELEMENTS
                || maxMatrixCells < 1
                || maxMatrixCells > MAX_MATRIX_CELLS) {
            throw new IllegalArgumentException(
                "finite-field factorization policy is invalid");
        }
    }

    public static FiniteFieldFactorizationPolicy
            deterministicBerlekamp(
        int maxEnumeratedFieldElements,
        long maxMatrixCells
    ) {
        return new FiniteFieldFactorizationPolicy(
            Algorithm.DETERMINISTIC_BERLEKAMP_V1,
            maxEnumeratedFieldElements,
            maxMatrixCells);
    }

    public boolean permitsMatrixDegree(int degree) {
        return degree >= 0
            && (long) degree * degree <= maxMatrixCells;
    }

    public String canonicalMaterial() {
        return algorithm.id()
            + ':' + maxEnumeratedFieldElements
            + ':' + maxMatrixCells;
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
