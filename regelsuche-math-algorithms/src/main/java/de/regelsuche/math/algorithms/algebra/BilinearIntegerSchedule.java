package de.regelsuche.math.algorithms.algebra;

import de.regelsuche.math.algorithms.algebra.BilinearProgram.Rejection;
import de.regelsuche.math.algorithms.algebra.BilinearProgram.Result;
import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.function.BooleanSupplier;

/** Exact integer lowering with a width-sensitive, deterministic magnitude-word cost model. */
public final class BilinearIntegerSchedule {
    private BilinearIntegerSchedule() {}

    /** b allows both signs with abs(x) < 2^b. In particular b=0 permits only zero. */
    public record Profile(List<Integer> leftBits, List<Integer> rightBits) {
        public Profile {
            leftBits = List.copyOf(leftBits);
            rightBits = List.copyOf(rightBits);
            if (leftBits.isEmpty() || rightBits.isEmpty()) throw new IllegalArgumentException("EMPTY_INPUT_PROFILE");
            for (int bits : leftBits) checkWidth(bits);
            for (int bits : rightBits) checkWidth(bits);
        }

        private static void checkWidth(int bits) {
            if (bits < 0 || bits > IterationLimits.MAX_RUNTIME_BITS)
                throw new IllegalArgumentException("INVALID_MAGNITUDE_WIDTH");
        }

        public long retainedCells() { return 2L + leftBits.size() + rightBits.size(); }
    }

    public enum Op { ADD, SUBTRACT, NEGATE, SCALE, MULTIPLY }

    /** Left and right are slot indices. Only SCALE uses coefficient; unary operations have right=-1. */
    public record Step(Op op, int left, int right, BigInteger coefficient) {}

    /**
     * limbWork counts modeled arithmetic plus result-magnitude writes in 32-bit words.
     * Products use schoolbook width products. Each operation materializes one modeled object.
     * NEGATE can share its magnitude. These counts describe a schedule, not JVM allocations.
     */
    public record Cost(long limbWork, long allocatedWords, long allocatedObjects,
                       long variableProducts) implements Comparable<Cost> {
        @Override public int compareTo(Cost other) {
            int order = Long.compare(limbWork, other.limbWork);
            if (order == 0) order = Long.compare(allocatedWords, other.allocatedWords);
            if (order == 0) order = Long.compare(allocatedObjects, other.allocatedObjects);
            if (order == 0) order = Long.compare(variableProducts, other.variableProducts);
            return order;
        }
    }

    /** Slots hold the left then right inputs, zero, and one result for each successive step. */
    public static final class Plan {
        private final Profile profile;
        private final List<Step> steps;
        private final List<Integer> outputs;
        private final List<Integer> magnitudeBits;
        private final Cost cost;
        private final long retainedCells;

        private Plan(Builder builder, List<Integer> outputs) {
            this.profile = builder.profile;
            this.steps = List.copyOf(builder.steps);
            this.outputs = List.copyOf(outputs);
            this.magnitudeBits = List.copyOf(builder.bits);
            this.cost = new Cost(builder.limbWork, builder.allocatedWords, steps.size(), builder.products);
            this.retainedCells = 7L + magnitudeBits.size() + outputs.size() + 6L * steps.size() + builder.coefficientWords;
        }

        public Profile profile() { return profile; }
        public int zeroSlot() { return profile.leftBits().size() + profile.rightBits().size(); }
        public List<Step> steps() { return steps; }
        public List<Integer> outputs() { return outputs; }
        public List<Integer> magnitudeBits() { return magnitudeBits; }
        public Cost cost() { return cost; }
        /** Additional cells beyond the immutable profile shared with the caller. */
        public long retainedCells() { return retainedCells; }
    }

    public static Result<Plan> plan(BilinearProgram program, Profile profile, IterationLimits limits,
                                    BooleanSupplier cancellation) {
        return BilinearProgram.run(limits, cancellation, work -> {
            long cells = profile.retainedCells() + BilinearProgram.matrixCells(program.u())
                + BilinearProgram.matrixCells(program.v()) + BilinearProgram.matrixCells(program.w());
            return plan(program, profile, cells, work);
        });
    }

    /** Caller includes program, profile, and all other live portfolio data in retained. */
    static Plan plan(BilinearProgram program, Profile profile, long retained, CertificateWork work) {
        work.cells(retained);
        if (program.u().size() != program.v().size() || program.w().isEmpty())
            throw new Rejection("PROGRAM_SHAPE");
        var builder = new Builder(profile, program, retained, work);
        return builder.lower(program);
    }

