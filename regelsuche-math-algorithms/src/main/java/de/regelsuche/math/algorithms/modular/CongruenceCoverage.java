package de.regelsuche.math.algorithms.modular;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static java.math.BigInteger.ONE;
import static java.math.BigInteger.ZERO;

/**
 * Finite coverage and survivor bounds for congruence classes with inclusive activation thresholds.
 *
 * <p>A request examines {@code first + step * k}, {@code 0 <= k < count}. A rule
 * covers a value {@code n} exactly when {@code n >= activeFrom} and
 * {@code n == residue (mod modulus)}. Origins, residues and thresholds may be signed.
 * Generalized CRT and inclusion-exclusion operate on rays in index coordinates;
 * no primality or coprimality assumptions are made.
 *
 * <p>The number of union terms can grow exponentially in the number of rules.
 * Work, intermediate terms, integer size, cancellation and elapsed time are bounded.
 * An unavailable result contains no certificate and asserts nothing about coverage.
 * Certificates concern only the supplied finite request, never an infinite family.
 */
public final class CongruenceCoverage {
    private CongruenceCoverage() {}

    /** A congruence class active at and above {@code activeFrom}; modulus is positive. */
    public record Rule(BigInteger modulus, BigInteger residue, BigInteger activeFrom) {
        public Rule {
            Objects.requireNonNull(modulus);
            Objects.requireNonNull(residue);
            Objects.requireNonNull(activeFrom);
            if (modulus.signum() <= 0) throw new IllegalArgumentException("INVALID_CONGRUENCE_MODULUS");
        }
    }

    /** A finite increasing progression; step is positive and count is nonnegative. */
    public record Progression(BigInteger first, BigInteger step, BigInteger count) {
        public Progression {
            Objects.requireNonNull(first);
            Objects.requireNonNull(step);
            Objects.requireNonNull(count);
            if (step.signum() <= 0 || count.signum() < 0)
                throw new IllegalArgumentException("INVALID_COVERAGE_PROGRESSION");
        }
    }

    /** Immutable input, with the original rule order preserved for deterministic work. */
    public record Request(Progression progression, List<Rule> rules) {
        public Request {
            Objects.requireNonNull(progression);
            rules = List.copyOf(rules);
        }
    }

    /**
     * A signed indicator term for indices {@code firstIndex + period * j}, {@code j >= 0},
     * clipped to the source count. A singleton uses that finite count as its period.
     * This record is certificate data; constructing it does not establish its validity.
     */
    public record Term(BigInteger firstIndex, BigInteger period, BigInteger coefficient) {
        public Term {
            Objects.requireNonNull(firstIndex);
            Objects.requireNonNull(period);
            Objects.requireNonNull(coefficient);
        }
    }

    /**
     * Replayable finite evidence. Terms are sorted by first index then period, with
     * identical rays merged. {@code leastIndex} is empty precisely for complete coverage.
     * Arbitrary constructed certificates must be checked with {@link #verify}.
     */
    public record Certificate(Request source, List<Term> terms, BigInteger coveredCount,
            Optional<BigInteger> leastIndex) {
        public Certificate {
            Objects.requireNonNull(source);
            terms = List.copyOf(terms);
            Objects.requireNonNull(coveredCount);
            Objects.requireNonNull(leastIndex);
        }
    }

    public enum Status { COMPLETE_COVER, FOUND_UNCOVERED, UNAVAILABLE }

    /** Completed results carry evidence; interrupted or bounded-out results carry none. */
    public record Result(Status status, Optional<Certificate> certificate, String reason, long work) {
        public Result {
            Objects.requireNonNull(status);
            Objects.requireNonNull(certificate);
            Objects.requireNonNull(reason);
            if (work < 0 || (status == Status.UNAVAILABLE) == certificate.isPresent())
                throw new IllegalArgumentException("INVALID_COVERAGE_RESULT");
        }

