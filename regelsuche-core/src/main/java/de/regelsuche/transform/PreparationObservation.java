package de.regelsuche.transform;

import de.regelsuche.polynomial.PolynomialWorkAuthority;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Private observation of admitted work; the external authority supplies policy, never metrics. */
final class PreparationObservation implements PolynomialWorkAuthority {
    private final PolynomialWorkAuthority authority;
    private final Thread owner = Thread.currentThread();
    private final Map<String, Long> stages = new LinkedHashMap<>();
    private PolynomialWorkLedger refused;
    PreparationObservation(PolynomialWorkAuthority authority) { this.authority = Objects.requireNonNull(authority, "authority"); }
    @Override public void consume(PolynomialWorkLedger charge) {
        if (Thread.currentThread() != owner) throw new IllegalStateException("PREPARATION_OBSERVATION_THREAD_MISMATCH");
        try { authority.consume(charge); }
        catch (LimitReached failure) { refused = charge; throw failure; }
        charge.stages().forEach((stage, units) -> stages.merge(stage, units, Math::addExact));
    }
    @Override public long remainingOpaqueWorkUnits() { return authority.remainingOpaqueWorkUnits(); }
    PolynomialWorkLedger ledger() { return new PolynomialWorkLedger(stages); }
    Optional<PolynomialWorkLedger> refusedCharge() { return Optional.ofNullable(refused); }
    PolynomialWorkAuthority scope(String phase) { return phase(this, "preparation." + phase); }
    static PolynomialWorkAuthority phase(PolynomialWorkAuthority authority, String phase) {
        if (authority == PolynomialWorkAuthority.unbounded()) return authority;
        return new PolynomialWorkAuthority() {
            @Override public void consume(PolynomialWorkLedger work) {
                var prefixed = new LinkedHashMap<String, Long>();
                work.stages().forEach((stage, units) -> prefixed.put(phase + "." + stage, units));
                authority.consume(new PolynomialWorkLedger(prefixed));
            }
            @Override public long remainingOpaqueWorkUnits() { return authority.remainingOpaqueWorkUnits(); }
        };
    }
}
