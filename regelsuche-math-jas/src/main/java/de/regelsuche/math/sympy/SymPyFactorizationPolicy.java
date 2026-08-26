package de.regelsuche.math.sympy;

import java.time.Duration;
import java.util.Objects;

/** Runtime placement and explicit resource limits for one SymPy invocation. */
public record SymPyFactorizationPolicy(
    String pythonExecutable,
    String expectedSymPyVersion,
    Duration timeout,
    int maxInputBytes,
    int maxStdoutBytes,
    int maxStderrBytes,
    int maxFactors,
    int maxTermsPerFactor
) {
    public static final String PINNED_SYMPY_VERSION = "1.14.0";
    public static final int MAX_IO_BYTES = 16 * 1024 * 1024;
    public static final int MAX_FACTORS = 4_096;
    public static final int MAX_TERMS_PER_FACTOR = 1_000_000;

    public SymPyFactorizationPolicy {
        if (pythonExecutable == null || pythonExecutable.isBlank()) {
            throw new IllegalArgumentException(
                "pythonExecutable must not be blank");
        }
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
                || maxStdoutBytes < 256
                || maxStdoutBytes > MAX_IO_BYTES
                || maxStderrBytes < 256
                || maxStderrBytes > MAX_IO_BYTES
                || maxFactors < 1
                || maxFactors > MAX_FACTORS
                || maxTermsPerFactor < 1
                || maxTermsPerFactor > MAX_TERMS_PER_FACTOR) {
            throw new IllegalArgumentException(
                "SymPy factorization policy is invalid");
        }
        pythonExecutable = pythonExecutable.trim();
        expectedSymPyVersion = expectedSymPyVersion.trim();
    }

    public static SymPyFactorizationPolicy pinned(String pythonExecutable) {
        return new SymPyFactorizationPolicy(
            pythonExecutable,
            PINNED_SYMPY_VERSION,
            Duration.ofSeconds(20),
            2 * 1024 * 1024,
            8 * 1024 * 1024,
            256 * 1024,
            1_024,
            100_000);
    }

    public static SymPyFactorizationPolicy pinnedFromEnvironment() {
        return pinned(System.getenv().getOrDefault(
            "REGELSUCHE_SYMPY_PYTHON",
            "python3"));
    }

    String canonicalMaterial() {
        StringBuilder result = new StringBuilder();
        SymPyEvidence.append(result, expectedSymPyVersion);
        SymPyEvidence.append(result, Long.toString(timeout.toMillis()));
        SymPyEvidence.append(result, Integer.toString(maxInputBytes));
        SymPyEvidence.append(result, Integer.toString(maxStdoutBytes));
        SymPyEvidence.append(result, Integer.toString(maxStderrBytes));
        SymPyEvidence.append(result, Integer.toString(maxFactors));
        SymPyEvidence.append(result, Integer.toString(maxTermsPerFactor));
        return result.toString();
    }
}