        /** The absolute progression value at the least uncovered index, when found. */
        public Optional<BigInteger> leastUncovered() {
            return certificate.flatMap(proof -> proof.leastIndex().map(index ->
                proof.source().progression().first().add(proof.source().progression().step().multiply(index))));
        }
    }

    /** A verifier failure, including resource exhaustion, never validates partial evidence. */
    public record Verification(boolean valid, String reason, long work) {}

    /**
     * Finite survivor bounds with exact intersection sums for layers 1 through the
     * smaller of the requested order and the number of nonempty rules. Layer zero
     * is the source progression's count. Repeated rules occupy distinct positions.
     * Constructed certificates can be checked with {@link #verifyBounds}.
     */
    public record BoundsCertificate(Request source, int order, List<BigInteger> layerTotals,
            BigInteger lowerBound, BigInteger upperBound) {
        public BoundsCertificate {
            Objects.requireNonNull(source);
            layerTotals = List.copyOf(layerTotals);
            Objects.requireNonNull(lowerBound);
            Objects.requireNonNull(upperBound);
        }
    }

    public enum BoundsStatus { CERTIFIED, UNAVAILABLE }

    /** A certified interval or an unavailable result with no partial evidence. */
    public record BoundsResult(BoundsStatus status, Optional<BoundsCertificate> certificate, String reason, long work) {
        public BoundsResult {
            Objects.requireNonNull(status);
            Objects.requireNonNull(certificate);
            Objects.requireNonNull(reason);
            if (work < 0 || (status == BoundsStatus.UNAVAILABLE) == certificate.isPresent())
                throw new IllegalArgumentException("INVALID_COVERAGE_BOUNDS_RESULT");
        }
    }

    /**
     * Counts the union exactly and, if needed, locates its least missing index by prefix counts.
     * {@code maximumTerms} bounds the terms in each union map, including intermediate merges.
     * The old and new maps coexist while adding a rule. Integer magnitudes are limited by
     * {@code MAX_RUNTIME_BITS}.
     */
    public static Result search(Request request, IterationLimits limits, BooleanSupplier cancellation) {
        Objects.requireNonNull(request);
        var work = new Work(limits, cancellation);
        try {
            validateInput(request, work);
            var terms = reconstruct(request, work);
            var count = request.progression().count();
            var covered = count(terms, count, work);
            Optional<BigInteger> least = Optional.empty();
            if (!covered.equals(count)) {
                var index = leastMissing(terms, count, work);
                if (directlyCovered(request, index, work)) throw new Unavailable("INCONSISTENT_UNION");
                least = Optional.of(index);
            }
            var proof = new Certificate(request, terms, covered, least);
            var status = least.isPresent() ? Status.FOUND_UNCOVERED : Status.COMPLETE_COVER;
            return new Result(status, Optional.of(proof), status.name(), work.used);
        } catch (Unavailable unavailable) {
            return new Result(Status.UNAVAILABLE, Optional.empty(), unavailable.getMessage(), work.used);
        }
    }

