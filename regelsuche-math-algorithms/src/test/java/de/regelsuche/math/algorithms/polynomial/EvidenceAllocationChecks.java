package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.PolynomialWorkLedger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/** Exercises snapshot reuse without permitting mutable or stale evidence. */
final class EvidenceAllocationChecks {
    private EvidenceAllocationChecks() { }

    static void unchangedWorkReusesAnImmutableSnapshot() {
        var budget = new PolynomialWorkBudget(12);
        var empty = budget.ledger();
        require(budget.ledger() == empty, "unchanged work must reuse its evidence snapshot");
        budget.consume("a", 4);
        var first = budget.ledger();
        require(first != empty && first.stages().equals(Map.of("a", 4L)), "new charge needs new snapshot");
        require(budget.ledger() == first, "repeated read must reuse new snapshot");
        budget.consume("zero", 0);
        rejects(IllegalArgumentException.class, () -> budget.consume("", 0));
        rejects(IllegalArgumentException.class, () -> budget.consume("a", -1));
        rejects(PolynomialWorkBudget.LimitReached.class, () -> budget.consume("b", 9));
        require(budget.ledger() == first, "zero or rejected work must not invalidate evidence");
        require(budget.totalWorkUnits() == 4 && empty.stages().isEmpty(), "retained totals and old snapshot");
        budget.consume("a", 8);
        var full = budget.ledger();
        require(full != first && full.totalWorkUnits() == 12, "same-stage charge invalidates snapshot");
        require(first.stages().equals(Map.of("a", 4L)), "old snapshot cannot change");
        rejects(UnsupportedOperationException.class, () -> full.stages().put("b", 1L));
        rejects(PolynomialWorkBudget.LimitReached.class, () -> budget.consume("a", Long.MAX_VALUE));
        require(budget.ledger() == full, "rejected overflow retains the snapshot");
        require(new PolynomialWorkBudget(12).ledger().stages().isEmpty(), "no cross-budget cache");
    }

    static void everySnapshotMatchesAnIndependentLedger() {
        var budget = new PolynomialWorkBudget(Long.MAX_VALUE);
        var expected = new LinkedHashMap<String, Long>();
        var snapshots = new ArrayList<PolynomialWorkLedger>();
        var texts = new ArrayList<String>();
        Random random = new Random(999);
        long total = 0;
        for (int i = 0; i < 400; i++) {
            String stage = "stage-" + random.nextInt(23);
            long units = random.nextInt(200);
            budget.consume(stage, units);
            if (units != 0) expected.merge(stage, units, Math::addExact);
            total += units;
            var actual = budget.ledger();
            var reference = new PolynomialWorkLedger(expected);
            require(actual.equals(reference), "ledger value mismatch");
            require(actual.canonicalMaterial().equals(reference.canonicalMaterial()), "canonical material mismatch");
            require(actual.totalWorkUnits() == total && budget.totalWorkUnits() == total, "total mismatch");
            snapshots.add(actual); texts.add(actual.canonicalMaterial());
        }
        for (int i = 0; i < snapshots.size(); i++) {
            require(snapshots.get(i).canonicalMaterial().equals(texts.get(i)), "retained snapshot mutated");
        }
        budget.consume("ceiling", Long.MAX_VALUE - total);
        var full = budget.ledger();
        require(full.totalWorkUnits() == Long.MAX_VALUE, "exact long ceiling");
        rejects(PolynomialWorkBudget.LimitReached.class, () -> budget.consume("overflow", 1));
        require(budget.ledger() == full, "overflow changed the cache");
    }

    private static void rejects(Class<? extends Throwable> type, Runnable action) {
        try { action.run(); } catch (Throwable error) {
            if (type.isInstance(error)) return;
            throw new AssertionError("unexpected exception", error);
        }
        throw new AssertionError("expected " + type.getName());
    }

    private static void require(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }

    public static void main(String[] args) {
        unchangedWorkReusesAnImmutableSnapshot();
        everySnapshotMatchesAnIndependentLedger();
        System.out.println("2 immutable evidence allocation checks passed");
    }
}
