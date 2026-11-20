package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactMatrix;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Bounded modular discovery of exact substitutions for homogeneous systems over Q. */
public final class ModularKernel {
    private static final List<Integer> DEFAULT_PRIMES = List.of(1_000_000_007, 1_000_000_009, 998_244_353,
        469_762_049, 167_772_161, 754_974_721);

    private ModularKernel() {}

    public enum Strength { SUBSTITUTION, COMPLETE_KERNEL }
    public enum Status { VERIFIED, NO_CERTIFICATE, LIMIT, CANCELLED }

    /** maximumTerms bounds the cells in each dense matrix, including the square projection. */
    public record Limits(IterationLimits effort, int maximumBits, List<Integer> primes) {
        public Limits {
            Objects.requireNonNull(effort);
            if (maximumBits < 1 || maximumBits > IterationLimits.MAX_RUNTIME_BITS
                    || primes.isEmpty() || primes.size() > 64) throw new IllegalArgumentException("invalid limits");
            primes = List.copyOf(primes);
            if (new HashSet<>(primes).size() != primes.size()) throw new IllegalArgumentException("duplicate modulus");
        }
        public static Limits defaults(IterationLimits effort) {
            return new Limits(effort, 4096, DEFAULT_PRIMES);
        }
    }

    /** Untrusted evidence. A verifier chooses the required strength independently of discovery. */
    public record Certificate(ExactMatrix source, List<Integer> selectedRows, List<Integer> pivots,
            List<Integer> freeColumns, ExactMatrix projection, int witnessPrime) {
        public Certificate {
            Objects.requireNonNull(source);
            Objects.requireNonNull(projection);
            selectedRows = List.copyOf(selectedRows);
            pivots = List.copyOf(pivots);
            freeColumns = List.copyOf(freeColumns);
        }
    }

    public record Result(Status status, Strength strength, Optional<Certificate> certificate, long work) {
        public Result {
            Objects.requireNonNull(status);
            Objects.requireNonNull(strength);
            if ((status == Status.VERIFIED) != certificate.isPresent() || work < 0)
                throw new IllegalArgumentException("inconsistent search result");
        }
    }

    public static Result search(ExactMatrix source, Strength strength, Limits limits, BooleanSupplier cancellation) {
        Objects.requireNonNull(strength);
        var work = new Work(limits, cancellation);
        try {
            work.shape(source);
            var integers = clear(source, work);
            Reduction pattern = null;
            BigInteger modulus = BigInteger.ONE;
            BigInteger[][] residues = null;
            int witness = 0;
            for (int prime : limits.primes()) {
                if (!isPrime(prime, work)) continue;
                var p = BigInteger.valueOf(prime);
                if (!integers.usable(p, work)) continue;
                var reduced = reduce(integers.mod(p, work), prime, work);
                int profileOrder = pattern == null ? -1 : compareProfiles(reduced, pattern, work);
                if (profileOrder < 0) {
                    pattern = reduced;
                    witness = prime;
                    modulus = BigInteger.ONE;
                    residues = new BigInteger[reduced.pivots.size()][reduced.free.size()];
                }
                if (profileOrder > 0) continue;
                // Equal pivot profiles use the same coordinates even if elimination selected different rows.
                // Retain the original rank witness; the exact verifier checks its equations after reconstruction.
                BigInteger nextModulus = work.bound(modulus.multiply(p));
                var inverse = modulus.modInverse(p);
                for (int i = 0; i < reduced.pivots.size(); i++) {
                    for (int j = 0; j < reduced.free.size(); j++) {
                        work.step();
                        var old = residues[i][j] == null ? BigInteger.ZERO : residues[i][j];
                        var value = BigInteger.valueOf(-reduced.matrix[i][reduced.free.get(j)]);
                        var digit = value.subtract(old).multiply(inverse).mod(p);
                        residues[i][j] = old.add(modulus.multiply(digit));
                    }
                }
                modulus = nextModulus;
                var projection = reconstruct(reduced, residues, modulus, work);
                if (projection == null) continue;
                var cert = new Certificate(source, pattern.rows, pattern.pivots, pattern.free, projection, witness);
                if (ModularKernelVerifier.check(source, strength, cert, work))
                    return new Result(Status.VERIFIED, strength, Optional.of(cert), work.used);
            }
            return new Result(Status.NO_CERTIFICATE, strength, Optional.empty(), work.used);
        } catch (Stopped stop) {
            return new Result(stop.status, strength, Optional.empty(), work.used);
        }
    }

