package de.regelsuche.solver.portfolio;

/** Cooperative cancellation checked before and during every backend invocation. */
@FunctionalInterface
public interface CancellationToken {
    boolean isCancelled();

    static CancellationToken none() {
        return () -> false;
    }
}
