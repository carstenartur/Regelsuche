package de.regelsuche.math.algorithms.modular;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Exact finite preimages of integer polynomials in a bounded residue scan. */
public final class PolynomialCongruence {
    private PolynomialCongruence() {}

    /** The original signed coefficients, lowest exponent first, and target residue. */
    public record Request(List<BigInteger> coefficientsAscending, int modulus, BigInteger residue) {
        public Request {
            Objects.requireNonNull(residue);
            if (modulus <= 0) throw new IllegalArgumentException("INVALID_CONGRUENCE_MODULUS");
            coefficientsAscending = List.copyOf(coefficientsAscending);
            if (coefficientsAscending.isEmpty()) throw new IllegalArgumentException("EMPTY_POLYNOMIAL_COEFFICIENTS");
        }
    }

    /** Finite evidence bound to the exact request. Use {@link #verify} to check supplied evidence. */
    public record Solution(Request source, List<Integer> residues) {
        public Solution {
            Objects.requireNonNull(source);
            residues = List.copyOf(residues);
        }
    }

    public enum Status { COMPLETE, UNAVAILABLE }

    /** A completed scan carries the entire sorted set, including a possible empty set. */
    public record Result(Status status, Optional<Solution> solution, String reason, long work) {
        public Result {
            Objects.requireNonNull(status);
            Objects.requireNonNull(solution);
            Objects.requireNonNull(reason);
            if (work < 0 || (status == Status.COMPLETE) != solution.isPresent()) {
                throw new IllegalArgumentException("INVALID_POLYNOMIAL_CONGRUENCE_RESULT");
            }
        }
    }

    /** A failed or interrupted verification has {@code valid=false}. */
    public record Verification(boolean valid, String reason, long work) {}

    /**
     * Checks every residue from zero to {@code modulus - 1}. {@code maximumTerms} bounds the
     * coefficient count and result count individually. A resource or cancellation stop returns
     * {@code UNAVAILABLE} with no solution.
     */
    public static Result solve(Request request, IterationLimits limits, BooleanSupplier cancellation) {
        Objects.requireNonNull(request);
        var work = new Work(limits, cancellation);
        try {
            var roots = scan(request, work);
            work.charge(roots.size());
            var solution = new Solution(request, roots);
            work.charge(0);
            return new Result(Status.COMPLETE, Optional.of(solution), "COMPLETE", work.used);
        } catch (Unavailable unavailable) {
            return new Result(Status.UNAVAILABLE, Optional.empty(), unavailable.getMessage(), work.used);
        }
    }

    /** Checks exact source equality and canonical residues, then replays the complete scan. */
    public static Verification verify(Request request, Solution solution, IterationLimits limits,
            BooleanSupplier cancellation) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(solution);
        var work = new Work(limits, cancellation);
        try {
            work.terms(request.coefficientsAscending().size());
            if (!matchesSource(request, solution.source(), work)) {
                return new Verification(false, "SOURCE_MISMATCH", work.used);
            }
            var claimed = solution.residues();
            work.terms(claimed.size());
            if (!canonical(claimed, request.modulus(), work)) {
                return new Verification(false, "NONCANONICAL_RESIDUES", work.used);
            }
            var roots = scan(request, work);
            work.charge(1L + Math.min(roots.size(), claimed.size()));
            boolean valid = roots.equals(claimed);
            work.charge(0);
            return new Verification(valid, valid ? "VERIFIED" : "RESIDUES_MISMATCH", work.used);
        } catch (Unavailable unavailable) {
            return new Verification(false, unavailable.getMessage(), work.used);
        }
    }

    private static boolean matchesSource(Request request, Request source, Work work) {
        if (request == source) return true;
        if (request.modulus() != source.modulus()) return false;
        var coefficients = request.coefficientsAscending();
        var sourceCoefficients = source.coefficientsAscending();
        if (coefficients.size() != sourceCoefficients.size()) return false;
        work.charge(work.inputWords(request.residue()));
        if (!request.residue().equals(source.residue())) return false;
        for (int i = 0; i < coefficients.size(); i++) {
            var coefficient = coefficients.get(i);
            work.charge(work.inputWords(coefficient));
            if (!coefficient.equals(sourceCoefficients.get(i))) return false;
        }
        return true;
    }

    private static boolean canonical(List<Integer> residues, int modulus, Work work) {
        int previous = -1;
        for (int residue : residues) {
            work.charge(1);
            if (residue <= previous || residue >= modulus) return false;
            previous = residue;
        }
        return true;
    }

    private static List<Integer> scan(Request request, Work work) {
        var coefficients = request.coefficientsAscending();
        work.terms(coefficients.size());
        var modulus = BigInteger.valueOf(request.modulus());
        int target = normalize(request.residue(), modulus, work);
        work.charge(coefficients.size());
        var normalized = new int[coefficients.size()];
        for (int i = 0; i < normalized.length; i++) {
            normalized[i] = normalize(coefficients.get(i), modulus, work);
        }
        var roots = new ArrayList<Integer>();
        for (int x = 0; x < request.modulus(); x++) {
            work.charge(1L + normalized.length);
            if (evaluateNormalized(normalized, request.modulus(), x) == target) {
                work.terms(roots.size() + 1);
                roots.add(x);
            }
        }
        return roots;
    }

    private static int normalize(BigInteger value, BigInteger modulus, Work work) {
        work.charge(1L + work.inputWords(value));
        return value.mod(modulus).intValueExact();
    }

    // Each input residue is in [0, modulus), so every unreduced step is at most
    // modulus * (modulus - 1), which fits in a signed long for a positive int modulus.
    static int evaluateNormalized(int[] coefficients, int modulus, int x) {
        long value = 0;
        for (int i = coefficients.length - 1; i >= 0; i--) {
            value = (value * x + coefficients[i]) % modulus;
        }
        return (int) value;
    }

    private static final class Work {
        private final IterationLimits limits;
        private final BooleanSupplier cancellation;
        private final long start = System.nanoTime();
        private final long timeout;
        private long used;

        private Work(IterationLimits limits, BooleanSupplier cancellation) {
            this.limits = Objects.requireNonNull(limits);
            this.cancellation = Objects.requireNonNull(cancellation);
            timeout = TimeUnit.MILLISECONDS.toNanos(limits.timeoutMillis());
        }

        private void charge(long amount) {
            if (cancellation.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new Unavailable("CANCELLED");
            if (System.nanoTime() - start >= timeout) throw new Unavailable("TIME_LIMIT");
            if (amount > limits.maximumWork() - used) throw new Unavailable("WORK_LIMIT");
            used += amount;
        }

        private void terms(int count) {
            charge(1);
            if (count > limits.maximumTerms()) throw new Unavailable("TERM_LIMIT");
        }

        private long inputWords(BigInteger value) {
            int bits = value.abs().bitLength();
            if (bits > IterationLimits.MAX_RUNTIME_BITS) throw new Unavailable("RUNTIME_BIT_LIMIT");
            return 1L + bits / 64L;
        }
    }

    private static final class Unavailable extends RuntimeException {
        private Unavailable(String reason) { super(reason, null, false, false); }
    }
}
