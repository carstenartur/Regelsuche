package de.regelsuche.math.sympy;

/**
 * Noncanonical runtime diagnostics. Timing values and raw transport hashes
 * never participate in mathematical evidence or engine-result hashes.
 */
public record SymPyExecutionMetrics(
    String runtimeId,
    String runtimeVersion,
    String sympyVersion,
    String inputHash,
    String outputHash,
    String scriptHash,
    boolean coldStart,
    long initializationNanos,
    long invocationNanos,
    long backendFactorNanos,
    long backendTotalNanos
) {
    public SymPyExecutionMetrics {
        if (runtimeId == null
                || runtimeId.isBlank()
                || runtimeVersion == null
                || sympyVersion == null
                || inputHash == null
                || !inputHash.matches("sha256:[0-9a-f]{64}")
                || outputHash == null
                || !outputHash.matches("sha256:[0-9a-f]{64}")
                || scriptHash == null
                || !scriptHash.matches("sha256:[0-9a-f]{64}")
                || initializationNanos < 0
                || invocationNanos < 0
                || backendFactorNanos < 0
                || backendTotalNanos < 0
                || backendFactorNanos > backendTotalNanos) {
            throw new IllegalArgumentException(
                "SymPy execution metrics are invalid");
        }
    }
}
