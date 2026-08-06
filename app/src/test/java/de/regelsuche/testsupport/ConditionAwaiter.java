package de.regelsuche.testsupport;

import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/**
 * Bounded condition wait for asynchronous tests without fixed sleep timing.
 */
public final class ConditionAwaiter {
    private static final long MAX_PARK_NANOS = Duration.ofMillis(10).toNanos();

    private ConditionAwaiter() {
    }

    public static void await(
        Duration timeout,
        BooleanSupplier condition,
        String failureMessage
    ) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(failureMessage, "failureMessage");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }

        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0L) {
                fail(failureMessage + " within " + timeout);
            }
            LockSupport.parkNanos(Math.min(remainingNanos, MAX_PARK_NANOS));
            if (Thread.interrupted()) {
                throw new InterruptedException("interrupted while awaiting condition");
            }
        }
    }
}
