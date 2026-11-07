package de.regelsuche.math.algorithms.iteration;

/** Resource limits for bounded, exact iteration arithmetic. */
public record IterationLimits(long maximumWork, int maximumTerms, long timeoutMillis) {
    public static final int MAX_RUNTIME_BITS = 65_536;

    public IterationLimits {
        if (maximumWork < 1 || maximumTerms < 1 || timeoutMillis < 1) {
            throw new IllegalArgumentException("INVALID_ITERATION_LIMITS");
        }
    }

    public IterationLimits withMaximumWork(long work) {
        return new IterationLimits(work, maximumTerms, timeoutMillis);
    }
}
