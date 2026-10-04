package de.regelsuche.sdk.optimization;
/** Work is deterministic charged work; wall time is a separate best-effort interruption bound. */
public record OptimizationBudget(long maximumWork, int maximumStates, int maximumCandidates, long timeoutMillis) {
    public static final OptimizationBudget DEFAULT = new OptimizationBudget(2_000_000, 128, 32, 5_000);
    public OptimizationBudget {
        if (maximumWork < 1 || maximumStates < 1 || maximumCandidates < 1 || maximumCandidates > 1024 || timeoutMillis < 1)
            throw new IllegalArgumentException("INVALID_OPTIMIZATION_BUDGET");
    }
}
