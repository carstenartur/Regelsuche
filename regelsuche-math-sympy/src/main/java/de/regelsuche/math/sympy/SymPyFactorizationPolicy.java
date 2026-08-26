package de.regelsuche.math.sympy;

import java.time.Duration;
import java.util.Objects;

/**
 * Semantic version pin and explicit resource limits shared by embedded and
 * process-based SymPy factorization adapters.
 */
public record SymPyFactorizationPolicy(
    String expectedSymPyVersion,
    Duration timeout,
    int maxInputBytes,
    int maxOutputBytes,
    int maxFactors,
    int maxTermsPerFactor,
    int maxTotalFactorTerms,
    int maxCoefficientBitLength
) {
    public static final String PINNED_SYMPY_VERSION = "1.14.0";
    public static final int MAX_IO_BYTES = 16 * 1024 * 1024;
    public static final int MAX_FACTORS = 4_096;
    public static final int MAX_TERMS = 1_000_000;
    public static final int MAX_COEFFICIENT_BITS = 1_000_000;

    public SymPyFactorizationPolicy {
        if (expectedSymPyVersion == null
                || expectedSymPyVersion.isBlank()) {
            throw new IllegalArgumentException(
                "expectedSymPyVersion must not be blank");
        }
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.compareTo(Duration.ofMillis(100)) < 0
                || timeout.compareTo(Duration.ofMinutes(5)) > 0
                || maxInputBytes < 256
                || maxInputBytes > MAX_IO_BYTES
                || maxOutputBytes < 256
                || maxOutputBytes > MAX_IO_BYTES
                || maxFactors < 1
                || maxFactors > MAX_FACTORS
                || maxTermsPerFactor < 1
                || maxTermsPerFactor > MAX_TERMS
                || maxTotalFactorTerms < maxTermsPerFactor
                || maxTotalFactorTerms > MAX_TERMS
                || maxCoefficientBitLength < 1
                || maxCoefficientBitLength > MAX_COEFFICIENT_BITS) {
            throw new IllegalArgumentException(
                "SymPy factorization policy is invalid");
        }
        expectedSymPyVersion = expectedSymPyVersion.trim();
    }

    public static SymPyFactorizationPolicy pinned() {
        return new SymPyFactorizationPolicy(
            PINNED_SYMPY_VERSION,
            Duration.ofSeconds(60),
            2 * 1024 * 1024,
            8 * 1024 * 1024,
            1_024,
            100_000,
            250_000,
            4_096);
    }

    String canonicalMaterial() {
        StringBuilder result = new StringBuilder();
        SymPyEvidence.append(result, expectedSymPyVersion);
        SymPyEvidence.append(result, Long.toString(timeout.toMillis()));
        SymPyEvidence.append(result, Integer.toString(maxInputBytes));
        SymPyEvidence.append(result, Integer.toString(maxOutputBytes));
        SymPyEvidence.append(result, Integer.toString(maxFactors));
        SymPyEvidence.append(result, Integer.toString(maxTermsPerFactor));
        SymPyEvidence.append(result, Integer.toString(maxTotalFactorTerms));
        SymPyEvidence.append(result, Integer.toString(maxCoefficientBitLength));
        return result.toString();
    }
}
