package de.regelsuche.polynomial;

import java.util.Map;

/**
 * An additional, invocation-wide work authority for exact polynomial pipelines.
 * Implementations must admit a complete charge atomically before its operation
 * and must never reset consumed work. Component representation limits still
 * apply independently. A rejected charge consumes no work.
 */
public interface PolynomialWorkAuthority extends PolynomialWorkSink {
    void consume(PolynomialWorkLedger work);

    /**
     * A conservative raw ceiling for an opaque engine/verifier invocation.
     * Every raw unit is reserved as an undiscounted unit in every potentially
     * affected dimension. The caller must execute synchronously, then settle
     * the returned complete ledger before doing any further work.
     */
    long remainingOpaqueWorkUnits();

    /** Optional dispatch work, admitted and retained before an opaque request. */
    default PolynomialWorkLedger opaqueInvocationOverhead() {
        return PolynomialWorkLedger.empty();
    }

    @Override
    default void consume(String stage, long units) {
        consume(new PolynomialWorkLedger(Map.of(stage, units)));
    }

    static PolynomialWorkAuthority unbounded() {
        return Unbounded.INSTANCE;
    }

    enum Unbounded implements PolynomialWorkAuthority {
        INSTANCE;

        @Override
        public void consume(PolynomialWorkLedger work) {
            java.util.Objects.requireNonNull(work, "work");
        }

        @Override
        public void consume(String stage, long units) {
            // Keep existing, unmetered callers free of per-operation ledgers.
            PolynomialWorkSink.none().consume(stage, units);
        }

        @Override
        public long remainingOpaqueWorkUnits() {
            return Long.MAX_VALUE;
        }
    }

    /** A normal budget outcome, never a technical or mathematical failure. */
    final class LimitReached extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public LimitReached() {
            super("SHARED_POLYNOMIAL_WORK_AUTHORITY_EXHAUSTED");
        }
    }
}
