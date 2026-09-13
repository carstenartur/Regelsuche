package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.PolynomialWorkLedger;
import de.regelsuche.polynomial.PolynomialWorkSink;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared non-resettable work budget for exact polynomial algorithm stages.
 * Mutable bookkeeping is confined to one execution thread; published ledgers
 * are immutable snapshots and may be retained independently of this budget.
 */
final class PolynomialWorkBudget implements PolynomialWorkSink {
    private final long limit;
    private final Map<String, Long> stages = new LinkedHashMap<>();
    private long total;
    private PolynomialWorkLedger snapshot;

    PolynomialWorkBudget(long limit) {
        if (limit < 1) {
            throw new IllegalArgumentException(
                "polynomial work budget must be positive");
        }
        this.limit = limit;
    }

    long limit() {
        return limit;
    }

    @Override
    public void consume(String stage, long units) {
        if (stage == null || stage.isBlank() || units < 0) {
            throw new IllegalArgumentException(
                "polynomial work entry is invalid");
        }
        if (units == 0) {
            return;
        }
        if (total > limit - units) {
            throw new LimitReached();
        }
        total += units;
        stages.merge(stage, units, Math::addExact);
        snapshot = null;
    }

    /** Current charged work, without allocating an immutable evidence snapshot. */
    long totalWorkUnits() {
        return total;
    }

    PolynomialWorkLedger ledger() {
        if (snapshot == null) {
            snapshot = new PolynomialWorkLedger(stages);
        }
        return snapshot;
    }

    static final class LimitReached extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
