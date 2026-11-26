package de.regelsuche.math.algorithms.modular;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Bounded, untrusted schedules for powers of one group element. */
public final class SharedPowerPlan {
    public static final int MAX_BITS = 4096;
    public static final int MAX_OUTPUTS = 64;
    public static final int MAX_STEPS = 4096;

    private SharedPowerPlan() {}

    /** Requires y^k=(x^e)^k. Only k=1 requires equality of the raw values. */
    public record Output(BigInteger exponent, BigInteger observationPower) {
        public Output {
            bounded(exponent);
            positive(observationPower);
        }
    }

    /**
     * Conditional group assumption x^n=1; n need not be the exact order.
     * Each observation power must divide n. This neither establishes primality
     * nor authorizes a normalization or a correlation between output signs.
     */
    public record Request(BigInteger orderMultiple, List<Output> outputs) {
        public Request {
            positive(orderMultiple);
            outputs = copy(outputs, MAX_OUTPUTS);
            if (outputs.isEmpty()) throw new IllegalArgumentException("EMPTY_OUTPUTS");
            for (var output : outputs) {
                if (!orderMultiple.mod(output.observationPower()).equals(BigInteger.ZERO)) {
                    throw new IllegalArgumentException("OBSERVATION_MUST_DIVIDE_ORDER");
                }
            }
        }
    }

    public enum Operation { POWER, MULTIPLY, INVERSE }

    /** Node 0 is 1, node 1 is x; step i produces node i+2. POWER always powers x. */
    public record Step(Operation operation, int left, int right, BigInteger exponent) {
        public Step {
            Objects.requireNonNull(operation);
            bounded(exponent);
            if (operation == Operation.POWER) {
                if (exponent.signum() < 0 || left != 1 || right != 0) {
                    throw new IllegalArgumentException("INVALID_POWER_STEP");
                }
            } else if (exponent.signum() != 0 || operation == Operation.INVERSE && right != 0) {
                throw new IllegalArgumentException("INVALID_STEP_ARGUMENTS");
            }
        }

        public static Step power(BigInteger exponent) { return new Step(Operation.POWER, 1, 0, exponent); }
        public static Step multiply(int left, int right) { return new Step(Operation.MULTIPLY, left, right, BigInteger.ZERO); }
        public static Step inverse(int value) { return new Step(Operation.INVERSE, value, 0, BigInteger.ZERO); }
    }

    public record Schedule(List<Step> steps, List<Integer> outputs) {
        public Schedule {
            steps = copy(steps, MAX_STEPS);
            outputs = copy(outputs, MAX_OUTPUTS);
        }
    }

    /** Source-bound program evidence. Recheck against the expected request before accepting it. */
    public record Certificate(Request source, Schedule schedule) {
        public Certificate {
            Objects.requireNonNull(source);
            Objects.requireNonNull(schedule);
        }
    }

    /** Explicit operation weights; binary-chain estimates do not model BigInteger's actual window algorithm. */
    public record Weights(long multiplication, long squaring, long inverse) {
        public Weights {
            for (long value : new long[]{multiplication, squaring, inverse}) {
                if (value < 1 || value > 1_000_000_000L) throw new IllegalArgumentException("INVALID_COST_WEIGHT");
            }
        }
    }

    /** Outer calls plus separate binary-chain estimates inside POWER; none are measured runtime counts. */
    public record Cost(int powerCalls, int multiplications, int squarings, int inversions,
            int estimatedPowerMultiplications, int estimatedPowerSquarings, long weighted) {}

    static long powerCost(BigInteger exponent, Weights weights) {
        return Math.max(0, exponent.bitLength() - 1L) * weights.squaring()
                + Math.max(0, exponent.bitCount() - 1L) * weights.multiplication();
    }

    static void bounded(BigInteger value) {
        if (value == null || value.getClass() != BigInteger.class || value.abs().bitLength() > MAX_BITS) {
            throw new IllegalArgumentException("POWER_INTEGER_LIMIT");
        }
    }

    private static void positive(BigInteger value) {
        bounded(value);
        if (value.signum() <= 0) throw new IllegalArgumentException("POSITIVE_POWER_PARAMETER_REQUIRED");
    }

    private static <T> List<T> copy(List<T> values, int maximum) {
        Objects.requireNonNull(values);
        var result = new ArrayList<T>();
        for (var value : values) {
            if (result.size() == maximum) throw new IllegalArgumentException("POWER_SIZE_LIMIT");
            result.add(Objects.requireNonNull(value));
        }
        return List.copyOf(result);
    }
}
