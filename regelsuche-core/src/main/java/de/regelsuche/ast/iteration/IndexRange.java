package de.regelsuche.ast.iteration;

import java.math.BigInteger;
import java.util.Objects;

/** Counted range: start + k * step, 0 <= k < count. No machine-counter wrap is implied. */
public record IndexRange(BigInteger start, BigInteger step, BigInteger count) {
    public static final int MAX_BOUND_BITS = 1024;
    public IndexRange {
        Objects.requireNonNull(start); Objects.requireNonNull(step); Objects.requireNonNull(count);
        if (start.bitLength() > MAX_BOUND_BITS || step.bitLength() > MAX_BOUND_BITS
                || count.bitLength() > MAX_BOUND_BITS || step.signum() == 0 || count.signum() < 0)
            throw new IllegalArgumentException("INVALID_OR_OVERSIZED_RANGE");
    }
    public BigInteger endExclusive() { return start.add(step.multiply(count)); }
}