    /**
     * Rebuilds the entire union from the supplied request, compares the certificate terms
     * and count, then checks the claimed witness directly and its preceding prefix exactly.
     * It does not trust a search status, a claimed covered count, or an untested witness.
     */
    public static Verification verify(Request request, Certificate certificate, IterationLimits limits,
            BooleanSupplier cancellation) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(certificate);
        var work = new Work(limits, cancellation);
        try {
            validateInput(request, work);
            if (!sameSource(request, certificate.source(), work)) return invalid("SOURCE_MISMATCH", work);
            validateCertificate(certificate, work);
            var terms = reconstruct(request, work);
            work.charge(1L + terms.size());
            if (!terms.equals(certificate.terms())) return invalid("TERMS_MISMATCH", work);
            var size = request.progression().count();
            var covered = count(terms, size, work);
            if (!covered.equals(certificate.coveredCount())) return invalid("COVERED_COUNT_MISMATCH", work);
            if (covered.equals(size)) {
                if (certificate.leastIndex().isPresent()) return invalid("LEAST_INDEX_MISMATCH", work);
            } else {
                if (certificate.leastIndex().isEmpty()) return invalid("LEAST_INDEX_MISMATCH", work);
                var index = certificate.leastIndex().orElseThrow();
                if (index.signum() < 0 || index.compareTo(size) >= 0
                        || !count(terms, index, work).equals(index)
                        || directlyCovered(request, index, work)) {
                    return invalid("LEAST_INDEX_MISMATCH", work);
                }
            }
            work.charge(1);
            return new Verification(true, "VERIFIED", work.used);
        } catch (Unavailable unavailable) {
            return invalid(unavailable.getMessage(), work);
        }
    }

    /**
     * Bounds the total number of uncovered elements using complete Bonferroni layers
     * through the requested nonnegative order. Order zero yields [0, count] when
     * any rule is effective. Exhausting the effective rules gives an exact count.
     * The normalized rule positions are bounded by {@code maximumTerms}.
     */
    public static BoundsResult bounds(Request request, int order, IterationLimits limits,
            BooleanSupplier cancellation) {
        Objects.requireNonNull(request);
        validateOrder(order);
        var work = new Work(limits, cancellation);
        try {
            validateInput(request, work);
            var proof = reconstructBounds(request, order, work);
            return new BoundsResult(BoundsStatus.CERTIFIED, Optional.of(proof), BoundsStatus.CERTIFIED.name(), work.used);
        } catch (Unavailable unavailable) {
            return new BoundsResult(BoundsStatus.UNAVAILABLE, Optional.empty(), unavailable.getMessage(), work.used);
        }
    }

    /** Recomputes every required layer and both endpoints against the expected request and order. */
    public static Verification verifyBounds(Request request, int order, BoundsCertificate certificate,
            IterationLimits limits, BooleanSupplier cancellation) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(certificate);
        validateOrder(order);
        var work = new Work(limits, cancellation);
        try {
            validateInput(request, work);
            if (!sameSource(request, certificate.source(), work)) return invalid("SOURCE_MISMATCH", work);
            if (order != certificate.order()) return invalid("ORDER_MISMATCH", work);
            work.terms(certificate.layerTotals().size());
            for (var total : certificate.layerTotals()) work.integer(total);
            work.integer(certificate.lowerBound());
            work.integer(certificate.upperBound());
            var expected = reconstructBounds(request, order, work);
            work.charge(1L + expected.layerTotals().size());
            if (!expected.layerTotals().equals(certificate.layerTotals())) return invalid("LAYER_TOTALS_MISMATCH", work);
            if (!expected.lowerBound().equals(certificate.lowerBound())
                    || !expected.upperBound().equals(certificate.upperBound())) {
                return invalid("BOUNDS_MISMATCH", work);
            }
            work.charge(1);
            return new Verification(true, "VERIFIED", work.used);
        } catch (Unavailable unavailable) {
            return invalid(unavailable.getMessage(), work);
        }
    }

    private static Verification invalid(String reason, Work work) {
        return new Verification(false, reason, work.used);
    }

    private static void validateOrder(int order) {
        if (order < 0) throw new IllegalArgumentException("INVALID_COVERAGE_BOUNDS_ORDER");
    }

    private static boolean sameSource(Request request, Request source, Work work) {
        validateInput(source, work);
        work.charge(1L + request.rules().size());
        return request.equals(source);
    }

    private static void validateInput(Request request, Work work) {
        work.charge(1);
        var progression = request.progression();
        work.integer(progression.first());
        work.integer(progression.step());
        work.integer(progression.count());
        if (progression.count().signum() > 0) {
            work.add(progression.first(), work.multiply(progression.step(), work.subtract(progression.count(), ONE)));
        }
        for (var rule : request.rules()) {
            work.integer(rule.modulus());
            work.integer(rule.residue());
            work.integer(rule.activeFrom());
        }
    }

    private static void validateCertificate(Certificate certificate, Work work) {
        work.terms(certificate.terms().size());
        work.integer(certificate.coveredCount());
        certificate.leastIndex().ifPresent(work::integer);
        for (var term : certificate.terms()) {
            work.integer(term.firstIndex());
            work.integer(term.period());
            work.integer(term.coefficient());
        }
    }

    private record Ray(BigInteger first, BigInteger period) {}

    private record IntersectedRule(int position, Ray intersection) {}

    private static BoundsCertificate reconstructBounds(Request request, int order, Work work) {
        var size = request.progression().count();
        var rays = new ArrayList<Ray>();
        if (size.signum() > 0) {
            for (var rule : request.rules()) {
                var ray = indexRay(request.progression(), rule, work);
                if (ray == null) continue;
                work.terms(rays.size() + 1);
                rays.add(ray);
                // At order zero one ray is enough to rule out exhaustion.
                if (order == 0) break;
            }
        }
        int depth = Math.min(order, rays.size());
        var totals = layerTotals(rays, depth, size, work);
        var lower = ZERO;
        var upper = size;
        var partial = size;
        for (int layer = 0; layer < depth; layer++) {
            if (layer % 2 == 0) {
                partial = work.subtract(partial, totals.get(layer));
                lower = lower.max(partial);
            } else {
                partial = work.add(partial, totals.get(layer));
                upper = upper.min(partial);
            }
        }
        if (depth == rays.size()) {
            lower = partial;
            upper = partial;
        }
        work.charge(1L + depth);
        return new BoundsCertificate(request, order, totals, lower, upper);
    }

    private static List<BigInteger> layerTotals(List<Ray> rays, int depth, BigInteger size, Work work) {
        if (depth == 0) return List.of();
        work.charge(1L + depth);
        var totals = new ArrayList<>(Collections.nCopies(depth, ZERO));
        var path = new ArrayDeque<IntersectedRule>();
        // Visit each subset in increasing rule order, bypassing every extension of an empty intersection.
        int position = 0;
        while (true) {
            work.charge(1);
            if (position == rays.size()) {
                if (path.isEmpty()) return totals;
                position = path.removeLast().position() + 1;
                continue;
            }
            var ray = rays.get(position);
            if (!path.isEmpty()) ray = intersect(path.getLast().intersection(), ray, size, work);
            if (ray != null) {
                int layer = path.size();
                var occurrences = rayCount(ray.first(), ray.period(), size, work);
                totals.set(layer, work.add(totals.get(layer), occurrences));
                if (layer + 1 < depth && position + 1 < rays.size()) {
                    path.addLast(new IntersectedRule(position, ray));
                }
            }
            position++;
        }
    }

    private static List<Term> reconstruct(Request request, Work work) {
        var terms = new LinkedHashMap<Ray, BigInteger>();
        var size = request.progression().count();
        if (size.signum() == 0) return List.of();
        for (var rule : request.rules()) {
            var ray = indexRay(request.progression(), rule, work);
            if (ray == null) continue;
            work.charge(1L + terms.size());
            var next = new LinkedHashMap<>(terms);
            merge(next, ray, ONE, work);
            // 1_(U union A) = 1_U + 1_A - 1_U * 1_A, including signed existing terms.
            for (var entry : terms.entrySet()) {
                var intersection = intersect(entry.getKey(), ray, size, work);
                if (intersection != null) merge(next, intersection, work.negate(entry.getValue()), work);
            }
            terms = next;
        }
        var result = new ArrayList<Term>(terms.size());
        for (var entry : terms.entrySet()) {
            work.charge(1);
            result.add(new Term(entry.getKey().first(), entry.getKey().period(), entry.getValue()));
        }
        result.sort((left, right) -> {
            work.charge(1L + Work.words(left.firstIndex()) + Work.words(right.firstIndex())
                + Work.words(left.period()) + Work.words(right.period()));
            int first = left.firstIndex().compareTo(right.firstIndex());
            return first != 0 ? first : left.period().compareTo(right.period());
        });
        return List.copyOf(result);
    }

    private static void merge(Map<Ray, BigInteger> terms, Ray ray, BigInteger coefficient, Work work) {
        work.charge(1);
        var combined = work.add(terms.getOrDefault(ray, ZERO), coefficient);
        if (combined.signum() == 0) terms.remove(ray);
        else terms.put(ray, combined);
        work.terms(terms.size());
    }

    private static Ray indexRay(Progression progression, Rule rule, Work work) {
        var divisor = work.gcd(progression.step(), rule.modulus());
        var difference = work.subtract(rule.residue(), progression.first());
        if (work.mod(difference, divisor).signum() != 0) return null;
        var period = work.divide(rule.modulus(), divisor);
        var residue = ZERO;
        if (!period.equals(ONE)) {
            var inverse = work.inverse(work.mod(work.divide(progression.step(), divisor), period), period);
            residue = work.mod(work.multiply(work.mod(work.divide(difference, divisor), period), inverse), period);
        }
        var threshold = work.ceilDivide(work.subtract(rule.activeFrom(), progression.first()), progression.step()).max(ZERO);
        return clipped(lift(residue, period, threshold, work), period, progression.count(), work);
    }

    private static Ray intersect(Ray left, Ray right, BigInteger count, Work work) {
        work.charge(1);
        if (left.period().equals(count)) return singletonIntersection(left, right, work);
        if (right.period().equals(count)) return singletonIntersection(right, left, work);
        var divisor = work.gcd(left.period(), right.period());
        var difference = work.subtract(right.first(), left.first());
        if (work.mod(difference, divisor).signum() != 0) return null;
        var rightReduced = work.divide(right.period(), divisor);
        var period = work.multiply(left.period(), rightReduced);
        var shift = ZERO;
        if (!rightReduced.equals(ONE)) {
            var inverse = work.inverse(work.mod(work.divide(left.period(), divisor), rightReduced), rightReduced);
            shift = work.mod(work.multiply(work.mod(work.divide(difference, divisor), rightReduced), inverse), rightReduced);
        }
        var first = work.add(left.first(), work.multiply(left.period(), shift));
        return clipped(lift(first, period, left.first().max(right.first()), work), period, count, work);
    }

    private static Ray singletonIntersection(Ray singleton, Ray other, Work work) {
        return singleton.first().compareTo(other.first()) >= 0
                && work.mod(work.subtract(singleton.first(), other.first()), other.period()).signum() == 0
            ? singleton : null;
    }

    private static BigInteger lift(BigInteger first, BigInteger period, BigInteger threshold, Work work) {
        work.charge(1);
        if (first.compareTo(threshold) >= 0) return first;
        return work.add(first, work.multiply(period, work.ceilDivide(work.subtract(threshold, first), period)));
    }

    private static Ray clipped(BigInteger first, BigInteger period, BigInteger count, Work work) {
        work.charge(1);
        if (first.compareTo(count) >= 0) return null;
        // Equal singleton sets must merge even if their original moduli were different.
        return new Ray(first, period.compareTo(work.subtract(count, first)) >= 0 ? count : period);
    }

    private static BigInteger count(List<Term> terms, BigInteger boundary, Work work) {
        var sum = ZERO;
        for (var term : terms) {
            work.charge(1);
            if (boundary.compareTo(term.firstIndex()) <= 0) continue;
            var occurrences = rayCount(term.firstIndex(), term.period(), boundary, work);
            sum = work.add(sum, work.multiply(occurrences, term.coefficient()));
        }
        work.charge(1);
        if (sum.signum() < 0 || sum.compareTo(boundary) > 0) throw new Unavailable("INCONSISTENT_UNION");
        return sum;
    }

    private static BigInteger rayCount(BigInteger first, BigInteger period, BigInteger boundary, Work work) {
        return work.add(work.divide(work.subtract(work.subtract(boundary, ONE), first), period), ONE);
    }

    private static BigInteger leastMissing(List<Term> terms, BigInteger size, Work work) {
        var low = ZERO;
        var high = work.subtract(size, ONE);
        while (low.compareTo(high) < 0) {
            var middle = work.add(low, work.divide(work.subtract(high, low), BigInteger.TWO));
            var prefix = work.add(middle, ONE);
            if (count(terms, prefix, work).equals(prefix)) low = prefix;
            else high = middle;
        }
        return low;
    }

    private static boolean directlyCovered(Request request, BigInteger index, Work work) {
        var value = work.add(request.progression().first(), work.multiply(request.progression().step(), index));
        for (var rule : request.rules()) {
            work.charge(1);
            if (value.compareTo(rule.activeFrom()) >= 0
                    && work.mod(work.subtract(value, rule.residue()), rule.modulus()).signum() == 0) return true;
        }
        return false;
    }

    private static final class Unavailable extends RuntimeException {
        private Unavailable(String reason) { super(reason, null, false, false); }
    }

    /** Word-based accounting for this integer algorithm; no polynomial semantics are involved. */
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
            if (amount < 0 || amount > limits.maximumWork() - used) throw new Unavailable("WORK_LIMIT");
            used += amount;
        }

        private static int bits(BigInteger value) { return value.abs().bitLength(); }
        private static long words(BigInteger value) { return 1L + bits(value) / 64L; }

        private BigInteger integer(BigInteger value) {
            charge(words(value));
            if (bits(value) > IterationLimits.MAX_RUNTIME_BITS) throw new Unavailable("RUNTIME_BIT_LIMIT");
            return value;
        }

        private void terms(int count) {
            charge(1);
            if (count > limits.maximumTerms()) throw new Unavailable("TERM_LIMIT");
        }

        private BigInteger add(BigInteger left, BigInteger right) {
            charge(1L + Math.max(words(left), words(right)));
            return integer(left.add(right));
        }

        private BigInteger subtract(BigInteger left, BigInteger right) {
            charge(1L + Math.max(words(left), words(right)));
            return integer(left.subtract(right));
        }

        private BigInteger negate(BigInteger value) {
            charge(words(value));
            return integer(value.negate());
        }

        private BigInteger multiply(BigInteger left, BigInteger right) {
            charge(1L + words(left) * words(right));
            if (left.signum() != 0 && right.signum() != 0
                    && (long) bits(left) + bits(right) > IterationLimits.MAX_RUNTIME_BITS + 1L) {
                throw new Unavailable("RUNTIME_BIT_LIMIT");
            }
            return integer(left.multiply(right));
        }

        private BigInteger divide(BigInteger left, BigInteger right) {
            charge(1L + words(left) * words(right));
            return integer(left.divide(right));
        }

        private BigInteger ceilDivide(BigInteger numerator, BigInteger denominator) {
            var quotient = divide(numerator, denominator);
            return numerator.signum() > 0 && mod(numerator, denominator).signum() != 0
                ? add(quotient, ONE) : quotient;
        }

        private BigInteger mod(BigInteger value, BigInteger modulus) {
            charge(1L + words(value) * words(modulus));
            return integer(value.mod(modulus));
        }

        private BigInteger gcd(BigInteger left, BigInteger right) {
            charge(1L + words(left) * words(right));
            return integer(left.gcd(right));
        }

        private BigInteger inverse(BigInteger value, BigInteger modulus) {
            charge(1L + words(value) * words(modulus));
            return integer(value.modInverse(modulus));
        }
    }
}
