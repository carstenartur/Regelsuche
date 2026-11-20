package de.regelsuche.math.algorithms.positivity;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.scalar.ExactRational;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** Exact tensor-product Bernstein witnesses for closed rational intervals and rectangles. */
public final class BernsteinPositivity {
    private BernsteinPositivity() {}

    public record Interval(ExactRational lower, ExactRational upper) {
        public Interval {
            if (lower.compareTo(upper) >= 0) throw new IllegalArgumentException("INVALID_INTERVAL");
        }
    }
    public record Request(SparsePolynomial polynomial, List<Interval> box) {
        public Request {
            box = List.copyOf(box);
            if (box.size() != polynomial.degrees().size()) throw new IllegalArgumentException("BOX_DIMENSION");
        }
    }
    public enum Claim { NONNEGATIVE, STRICTLY_POSITIVE }
    public enum Status { CERTIFIED, INCONCLUSIVE, UNAVAILABLE }
    public sealed interface Step permits Split, Leaf {}
    public record Split(int axis) implements Step {}
    /** Tensor order is x-major: (0,0), (0,1), ..., (1,0), ... . */
    public record Leaf(List<Interval> box, List<ExactRational> coefficients) implements Step {
        public Leaf { box = List.copyOf(box); coefficients = List.copyOf(coefficients); }
    }
    public record Certificate(Request source, Claim claim, List<Step> steps) {
        public Certificate {
            Objects.requireNonNull(source);
            Objects.requireNonNull(claim);
            steps = List.copyOf(steps);
        }
    }
    public record Result(Status status, Optional<Certificate> certificate, String reason, long work) {}
    public record Verification(boolean valid, String reason, long work) {}

    /** Convex-hull enclosure on the original box, including signed coefficient nets. */
    public record Bounds(Request source, List<ExactRational> coefficients, ExactRational lower, ExactRational upper) {
        public Bounds {
            Objects.requireNonNull(source);
            coefficients = List.copyOf(coefficients);
            Objects.requireNonNull(lower);
            Objects.requireNonNull(upper);
        }
    }
    public record BoundsResult(Optional<Bounds> bounds, String reason, long work) {}

    public static BoundsResult bounds(Request source, IterationLimits limits, BooleanSupplier cancellation) {
        var work = new PositivityWork(limits, cancellation);
        try {
            int size = BernsteinTensors.size(source, work);
            work.polynomial(source.polynomial());
            BernsteinTensors.checkBox(source.box(), work);
            var coefficients = List.of(BernsteinTensors.root(source, size, work));
            var extrema = extrema(coefficients, work);
            var bounds = new Bounds(source, coefficients, extrema[0], extrema[1]);
            work.charge(0);
            return new BoundsResult(Optional.of(bounds), "COMPLETE", work.used);
        } catch (PositivityWork.Stop stop) {
            return new BoundsResult(Optional.empty(), stop.getMessage(), work.used);
        }
    }

    public static Verification verifyBounds(Request source, Bounds bounds, IterationLimits limits,
            BooleanSupplier cancellation) {
        var work = new PositivityWork(limits, cancellation);
        try {
            int size = BernsteinTensors.size(source, work);
            BernsteinTensors.checkBox(source.box(), work);
            BernsteinTensors.checkBox(bounds.source().box(), work);
            if (!source.box().equals(bounds.source().box())
                    || !work.samePolynomial(source.polynomial(), bounds.source().polynomial())) {
                return new Verification(false, "SOURCE_MISMATCH", work.used);
            }
            if (bounds.coefficients().size() != size) return new Verification(false, "TENSOR_SIZE", work.used);
            var extrema = extrema(bounds.coefficients(), work);
            boolean valid = extrema[0].equals(work.check(bounds.lower())) && extrema[1].equals(work.check(bounds.upper()))
                && BernsteinReplay.identity(source.polynomial(), new Leaf(source.box(), bounds.coefficients()), work);
            work.charge(0);
            return new Verification(valid, valid ? "VERIFIED" : "BOUNDS_MISMATCH", work.used);
        } catch (PositivityWork.Stop stop) {
            return new Verification(false, stop.getMessage(), work.used);
        }
    }

    private static ExactRational[] extrema(List<ExactRational> coefficients, PositivityWork work) {
        var lower = coefficients.getFirst();
        var upper = lower;
        for (var coefficient : coefficients) {
            work.check(coefficient);
            if (coefficient.compareTo(lower) < 0) lower = coefficient;
            if (coefficient.compareTo(upper) > 0) upper = coefficient;
        }
        return new ExactRational[]{lower, upper};
    }

    private record Patch(List<Interval> box, ExactRational[] coefficients, int depth) {}

    /** Searches to the requested midpoint depth. A signed control net alone proves no counterexample. */
    public static Result prove(Request source, int maxDepth, IterationLimits limits, BooleanSupplier cancellation) {
        if (maxDepth < 0) throw new IllegalArgumentException("NEGATIVE_SUBDIVISION_DEPTH");
        var work = new PositivityWork(limits, cancellation);
        try {
            int size = BernsteinTensors.size(source, work);
            work.polynomial(source.polynomial());
            BernsteinTensors.checkBox(source.box(), work);
            var pending = new ArrayDeque<Patch>();
            var steps = new ArrayList<Step>();
            long cells = size;
            pending.push(new Patch(source.box(), BernsteinTensors.root(source, size, work), 0));
            Claim claim = Claim.STRICTLY_POSITIVE;
            while (!pending.isEmpty()) {
                var patch = pending.pop();
                int sign = 1;
                for (var coefficient : patch.coefficients()) {
                    work.charge(1);
                    sign = Math.min(sign, coefficient.signum());
                }
                if (sign >= 0) {
                    if (sign == 0) claim = Claim.NONNEGATIVE;
                    steps.add(new Leaf(patch.box(), List.of(patch.coefficients())));
                    continue;
                }
                if (patch.depth() == maxDepth) {
                    return new Result(Status.INCONCLUSIVE, Optional.empty(), "DEPTH_LIMIT", work.used);
                }
                int dx = source.polynomial().degrees().getFirst();
                int dy = source.polynomial().degreeY();
                // A constant net cannot improve on subdivision.
                if (dx == 0 && dy == 0) {
                    return new Result(Status.INCONCLUSIVE, Optional.empty(), "SIGNED_COEFFICIENT", work.used);
                }
                int axis = dy == 0 ? 0 : dx == 0 ? 1 : patch.depth() % 2;
                cells += 2L * size;
                work.terms(cells);
                var halves = BernsteinTensors.split(patch.coefficients(), dx, dy, axis, work);
                steps.add(new Split(axis));
                pending.push(new Patch(BernsteinTensors.halfBox(patch.box(), axis, true, work),
                    halves.right(), patch.depth() + 1));
                pending.push(new Patch(BernsteinTensors.halfBox(patch.box(), axis, false, work),
                    halves.left(), patch.depth() + 1));
            }
            var certificate = new Certificate(source, claim, steps);
            work.charge(0);
            return new Result(Status.CERTIFIED, Optional.of(certificate), "CERTIFIED", work.used);
        } catch (PositivityWork.Stop stop) {
            return new Result(Status.UNAVAILABLE, Optional.empty(), stop.getMessage(), work.used);
        }
    }

    /** Checks a complete midpoint partition and tensor identities against the independently supplied source. */
    public static Verification verify(Request source, Certificate certificate, IterationLimits limits,
            BooleanSupplier cancellation) {
        return BernsteinReplay.verify(source, certificate, new PositivityWork(limits, cancellation));
    }
}
