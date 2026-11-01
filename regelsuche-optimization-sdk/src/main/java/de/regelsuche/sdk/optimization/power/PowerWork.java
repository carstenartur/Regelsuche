package de.regelsuche.sdk.optimization.power;

import de.regelsuche.sdk.optimization.*;
import java.math.BigInteger;
import java.util.Objects;

/** One ledger and deadline cover preparation, synthesis and replay in each public call. */
final class PowerWork {
    static final class Unavailable extends RuntimeException { Unavailable(String reason) { super(reason); } }
    final OptimizationBudget budget;
    private final CancellationToken cancellation;
    private final long started=System.nanoTime(), timeoutNanos;
    long used;
    PowerWork(OptimizationBudget budget,CancellationToken cancellation) {
        this.budget=Objects.requireNonNull(budget); this.cancellation=Objects.requireNonNull(cancellation);
        timeoutNanos=budget.timeoutMillis()>Long.MAX_VALUE/1_000_000 ? Long.MAX_VALUE : budget.timeoutMillis()*1_000_000;
    }
    void charge(long amount) {
        if(cancellation.isCancelled() || Thread.currentThread().isInterrupted()) throw new Unavailable("CANCELLED");
        if(amount<0 || amount>budget.maximumWork()-used) throw new Unavailable("WORK_LIMIT");
        if(System.nanoTime()-started>=timeoutNanos) throw new Unavailable("TIME_LIMIT");
        used+=amount;
    }
    void exponent(BigInteger exponent) {
        charge(1);
        try { FixedPowerPlan.boundedExponent(exponent); }
        catch(IllegalArgumentException invalid) { throw new Unavailable(invalid.getMessage()); }
        charge(1L+(exponent.bitLength()+63)/64);
    }
    void identifier(String id) {
        charge(0);
        if(id==null || id.length()>UnitInverseReusePlan.MAX_BINDING_ID_LENGTH) throw new Unavailable("BINDING_ID_LIMIT");
        // Reserve all bounded content inspection before isBlank, even for invalid IDs.
        charge(1L+id.length());
        if(id.isBlank()) throw new Unavailable("BLANK_BINDING_ID");
    }
}