    /** Prefer higher rank, then earlier pivot columns: reduction modulo a prime can only lose prefix rank. */
    private static int compareProfiles(Reduction candidate, Reduction retained, Work work) {
        int rankOrder = Integer.compare(retained.pivots.size(), candidate.pivots.size());
        if (rankOrder != 0) return rankOrder;
        for (int i = 0; i < candidate.pivots.size(); i++) {
            work.step();
            int columnOrder = Integer.compare(candidate.pivots.get(i), retained.pivots.get(i));
            if (columnOrder != 0) return columnOrder;
        }
        return 0;
    }

    /** Returns false for invalid evidence, cancellation, or insufficient verification resources. */
    public static boolean verify(ExactMatrix source, Strength strength, Certificate cert, Limits limits,
            BooleanSupplier cancellation) {
        Objects.requireNonNull(strength);
        try {
            return ModularKernelVerifier.check(source, strength, cert, new Work(limits, cancellation));
        } catch (Stopped stop) {
            return false;
        }
    }

    private record IntegerRows(BigInteger[][] values, BigInteger[] scales) {
        boolean usable(BigInteger prime, Work work) {
            for (var scale : scales) {
                work.step();
                if (scale.mod(prime).signum() == 0) return false;
            }
            return true;
        }

        long[][] mod(BigInteger prime, Work work) {
            long[][] out = new long[values.length][values[0].length];
            for (int i = 0; i < out.length; i++) for (int j = 0; j < out[i].length; j++) {
                work.step();
                out[i][j] = values[i][j].mod(prime).longValueExact();
            }
            return out;
        }
    }

    private static IntegerRows clear(ExactMatrix a, Work work) {
        var values = new BigInteger[a.rowCount()][a.columns()];
        var scales = new BigInteger[a.rowCount()];
        for (int i = 0; i < a.rowCount(); i++) {
            var scale = BigInteger.ONE;
            for (var x : a.rows().get(i)) {
                work.step();
                work.bound(x);
                var d = x.denominator();
                scale = work.bound(scale.divide(scale.gcd(d)).multiply(d));
            }
            scales[i] = scale;
            for (int j = 0; j < a.columns(); j++) {
                work.step();
                var x = a.get(i,j);
                values[i][j] = work.bound(scale.divide(x.denominator()).multiply(x.numerator()));
            }
        }
        return new IntegerRows(values, scales);
    }

    private record Reduction(List<Integer> rows, List<Integer> pivots, List<Integer> free, long[][] matrix) {}

    private static Reduction reduce(long[][] a, int prime, Work work) {
        int[] origins = new int[a.length];
        for (int i = 0; i < origins.length; i++) origins[i] = i;
        var pivots = new ArrayList<Integer>();
        var rows = new ArrayList<Integer>();
        var free = new ArrayList<Integer>();
        for (int j = 0; j < a[0].length; j++) {
            work.step();
            int r = rows.size(), k = r;
            while (k < a.length && a[k][j] == 0) { work.step(); k++; }
            if (k == a.length) { free.add(j); continue; }
            long[] swap = a[k];
            a[k] = a[r];
            a[r] = swap;
            int origin = origins[k];
            origins[k] = origins[r];
            origins[r] = origin;
            rows.add(origin);
            pivots.add(j);
            long inverse = inverse(a[r][j], prime);
            // Earlier free columns may be nonzero in other pivot rows, but vanish in this residual row.
            for (int col = j; col < a[r].length; col++) {
                work.step();
                a[r][col] = a[r][col] * inverse % prime;
            }
            for (int i = 0; i < a.length; i++) {
                work.step();
                if (i == r || a[i][j] == 0) continue;
                long factor = a[i][j];
                for (int col = j; col < a[i].length; col++) {
                    work.step();
                    a[i][col] = Math.floorMod(a[i][col] - factor * a[r][col], prime);
                }
            }
        }
        return new Reduction(rows, pivots, free, a);
    }

