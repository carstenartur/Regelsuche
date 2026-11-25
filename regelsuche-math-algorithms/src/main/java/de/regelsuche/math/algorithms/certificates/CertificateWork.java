package de.regelsuche.math.algorithms.certificates;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Per-call accounting for bounded exact certificate construction and verification. */
public final class CertificateWork {
    private static final long MAX_CELLS = 1_000_000;
    private final IterationLimits limits;
    private final BooleanSupplier cancellation;
    private final long start = System.nanoTime();
    private final long timeoutNanos;
    private long used;

    public CertificateWork(IterationLimits limits, BooleanSupplier cancellation) {
        this.limits = Objects.requireNonNull(limits);
        this.cancellation = Objects.requireNonNull(cancellation);
        timeoutNanos = TimeUnit.MILLISECONDS.toNanos(limits.timeoutMillis());
    }

    public void charge(long count) {
        if (count < 0) throw new IllegalArgumentException("NEGATIVE_WORK");
        if (cancellation.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new Stop("CANCELLED");
        if (System.nanoTime() - start >= timeoutNanos) throw new Stop("TIME_LIMIT");
        chargeCompleted(count);
    }

    /** Post a nested operation's already incurred work before observing cancellation or deadline at return. */
    public void chargeCompleted(long count) {
        if (count < 0) throw new IllegalArgumentException("NEGATIVE_WORK");
        if (count > limits.maximumWork() - used) throw new Stop("WORK_LIMIT");
        used += count;
    }

    /** Check a table, matrix or accumulated retained certificate size before allocation. */
    public void cells(long count) {
        if (count < 0) throw new IllegalArgumentException("NEGATIVE_CELLS");
        charge(0);
        if (count > limits.maximumTerms() || count > MAX_CELLS) throw new Stop("TERM_LIMIT");
    }

    public ExactRational check(ExactRational value) {
        Objects.requireNonNull(value);
        int bits = Math.max(value.numerator().abs().bitLength(), value.denominator().bitLength());
        checkBits(bits);
        return value;
    }

    public BigInteger check(BigInteger value) {
        Objects.requireNonNull(value);
        checkBits(value.abs().bitLength());
        return value;
    }

    private void checkBits(int bits) {
        charge(0);
        if (bits > IterationLimits.MAX_RUNTIME_BITS) throw new Stop("RUNTIME_BIT_LIMIT");
        charge(1L + bits / Long.SIZE);
    }

    public ExactRational add(ExactRational a, ExactRational b) {
        charge(1); check(a); check(b); return check(a.add(b));
    }

    public ExactRational sub(ExactRational a, ExactRational b) {
        charge(1); check(a); check(b); return check(a.subtract(b));
    }

    public ExactRational mul(ExactRational a, ExactRational b) {
        charge(1); check(a); check(b); return check(a.multiply(b));
    }

    public ExactRational div(ExactRational a, ExactRational b) {
        charge(1); check(a); check(b); return check(a.divide(b));
    }

    public long used() { return used; }

    /** Remaining budget for a nested independent checker; its reported work must be charged back. */
    public IterationLimits remainingLimits() {
        charge(0);
        long remaining = limits.maximumWork() - used;
        if (remaining == 0) throw new Stop("WORK_LIMIT");
        long nanos = timeoutNanos - (System.nanoTime() - start);
        if (nanos <= 0) throw new Stop("TIME_LIMIT");
        long millis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos));
        return new IterationLimits(remaining, limits.maximumTerms(), millis);
    }

    /** Reserve caller-owned cells that stay live during a nested search or verification. */
    public IterationLimits remainingLimits(long retainedCells) {
        cells(retainedCells);
        var remaining = remainingLimits();
        long terms = Math.min(remaining.maximumTerms(), MAX_CELLS) - retainedCells;
        if (terms < 1) throw new Stop("TERM_LIMIT");
        return new IterationLimits(remaining.maximumWork(), (int) terms, remaining.timeoutMillis());
    }

    /** A resource boundary, never evidence for or against a mathematical statement. */
    public static final class Stop extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public Stop(String reason) { super(reason, null, false, false); }
    }
}
