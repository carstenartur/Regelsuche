package de.regelsuche.polynomial;

/**
 * Receives deterministic arithmetic work units from pure polynomial values.
 *
 * <p>The polynomial core depends only on this minimal sink. Concrete budgets,
 * ledgers and experiment policies belong to algorithm and orchestration
 * modules.</p>
 */
@FunctionalInterface
public interface PolynomialWorkSink {
    void consume(String stage, long units);

    static PolynomialWorkSink none() {
        return NoOp.INSTANCE;
    }

    enum NoOp implements PolynomialWorkSink {
        INSTANCE;

        @Override
        public void consume(String stage, long units) {
            if (stage == null || stage.isBlank() || units < 0) {
                throw new IllegalArgumentException(
                    "polynomial work entry is invalid");
            }
        }
    }
}
