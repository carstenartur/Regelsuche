package de.regelsuche.math.algorithms.polynomial;

import java.util.Objects;

/**
 * Explicit search, representation and precision limits for deterministic
 * Zassenhaus recombination.
 */
public record ZassenhausRecombinationPolicy(
    Algorithm algorithm,
    long maxSubsetCandidates,
    long maxLeadingDivisorTests,
    int maxAcceptedFactors,
    int maxIntermediateCoefficientBitLength,
    int maxHenselExponent,
    int maxModulusBitLength
) {
    public static final long MAX_SUBSET_CANDIDATES = 10_000_000L;
    public static final long MAX_LEADING_DIVISOR_TESTS = 10_000_000L;
    public static final int MAX_ACCEPTED_FACTORS = 100_000;
    public static final int MAX_INTERMEDIATE_COEFFICIENT_BIT_LENGTH =
        1_000_000;
    public static final int MAX_HENSEL_EXPONENT =
        HenselLiftingPolicy.MAX_TARGET_EXPONENT;
    public static final int MAX_MODULUS_BIT_LENGTH =
        HenselLiftingPolicy.MAX_MODULUS_BIT_LENGTH;

    public ZassenhausRecombinationPolicy {
        Objects.requireNonNull(algorithm, "algorithm");
        if (maxSubsetCandidates < 1
                || maxSubsetCandidates > MAX_SUBSET_CANDIDATES
                || maxLeadingDivisorTests < 1
                || maxLeadingDivisorTests > MAX_LEADING_DIVISOR_TESTS
                || maxAcceptedFactors < 1
                || maxAcceptedFactors > MAX_ACCEPTED_FACTORS
                || maxIntermediateCoefficientBitLength < 1
                || maxIntermediateCoefficientBitLength
                    > MAX_INTERMEDIATE_COEFFICIENT_BIT_LENGTH
                || maxHenselExponent < 1
                || maxHenselExponent > MAX_HENSEL_EXPONENT
                || maxModulusBitLength < 1
                || maxModulusBitLength > MAX_MODULUS_BIT_LENGTH) {
            throw new IllegalArgumentException(
                "Zassenhaus recombination policy is invalid");
        }
    }

    public static ZassenhausRecombinationPolicy boundedDefaults() {
        return new ZassenhausRecombinationPolicy(
            Algorithm.DETERMINISTIC_SUBSET_SEARCH_V1,
            250_000,
            250_000,
            4_096,
            65_536,
            4_096,
            65_536);
    }

    public String canonicalMaterial() {
        return algorithm.id()
            + ':' + maxSubsetCandidates
            + ':' + maxLeadingDivisorTests
            + ':' + maxAcceptedFactors
            + ':' + maxIntermediateCoefficientBitLength
            + ':' + maxHenselExponent
            + ':' + maxModulusBitLength;
    }

    public enum Algorithm {
        DETERMINISTIC_SUBSET_SEARCH_V1(
            "regelsuche.zassenhaus-recombination.subset-search/v1");

        private final String id;

        Algorithm(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }
}
