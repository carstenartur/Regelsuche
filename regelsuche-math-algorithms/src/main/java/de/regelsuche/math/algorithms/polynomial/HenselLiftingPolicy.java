package de.regelsuche.math.algorithms.polynomial;

import java.util.Objects;

/** Algorithm selection and explicit representation bounds for Hensel lifting. */
public record HenselLiftingPolicy(
    Algorithm algorithm,
    int targetExponent,
    int maxModulusBitLength,
    int maxIntermediateCoefficientBitLength
) {
    public static final int MAX_TARGET_EXPONENT = 4_096;
    public static final int MAX_MODULUS_BIT_LENGTH = 1_000_000;
    public static final int MAX_INTERMEDIATE_COEFFICIENT_BIT_LENGTH =
        1_000_000;

    public HenselLiftingPolicy {
        Objects.requireNonNull(algorithm, "algorithm");
        if (targetExponent < 1
                || targetExponent > MAX_TARGET_EXPONENT
                || maxModulusBitLength < 1
                || maxModulusBitLength > MAX_MODULUS_BIT_LENGTH
                || maxIntermediateCoefficientBitLength < 1
                || maxIntermediateCoefficientBitLength
                    > MAX_INTERMEDIATE_COEFFICIENT_BIT_LENGTH) {
            throw new IllegalArgumentException(
                "Hensel lifting policy is invalid");
        }
    }

    public static HenselLiftingPolicy linearMultifactor(
        int targetExponent,
        int maxModulusBitLength,
        int maxIntermediateCoefficientBitLength
    ) {
        return new HenselLiftingPolicy(
            Algorithm.LINEAR_MULTIFACTOR_V1,
            targetExponent,
            maxModulusBitLength,
            maxIntermediateCoefficientBitLength);
    }

    public String canonicalMaterial() {
        return algorithm.id()
            + ':' + targetExponent
            + ':' + maxModulusBitLength
            + ':' + maxIntermediateCoefficientBitLength;
    }

    public enum Algorithm {
        LINEAR_MULTIFACTOR_V1(
            "regelsuche.hensel-lifting.linear-multifactor/v1");

        private final String id;

        Algorithm(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }
}
