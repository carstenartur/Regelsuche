package de.regelsuche.benchmark.polynomial;

import de.regelsuche.polynomial.PolynomialWorkAuthority;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Pre-operation admission under the unchanged frozen v2 work projection.
 * A row is confined to its creating thread, including synchronous opaque calls.
 */
public final class PolynomialTheoryUtilityWorkAuthority
        implements PolynomialWorkAuthority {
    private final PolynomialTheoryUtilityExecutionInput input;
    private final Thread owner = Thread.currentThread();
    private PolynomialWorkLedger ledger = PolynomialWorkLedger.empty();
    private long primitiveWork;
    private PolynomialTheoryUtilityWorkBreakdown work =
        PolynomialTheoryUtilityWorkBreakdown.zero();

    public PolynomialTheoryUtilityWorkAuthority(
            PolynomialTheoryUtilityExecutionInput input) {
        this.input = Objects.requireNonNull(input, "input");
    }

    @Override
    public synchronized void consume(PolynomialWorkLedger charge) {
        requireOwner();
        Objects.requireNonNull(charge, "charge");
        Map<String, Long> stages = new LinkedHashMap<>(ledger.stages());
        charge.stages().forEach((stage, units) ->
            stages.merge(stage, units, Math::addExact));
        PolynomialWorkLedger proposed = new PolynomialWorkLedger(stages);
        var projected = PolynomialTheoryUtilityCanonicalWorkProjection.measure(
            PolynomialTheoryUtilityCanonicalWorkProjection.partition(primitiveWork, proposed));
        if (projected.primitiveWork() > input.admittedPrimitiveWork()
                || projected.mechanicalWork() > input.totalMechanicalWork()
                || projected.factorizationWork() > input.factorizationWork()) {
            throw new LimitReached();
        }
        ledger = proposed;
        work = projected;
    }

    public synchronized void consumePrimitive(long units) {
        requireOwner();
        if (units < 0) throw new IllegalArgumentException("negative primitive work");
        if (units > input.admittedPrimitiveWork() - primitiveWork) {
            throw new LimitReached();
        }
        primitiveWork += units;
        consume(PolynomialWorkLedger.empty());
    }

    @Override
    public synchronized long remainingOpaqueWorkUnits() {
        requireOwner();
        return Math.min(input.totalMechanicalWork() - work.mechanicalWork(),
            input.factorizationWork() - work.factorizationWork());
    }

    @Override
    public PolynomialWorkLedger opaqueInvocationOverhead() {
        return new PolynomialWorkLedger(Map.of("factorization.request-dispatch", 1L,
            "study.evidence.factorization-attempt-records", 1L));
    }

    public synchronized long remainingPrimitiveWork() {
        return input.admittedPrimitiveWork() - primitiveWork;
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("a polynomial study row cannot share concurrent work authority");
        }
    }

    public synchronized PolynomialWorkLedger ledger() {
        return ledger;
    }

    public synchronized PolynomialTheoryUtilityWorkBreakdown work() {
        return work;
    }

    public synchronized PolynomialTheoryUtilityCanonicalWorkProjection.Projection
            projection() {
        return PolynomialTheoryUtilityCanonicalWorkProjection.project(input,
            PolynomialTheoryUtilityCanonicalWorkProjection.partition(
                primitiveWork, ledger));
    }
}
