package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.FactorizationEngine;
import de.regelsuche.polynomial.PolynomialWorkSink;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shared non-resettable work budget for one exact polynomial algorithm. */
final class PolynomialWorkBudget implements PolynomialWorkSink {
    private final long limit;
    private final Map<String, Long> stages = new LinkedHashMap<>();
    private long total;

    PolynomialWorkBudget(long limit) {
        if (limit < 1) {
            throw new IllegalArgumentException(
                "polynomial work budget must be positive");
        }
        this.limit = limit;
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
    }

    FactorizationEngine.WorkLedger ledger() {
        return new FactorizationEngine.WorkLedger(stages);
    }

    static final class LimitReached extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
