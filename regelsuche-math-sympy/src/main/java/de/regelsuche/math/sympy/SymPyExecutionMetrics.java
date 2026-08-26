package de.regelsuche.math.sympy;

/**
 * Noncanonical runtime diagnostics. Timing values never participate in
 * mathematical evidence or engine-result hashes.
 */
public record SymPyExecutionMetrics(
    String runtimeId,
    String runtimeVersion,
    String sympyVersion,
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
