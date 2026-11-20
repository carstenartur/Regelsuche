package de.regelsuche.math.algorithms.positivity;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.scalar.ExactRational;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Bounds rational operations, dense allocations and proof size for a single call. */
final class PositivityWork {
    private static final int MAX_CELLS = 1_000_000;
    private final IterationLimits limits;
    private final BooleanSupplier cancellation;
    private final long start = System.nanoTime();
    private final long timeout;
    long used;

    PositivityWork(IterationLimits limits, BooleanSupplier cancellation) {
        this.limits = Objects.requireNonNull(limits);
        this.cancellation = Objects.requireNonNull(cancellation);
        timeout = TimeUnit.MILLISECONDS.toNanos(limits.timeoutMillis());
    }

    void charge(long count) {
        if (cancellation.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new Stop("CANCELLED");
        if (System.nanoTime() - start >= timeout) throw new Stop("TIME_LIMIT");
        if (count > limits.maximumWork() - used) throw new Stop("WORK_LIMIT");
        used += count;
    }

    void terms(long count) {
        charge(0);
        if (count > limits.maximumTerms() || count > MAX_CELLS) throw new Stop("TERM_LIMIT");
    }

    ExactRational check(ExactRational value) {
        int bits = Math.max(value.numerator().abs().bitLength(), value.denominator().bitLength());
        if (bits > IterationLimits.MAX_RUNTIME_BITS) throw new Stop("RUNTIME_BIT_LIMIT");
        charge(1L + bits / 64);
        return value;
    }

    void polynomial(SparsePolynomial source) {
        terms(source.terms().size());
        for (var coefficient : source.terms().values()) check(coefficient);
    }

    boolean samePolynomial(SparsePolynomial a, SparsePolynomial b) {
        polynomial(a);
        if (a == b) return true;
        if (!a.degrees().equals(b.degrees()) || a.terms().size() != b.terms().size()) return false;
        var it = b.terms().entrySet().iterator();
        for (var term : a.terms().entrySet()) {
            var other = it.next();
            if (!term.getKey().equals(other.getKey()) || !term.getValue().equals(check(other.getValue()))) return false;
        }
        return true;
    }

    ExactRational add(ExactRational a, ExactRational b) { return check(a.add(b)); }
    ExactRational sub(ExactRational a, ExactRational b) { return check(a.subtract(b)); }
    ExactRational mul(ExactRational a, ExactRational b) { return check(a.multiply(b)); }
    ExactRational div(ExactRational a, ExactRational b) { return check(a.divide(b)); }

    ExactRational pow(ExactRational value, int exponent) {
        ExactRational result = ExactRational.ONE;
        while (exponent > 0) {
            if ((exponent & 1) != 0) result = mul(result, value);
            exponent >>>= 1;
            if (exponent != 0) value = mul(value, value);
        }
        return result;
    }

    static final class Stop extends RuntimeException {
        Stop(String reason) { super(reason, null, false, false); }
    }
}