    /** Executes the exact schedule after checking the profiled magnitude of each signed input. */
    public static Result<List<BigInteger>> evaluate(Plan plan, List<BigInteger> left, List<BigInteger> right,
            IterationLimits limits, BooleanSupplier cancellation) {
        return BilinearProgram.run(limits, cancellation, work -> {
            if (left.size() != plan.profile.leftBits().size() || right.size() != plan.profile.rightBits().size())
                throw new Rejection("INPUT_VECTOR_SHAPE");
            long retained = plan.retainedCells() + plan.profile.retainedCells() + plan.magnitudeBits.size()
                + 2L * plan.outputs.size() + left.size() + right.size();
            work.cells(retained);
            var slots = new ArrayList<BigInteger>(plan.magnitudeBits.size());
            retained = addInputs(slots, left, plan.profile.leftBits(), retained, work);
            retained = addInputs(slots, right, plan.profile.rightBits(), retained, work);
            slots.add(BigInteger.ZERO);
            for (Step step : plan.steps) {
                var a = slots.get(step.left());
                var b = step.right() >= 0 ? slots.get(step.right()) : step.coefficient();
                int aBits = magnitudeBits(a), bBits = magnitudeBits(b);
                int bound = resultBits(step.op(), aBits, bBits, step.coefficient());
                work.cells(retained + words(bound) + 1);
                work.charge(arithmeticWork(step.op(), aBits, bBits));
                var value = switch (step.op()) {
                    case ADD -> a.add(b);
                    case SUBTRACT -> a.subtract(b);
                    case NEGATE -> a.negate();
                    case SCALE, MULTIPLY -> a.multiply(b);
                };
                slots.add(work.check(value));
                retained += words(magnitudeBits(value)) + 1;
            }
            work.charge(plan.outputs.size());
            var results = new ArrayList<BigInteger>(plan.outputs.size());
            for (int slot : plan.outputs) results.add(slots.get(slot));
            return List.copyOf(results);
        });
    }

    private static long addInputs(List<BigInteger> slots, List<BigInteger> inputs, List<Integer> bounds,
                                  long retained, CertificateWork work) {
        for (int i = 0; i < inputs.size(); i++) {
            var value = Objects.requireNonNull(inputs.get(i));
            int bits = magnitudeBits(value);
            if (bits > bounds.get(i)) throw new Rejection("INPUT_OUTSIDE_PROFILE");
            retained += words(bits);
            work.cells(retained);
            slots.add(work.check(value));
        }
        return retained;
    }

    // Unlike BigInteger.bitLength(), this includes the magnitude bit of negative powers of two.
    private static int magnitudeBits(BigInteger value) { return value.abs().bitLength(); }

    private static long words(int bits) { return (bits + 31L) / 32; }

    private static long arithmeticWork(Op op, int a, int b) {
        return switch (op) {
            case ADD, SUBTRACT -> Math.max(1, words(Math.max(a, b)));
            case NEGATE -> 1;
            case SCALE, MULTIPLY -> Math.max(1, words(a) * words(b));
        };
    }

    private static int resultBits(Op op, int a, int b, BigInteger coefficient) {
        return switch (op) {
            case NEGATE -> a;
            case ADD, SUBTRACT -> Math.min(a, b) == 0 ? Math.max(a, b) : Math.max(a, b) + 1;
            case SCALE -> {
                int shift = coefficient.getLowestSetBit();
                yield a == 0 ? 0 : a + (shift == b - 1 ? shift : b);
            }
            case MULTIPLY -> Math.min(a, b) <= 1 ? (Math.min(a, b) == 0 ? 0 : Math.max(a, b)) : a + b;
        };
    }

    /** A negative term denotes minus the value in its slot until the sign is absorbed by another operation. */
    private record Term(int slot, boolean negative) {}

    private static final class Builder {
        private final Profile profile;
        private final CertificateWork work;
        private final long fixedCells;
        private final ArrayList<Step> steps = new ArrayList<>();
        private final ArrayList<Integer> bits = new ArrayList<>();
        private final HashMap<Step, Integer> available = new HashMap<>();
        private final int zeroSlot;
        private long limbWork;
        private long allocatedWords;
        private long coefficientWords;
        private long products;

        Builder(Profile profile, BilinearProgram program, long retained, CertificateWork work) {
            this.profile = profile;
            this.work = work;
            int rank = program.u().size();
            int inputs = Math.addExact(profile.leftBits().size(), profile.rightBits().size());
            int longest = Math.max(rank, Math.max(profile.leftBits().size(), profile.rightBits().size()));
            // Input, product and output terms, a temporary priority queue and the final immutable snapshots.
            this.fixedCells = retained + 2L * inputs + 3L * rank + 3L * program.w().size() + 5L * longest + 12;
            work.cells(fixedCells);
            work.charge(inputs);
            bits.addAll(profile.leftBits());
            bits.addAll(profile.rightBits());
            zeroSlot = bits.size();
            bits.add(0);
        }

