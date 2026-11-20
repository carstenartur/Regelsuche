package de.regelsuche.math.algorithms.proof;

import de.regelsuche.math.algorithms.combinatorics.BipartiteMatching;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.modular.CongruenceCoverage;
import de.regelsuche.math.algorithms.modular.HunterCoverage;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Source-bound finite deductions. The final node is the root, with zero-based preceding premises. */
public final class FiniteProofDag {
    private FiniteProofDag() {}

    public sealed interface Source permits CoverageSource, HallSource {}
    /** The counted objects are uncovered indices in exactly this progression and rule family. */
    public record CoverageSource(CongruenceCoverage.Request request) implements Source {
        public CoverageSource { Objects.requireNonNull(request); }
    }
    /** The count is a lower bound on unmatched left vertices in every unit-capacity matching. */
    public record HallSource(BipartiteMatching.Request request) implements Source {
        public HallSource { Objects.requireNonNull(request); }
    }

    public sealed interface Node permits ExactLeaf, HunterLeaf, HallLeaf, Maximum, Exists {}
    public record ExactLeaf(CongruenceCoverage.Certificate certificate) implements Node {
        public ExactLeaf { Objects.requireNonNull(certificate); }
    }
    public record HunterLeaf(HunterCoverage.Certificate certificate) implements Node {
        public HunterLeaf { Objects.requireNonNull(certificate); }
    }
    public record HallLeaf(BipartiteMatching.Certificate certificate) implements Node {
        public HallLeaf { Objects.requireNonNull(certificate); }
    }
    /** Two lower bounds on the one expected source imply their maximum. */
    public record Maximum(int left, int right) implements Node {}
    /** A positive lower bound implies that at least one counted object exists. */
    public record Exists(int premise) implements Node {}
    public record Proof(List<Node> nodes) {
        public Proof { nodes = List.copyOf(nodes); }
    }

    public sealed interface Claim permits LowerBound, Existence {
        Source source();
    }
    public record LowerBound(Source source, BigInteger value) implements Claim {}
    public record Existence(Source source) implements Claim {}
    public record Verification(Optional<Claim> conclusion, String reason, long work) {
        public boolean valid() { return conclusion.isPresent(); }
    }

    /** Every leaf is replayed against the caller's source, sharing one work and wall-clock budget. */
    public static Verification verify(Source expected, Proof proof, IterationLimits limits,
            BooleanSupplier cancellation) {
        Objects.requireNonNull(expected);
        Objects.requireNonNull(proof);
        var replay = new Replay(limits, cancellation);
        try {
            replay.charge();
            int count = proof.nodes().size();
            if (count == 0) throw new Rejected("EMPTY_PROOF");
            if (count > limits.maximumTerms()) throw new Rejected("TERM_LIMIT");
            var previous = new ArrayList<Claim>();
            for (var node : proof.nodes()) {
                replay.charge();
                previous.add(switch (node) {
                    case ExactLeaf leaf -> {
                        var source = coverage(expected);
                        var cert = leaf.certificate();
                        var result = CongruenceCoverage.verify(source.request(), cert, replay.remaining(), cancellation);
                        replay.accept(result.valid(), result.reason(), result.work());
                        yield new LowerBound(expected, source.request().progression().count().subtract(cert.coveredCount()));
                    }
                    case HunterLeaf leaf -> {
                        var source = coverage(expected);
                        var cert = leaf.certificate();
                        var result = HunterCoverage.verify(source.request(), cert, replay.remaining(), cancellation);
                        replay.accept(result.valid(), result.reason(), result.work());
                        yield new LowerBound(expected, cert.lowerBound());
                    }
                    case HallLeaf leaf -> {
                        if (!(expected instanceof HallSource source)) throw new Rejected("DOMAIN_MISMATCH");
                        var cert = leaf.certificate();
                        var result = BipartiteMatching.verify(source.request(), cert, replay.remaining(), cancellation);
                        replay.accept(result.valid(), result.reason(), result.work());
                        yield new LowerBound(expected, BigInteger.valueOf(cert.deficiency()));
                    }
                    case Maximum join -> {
                        var left = bound(previous, join.left());
                        var right = bound(previous, join.right());
                        yield new LowerBound(expected, left.max(right));
                    }
                    case Exists step -> {
                        if (bound(previous, step.premise()).signum() <= 0) throw new Rejected("NONPOSITIVE_BOUND");
                        yield new Existence(expected);
                    }
                });
            }
            replay.charge();
            return new Verification(Optional.of(previous.getLast()), "VERIFIED", replay.used);
        } catch (Rejected rejected) {
            return new Verification(Optional.empty(), rejected.getMessage(), replay.used);
        }
    }

    private static CoverageSource coverage(Source expected) {
        if (expected instanceof CoverageSource source) return source;
        throw new Rejected("DOMAIN_MISMATCH");
    }

    private static BigInteger bound(List<Claim> previous, int index) {
        if (index < 0 || index >= previous.size()) throw new Rejected("INVALID_PREMISE");
        if (previous.get(index) instanceof LowerBound bound) return bound.value();
        throw new Rejected("PREMISE_IS_NOT_BOUND");
    }

    private static final class Replay {
        private final IterationLimits limits;
        private final BooleanSupplier cancellation;
        private final long start = System.nanoTime();
        private final long timeout;
        private long used;

        private Replay(IterationLimits limits, BooleanSupplier cancellation) {
            this.limits = Objects.requireNonNull(limits);
            this.cancellation = Objects.requireNonNull(cancellation);
            timeout = TimeUnit.MILLISECONDS.toNanos(limits.timeoutMillis());
        }

        private long nanosLeft() {
            if (cancellation.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new Rejected("CANCELLED");
            long remaining = timeout - (System.nanoTime() - start);
            if (remaining <= 0) throw new Rejected("TIME_LIMIT");
            return remaining;
        }

        private void charge() {
            nanosLeft();
            if (used >= limits.maximumWork()) throw new Rejected("WORK_LIMIT");
            used++;
        }

        private IterationLimits remaining() {
            long nanos = nanosLeft();
            if (used >= limits.maximumWork()) throw new Rejected("WORK_LIMIT");
            return new IterationLimits(limits.maximumWork() - used, limits.maximumTerms(),
                1 + TimeUnit.NANOSECONDS.toMillis(nanos - 1));
        }

        private void accept(boolean valid, String reason, long work) {
            used += work;
            nanosLeft();
            if (!valid) throw new Rejected(reason);
        }
    }

    private static final class Rejected extends RuntimeException {
        private Rejected(String reason) { super(reason, null, false, false); }
    }
}
