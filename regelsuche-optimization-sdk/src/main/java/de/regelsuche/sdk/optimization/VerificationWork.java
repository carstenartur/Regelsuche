package de.regelsuche.sdk.optimization;

/** Shared deterministic checker accounting and cooperative interruption. */
final class VerificationWork {
    static final class Stopped extends RuntimeException {
        final boolean cancelled;
        Stopped(boolean cancelled) { super(cancelled ? "CANCELLED" : "OPTIMIZATION_BUDGET_EXCEEDED"); this.cancelled = cancelled; }
    }
    private final CancellationToken token;
    private final long maximum;
    private final long started = System.nanoTime();
    private final long timeoutNanos;
    private long work;
    VerificationWork(OptimizationRequest request, CancellationToken token) {
        this.token = java.util.Objects.requireNonNull(token);
        maximum = request.budget().maximumWork();
        timeoutNanos = request.budget().timeoutMillis() > Long.MAX_VALUE / 1_000_000 ? Long.MAX_VALUE : request.budget().timeoutMillis() * 1_000_000;
    }
    void charge(long amount) {
        if (token.isCancelled() || Thread.currentThread().isInterrupted()) throw new Stopped(true);
        if (amount < 0 || amount > maximum - work || System.nanoTime() - started >= timeoutNanos) throw new Stopped(false);
        work += amount;
    }
    long used() { return work; }
}