    private static ExactMatrix reconstruct(Reduction reduced, BigInteger[][] residues, BigInteger modulus, Work work) {
        int n = reduced.pivots.size() + reduced.free.size();
        var bound = modulus.shiftRight(1).sqrt();
        var rows = new ArrayList<List<Rational>>(n);
        int pivot = 0;
        for (int i = 0; i < n; i++) {
            var row = new ArrayList<Rational>(n);
            for (int j = 0; j < n; j++) {
                work.step();
                row.add(i == j ? Rational.ONE : Rational.ZERO);
            }
            if (pivot < reduced.pivots.size() && i == reduced.pivots.get(pivot)) {
                row.set(i, Rational.ZERO);
                for (int j = 0; j < reduced.free.size(); j++) {
                    work.step();
                    var x = reconstruct(residues[pivot][j], modulus, bound, work);
                    if (x == null) return null;
                    row.set(reduced.free.get(j), x);
                }
                pivot++;
            }
            rows.add(row);
        }
        return new ExactMatrix(rows);
    }

    /** Balanced rational reconstruction using the extended Euclidean algorithm. */
    private static Rational reconstruct(BigInteger residue, BigInteger modulus, BigInteger bound, Work work) {
        var r0 = modulus;
        var r1 = residue;
        var t0 = BigInteger.ZERO;
        var t1 = BigInteger.ONE;
        while (r1.compareTo(bound) > 0) {
            work.step();
            var qr = r0.divideAndRemainder(r1);
            var t = t0.subtract(qr[0].multiply(t1));
            r0 = r1;
            r1 = qr[1];
            t0 = t1;
            t1 = t;
        }
        if (t1.abs().compareTo(bound) > 0 || !t1.gcd(modulus).equals(BigInteger.ONE)
                || !r1.gcd(t1).equals(BigInteger.ONE)) return null;
        return new Rational(r1, t1);
    }

    static long inverse(long a, int prime) {
        return BigInteger.valueOf(a).modInverse(BigInteger.valueOf(prime)).longValueExact();
    }

    // The fixed default primes are known. Other caller-provided int moduli are checked exactly.
    static boolean isPrime(int p, Work work) {
        work.step();
        if (p == 2 || DEFAULT_PRIMES.contains(p)) return true;
        if (p < 2 || p % 2 == 0) return false;
        for (int d = 3; (long) d * d <= p; d += 2) {
            work.step();
            if (p % d == 0) return false;
        }
        return true;
    }

    static final class Work {
        final Limits limits;
        final BooleanSupplier cancel;
        final long started = System.nanoTime();
        final long timeout;
        long used;

        Work(Limits limits, BooleanSupplier cancel) {
            this.limits = Objects.requireNonNull(limits);
            this.cancel = Objects.requireNonNull(cancel);
            timeout = TimeUnit.MILLISECONDS.toNanos(limits.effort.timeoutMillis());
        }
        void step() {
            if (cancel.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new Stopped(Status.CANCELLED);
            if (used == limits.effort.maximumWork() || System.nanoTime() - started >= timeout) throw new Stopped(Status.LIMIT);
            used++;
        }
        void shape(ExactMatrix a) {
            step();
            long m = a.rowCount(), n = a.columns();
            if (m > 4096 || n > 4096 || Math.max(m*n, n*n) > Math.min(limits.effort.maximumTerms(), 1_000_000))
                throw new Stopped(Status.LIMIT);
        }
        BigInteger bound(BigInteger x) {
            if (x.abs().bitLength() > limits.maximumBits) throw new Stopped(Status.LIMIT);
            return x;
        }
        void bound(Rational x) { bound(x.numerator()); bound(x.denominator()); }
    }

    private static final class Stopped extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final Status status;
        Stopped(Status status) { super(null, null, false, false); this.status = status; }
    }
}