        Plan lower(BilinearProgram program) {
            var products = new ArrayList<Term>(program.u().size());
            int rightOffset = profile.leftBits().size();
            for (int p = 0; p < program.u().size(); p++) {
                var left = form(program.u().get(p), null, 0, rightOffset);
                var right = form(program.v().get(p), null, rightOffset, profile.rightBits().size());
                int slot = left.slot() == zeroSlot || right.slot() == zeroSlot
                    ? zeroSlot : emit(Op.MULTIPLY, left.slot(), right.slot(), BigInteger.ONE);
                products.add(new Term(slot, left.negative() != right.negative()));
            }
            var outputs = new ArrayList<Integer>(program.w().size());
            for (var row : program.w()) {
                var value = form(row, products, 0, products.size());
                outputs.add(value.negative() ? emit(Op.NEGATE, value.slot(), -1, BigInteger.ONE) : value.slot());
            }
            work.charge(bits.size() + (long) outputs.size() + steps.size());
            return new Plan(this, outputs);
        }

        private Term form(List<ExactRational> coefficients, List<Term> terms, int offset, int width) {
            if (coefficients.size() != width) throw new Rejection("PROGRAM_SHAPE");
            var queue = new PriorityQueue<Term>((a, b) -> {
                work.charge(1);
                int order = Integer.compare(bits.get(a.slot()), bits.get(b.slot()));
                if (order == 0) order = Integer.compare(a.slot(), b.slot());
                if (order == 0) order = Boolean.compare(a.negative(), b.negative());
                return order;
            });
            for (int i = 0; i < width; i++) {
                var coefficient = work.check(coefficients.get(i));
                if (!coefficient.denominator().equals(BigInteger.ONE)) throw new Rejection("NON_INTEGRAL_COEFFICIENT");
                if (coefficient.isZero()) continue;
                var source = terms == null ? new Term(offset + i, false) : terms.get(i);
                if (source.slot() == zeroSlot) continue;
                BigInteger weight = coefficient.numerator();
                if (source.negative()) weight = weight.negate();
                if (weight.abs().equals(BigInteger.ONE)) {
                    queue.add(new Term(source.slot(), weight.signum() < 0));
                } else {
                    queue.add(new Term(emit(Op.SCALE, source.slot(), -1, weight), false));
                }
            }
            if (queue.isEmpty()) return new Term(zeroSlot, false);
            while (queue.size() > 1) queue.add(combine(queue.remove(), queue.remove()));
            return queue.remove();
        }

        private Term combine(Term a, Term b) {
            if (a.slot() == zeroSlot) return b;
            if (b.slot() == zeroSlot) return a;
            if (a.negative() == b.negative())
                return new Term(emit(Op.ADD, a.slot(), b.slot(), BigInteger.ONE), a.negative());
            if (a.slot() == b.slot()) return new Term(zeroSlot, false);
            int positive = a.negative() ? b.slot() : a.slot();
            int negative = a.negative() ? a.slot() : b.slot();
            return new Term(emit(Op.SUBTRACT, positive, negative, BigInteger.ONE), false);
        }

        private int emit(Op op, int left, int right, BigInteger coefficient) {
            if ((op == Op.ADD || op == Op.MULTIPLY) && right < left) {
                int swap = left;
                left = right;
                right = swap;
            }
            work.charge(1L + words(magnitudeBits(coefficient)));
            var step = new Step(op, left, right, coefficient);
            var existing = available.get(step);
            if (existing != null) return existing;
            long storedWords = op == Op.SCALE ? words(magnitudeBits(coefficient)) : 0;
            work.cells(fixedCells + 14L * (steps.size() + 1) + coefficientWords + storedWords);
            int a = bits.get(left);
            int b = right >= 0 ? bits.get(right) : magnitudeBits(coefficient);
            int bound = resultBits(op, a, b, coefficient);
            long resultWords = op == Op.NEGATE ? 0 : words(bound);
            limbWork = Math.addExact(limbWork, arithmeticWork(op, a, b) + resultWords);
            allocatedWords += resultWords;
            coefficientWords += storedWords;
            if (op == Op.MULTIPLY) products++;
            int slot = bits.size();
            bits.add(bound);
            steps.add(step);
            available.put(step, slot);
            return slot;
        }
    }
}
