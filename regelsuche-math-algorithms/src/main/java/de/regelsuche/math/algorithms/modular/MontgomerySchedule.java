package de.regelsuche.math.algorithms.modular;

import de.regelsuche.math.algorithms.equivalence.Monomial;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Bounded certificates for signed Montgomery schedules and their exact canonical observations. */
public final class MontgomerySchedule {
    public static final int MAX_INPUTS = 128;
    public static final int MAX_STEPS = 4096;
    public static final int MAX_TERMS = 8192;
    private static final int MAX_DEGREE = 64;
    private MontgomerySchedule() {}

    public enum Word {
        SHORT(16), INT(32), LONG(64);
        private final int bits;
        Word(int bits) { this.bits = bits; }
        public int bits() { return bits; }
        BigInteger minimum() { return BigInteger.ONE.shiftLeft(bits - 1).negate(); }
        BigInteger maximum() { return BigInteger.ONE.shiftLeft(bits - 1).subtract(BigInteger.ONE); }
    }

    /** Inclusive signed integer interval. Bounds are assumptions the source-language consumer must justify. */
    public record Interval(long minimum, long maximum) {
        public Interval {
            if (minimum > maximum) throw new IllegalArgumentException("INVALID_INTERVAL");
        }
        BigInteger low() { return BigInteger.valueOf(minimum); }
        BigInteger high() { return BigInteger.valueOf(maximum); }
    }

    /**
     * R=2^radixBits, positive odd q below R/2, and q*inverse=1 mod R.
     * Reduction is u=signedLowWord(a*inverse), M(a)=(a-u*q)/R.
     * Only the low-word multiplication intentionally wraps. Numerator arithmetic must fit 2*radixBits.
     */
    public record Parameters(int radixBits, long modulus, long inverse) {
        public Parameters {
            if ((radixBits != 16 && radixBits != 32) || modulus < 3 || (modulus & 1) == 0
                    || modulus >= (1L << (radixBits - 1)) || inverse < 0 || inverse >= (1L << radixBits)) {
                throw new IllegalArgumentException("INVALID_MONTGOMERY_PARAMETERS");
            }
            var radix = BigInteger.ONE.shiftLeft(radixBits);
            if (!BigInteger.valueOf(modulus).multiply(BigInteger.valueOf(inverse)).mod(radix).equals(BigInteger.ONE)) {
                throw new IllegalArgumentException("INVALID_MONTGOMERY_INVERSE");
            }
        }
        public Word residueWord() { return radixBits == 16 ? Word.SHORT : Word.INT; }
        public Word accumulatorWord() { return radixBits == 16 ? Word.INT : Word.LONG; }
    }

    public record Input(String name, Word word, Interval range) {
        public Input {
            if (name == null || name.isBlank() || name.length() > 128) throw new IllegalArgumentException("INVALID_INPUT_NAME");
            Objects.requireNonNull(word);
            Objects.requireNonNull(range);
        }
    }

    public enum Operation { CONSTANT, ADD, SUBTRACT, MULTIPLY, CAST, MONTGOMERY }

    /** Operands reference inputs first, then preceding steps. CAST is accepted only when value-preserving. */
    public record Step(Operation operation, Word word, List<Integer> operands, long literal) {
        public Step {
            Objects.requireNonNull(operation);
            Objects.requireNonNull(word);
            int arity = switch (operation) {
                case CONSTANT -> 0;
                case CAST, MONTGOMERY -> 1;
                default -> 2;
            };
            if (operands.size() != arity || operation != Operation.CONSTANT && literal != 0) {
                throw new IllegalArgumentException("INVALID_STEP_ARITY");
            }
            operands = List.copyOf(operands);
        }
        public static Step apply(Operation operation, Word word, int... operands) {
            var references = new ArrayList<Integer>(operands.length);
            for (int operand : operands) references.add(operand);
            return new Step(operation, word, references, 0);
        }
        public static Step constant(Word word, long value) { return new Step(Operation.CONSTANT, word, List.of(), value); }
    }

    /** A finite straight-line pure arithmetic program; arrays, exceptions and memory effects belong to the adapter. */
    public record Schedule(List<Step> steps, List<Integer> outputs) {
        public Schedule {
            if (steps.size() > MAX_STEPS || outputs.isEmpty() || outputs.size() > 64) {
                throw new IllegalArgumentException("SCHEDULE_SIZE_LIMIT");
            }
            steps = List.copyOf(steps);
            outputs = List.copyOf(outputs);
        }
    }

    /** CANONICAL_RESIDUE means equality after the mathematical floorMod(value,q), not raw machine equality. */
    public enum Observation { CONGRUENCE, CANONICAL_RESIDUE }

    public record Request(Parameters parameters, List<Input> inputs, Schedule source, Schedule target,
            Observation observation) {
        public Request {
            Objects.requireNonNull(parameters);
            Objects.requireNonNull(source);
            Objects.requireNonNull(target);
            Objects.requireNonNull(observation);
            if (inputs.size() > MAX_INPUTS) throw new IllegalArgumentException("INPUT_SIZE_LIMIT");
            inputs = List.copyOf(inputs);
        }
    }

    /** Untrusted, source-bound evidence. Both range lists include inputs followed by steps. Replay before use. */
    public record Certificate(Request request, List<Interval> sourceRanges, List<Interval> targetRanges,
            int sourceReductions, int targetReductions) {
        public Certificate {
            Objects.requireNonNull(request);
            if (sourceRanges.size() > MAX_INPUTS + MAX_STEPS || targetRanges.size() > MAX_INPUTS + MAX_STEPS) {
                throw new IllegalArgumentException("EVIDENCE_SIZE_LIMIT");
            }
            sourceRanges = List.copyOf(sourceRanges);
            targetRanges = List.copyOf(targetRanges);
        }
    }

    public record Result(Optional<Certificate> certificate, String reason, long work) {}
    public record Verification(boolean valid, String reason, long work) {}

    /** Certifies all machine ranges and the polynomial identity modulo q, accounting for every inverse radix. */
    public static Result certify(Request request, IterationLimits limits, BooleanSupplier cancellation) {
        return certify(request, new Work(limits, cancellation));
    }

    static Result certify(Request request, Work work) {
        try {
            var certificate = check(Objects.requireNonNull(request), work);
            return new Result(Optional.of(certificate), "VERIFIED", work.used);
        } catch (Unavailable unavailable) {
            return new Result(Optional.empty(), unavailable.getMessage(), work.used);
        }
    }

    /** Independently recomputes the ranges and residue identities; never calls the schedule planner. */
    public static Verification verify(Request expected, Certificate certificate, IterationLimits limits,
            BooleanSupplier cancellation) {
        Objects.requireNonNull(expected);
        Objects.requireNonNull(certificate);
        var work = new Work(limits, cancellation);
        try {
            work.charge(1L + expected.inputs().size() + expected.source().steps().size() + expected.target().steps().size()
                    + certificate.request().inputs().size() + certificate.request().source().steps().size()
                    + certificate.request().target().steps().size());
            if (!expected.equals(certificate.request())) return new Verification(false, "SOURCE_MISMATCH", work.used);
            var actual = check(expected, work);
            work.charge(1L + actual.sourceRanges().size() + actual.targetRanges().size());
            if (!actual.sourceRanges().equals(certificate.sourceRanges())
                    || !actual.targetRanges().equals(certificate.targetRanges())) {
                return new Verification(false, "RANGE_EVIDENCE_MISMATCH", work.used);
            }
            if (actual.sourceReductions() != certificate.sourceReductions()
                    || actual.targetReductions() != certificate.targetReductions()) {
                return new Verification(false, "OPERATION_COUNT_MISMATCH", work.used);
            }
            work.charge(0);
            return new Verification(true, "VERIFIED", work.used);
        } catch (Unavailable unavailable) {
            return new Verification(false, unavailable.getMessage(), work.used);
        }
    }

    private static Certificate check(Request request, Work work) {
        var names = new HashSet<String>();
        work.size(request.inputs().size());
        for (var input : request.inputs()) {
            work.charge(1);
            if (!names.add(input.name())) throw new Unavailable("DUPLICATE_INPUT");
        }
        var source = analyze(request.parameters(), request.inputs(), request.source(), work);
        var target = analyze(request.parameters(), request.inputs(), request.target(), work);
        if (source.outputs.size() != target.outputs.size()) throw new Unavailable("OUTPUT_COUNT_MISMATCH");
        for (int i = 0; i < source.outputs.size(); i++) {
            work.charge(1L + source.outputs.get(i).size() + target.outputs.get(i).size());
            if (!source.outputs.get(i).equals(target.outputs.get(i))) throw new Unavailable("RESIDUE_IDENTITY_MISMATCH");
        }
        work.charge(0);
        return new Certificate(request, source.ranges, target.ranges, source.reductions, target.reductions);
    }

    private record Value(Word word, Interval range, Map<Monomial, BigInteger> polynomial) {}
    private record Analysis(List<Interval> ranges, List<Map<Monomial, BigInteger>> outputs, int reductions) {}

    private static Analysis analyze(Parameters p, List<Input> inputs, Schedule schedule, Work work) {
        work.size(inputs.size() + schedule.steps().size());
        var q = BigInteger.valueOf(p.modulus());
        var inverseRadix = BigInteger.ONE.shiftLeft(p.radixBits()).modInverse(q);
        var values = new ArrayList<Value>();
        for (var input : inputs) {
            work.charge(1);
            fit(input.range().low(), input.range().high(), input.word());
            values.add(new Value(input.word(), input.range(), Map.of(Monomial.variable(input.name()), BigInteger.ONE)));
        }
        int reductions = 0;
        for (var step : schedule.steps()) {
            work.charge(1);
            for (int ref : step.operands()) if (ref < 0 || ref >= values.size()) throw new Unavailable("INVALID_REFERENCE");
            Value a = step.operands().isEmpty() ? null : values.get(step.operands().getFirst());
            Value b = step.operands().size() < 2 ? null : values.get(step.operands().get(1));
            Interval range;
            Map<Monomial, BigInteger> polynomial;
            switch (step.operation()) {
                case CONSTANT -> {
                    range = fit(BigInteger.valueOf(step.literal()), BigInteger.valueOf(step.literal()), step.word());
                    var value = BigInteger.valueOf(step.literal()).mod(q);
                    polynomial = value.signum() == 0 ? Map.of() : Map.of(Monomial.constant(), value);
                }
                case CAST -> {
                    range = fit(a.range.low(), a.range.high(), step.word());
                    polynomial = a.polynomial;
                }
                case MONTGOMERY -> {
                    if (a.word.bits() > p.accumulatorWord().bits() || step.word() != p.residueWord()) {
                        throw new Unavailable("MONTGOMERY_WORD_MISMATCH");
                    }
                    range = reductionRange(p, a.range);
                    polynomial = scale(a.polynomial, inverseRadix, q, work);
                    reductions++;
                }
                default -> {
                    if (step.word() == Word.SHORT || step.word().bits() < Math.max(a.word.bits(), b.word.bits())) {
                        throw new Unavailable("ARITHMETIC_PROMOTION_MISMATCH");
                    }
                    range = binaryRange(step.operation(), a.range, b.range, step.word());
                    polynomial = step.operation() == Operation.MULTIPLY
                            ? multiply(a.polynomial, b.polynomial, q, work)
                            : add(a.polynomial, b.polynomial, step.operation() == Operation.SUBTRACT, q, work);
                }
            }
            values.add(new Value(step.word(), range, polynomial));
        }
        var outputs = new ArrayList<Map<Monomial, BigInteger>>();
        for (int ref : schedule.outputs()) {
            work.charge(1);
            if (ref < 0 || ref >= values.size()) throw new Unavailable("INVALID_REFERENCE");
            outputs.add(values.get(ref).polynomial);
        }
        return new Analysis(values.stream().map(Value::range).toList(), outputs, reductions);
    }

    private static Interval reductionRange(Parameters p, Interval input) {
        var radix = BigInteger.ONE.shiftLeft(p.radixBits());
        var half = radix.shiftRight(1);
        var q = BigInteger.valueOf(p.modulus());
        // u ranges from -R/2 through R/2-1. Because q*inverse=1 mod R, the numerator is divisible by R.
        var low = input.low().subtract(half.subtract(BigInteger.ONE).multiply(q));
        var high = input.high().add(half.multiply(q));
        fit(low, high, p.accumulatorWord());
        var quotientLow = low.negate().shiftRight(p.radixBits()).negate(); // ceil(low/R)
        var quotientHigh = high.shiftRight(p.radixBits()); // floor(high/R)
        return fit(quotientLow, quotientHigh, p.residueWord());
    }

    private static Interval binaryRange(Operation operation, Interval a, Interval b, Word word) {
        if (operation == Operation.ADD) return fit(a.low().add(b.low()), a.high().add(b.high()), word);
        if (operation == Operation.SUBTRACT) return fit(a.low().subtract(b.high()), a.high().subtract(b.low()), word);
        var aa = a.low().multiply(b.low());
        var ab = a.low().multiply(b.high());
        var ba = a.high().multiply(b.low());
        var bb = a.high().multiply(b.high());
        return fit(aa.min(ab).min(ba).min(bb), aa.max(ab).max(ba).max(bb), word);
    }

    private static Interval fit(BigInteger low, BigInteger high, Word word) {
        if (low.compareTo(word.minimum()) < 0 || high.compareTo(word.maximum()) > 0) throw new Unavailable("SIGNED_OVERFLOW");
        return new Interval(low.longValueExact(), high.longValueExact());
    }

    private static Map<Monomial, BigInteger> scale(Map<Monomial, BigInteger> input, BigInteger factor,
            BigInteger modulus, Work work) {
        var result = new HashMap<Monomial, BigInteger>();
        for (var term : input.entrySet()) {
            work.charge(1);
            merge(result, term.getKey(), term.getValue().multiply(factor), modulus, work);
        }
        return result;
    }

    private static Map<Monomial, BigInteger> add(Map<Monomial, BigInteger> a, Map<Monomial, BigInteger> b,
            boolean subtract, BigInteger modulus, Work work) {
        work.charge(a.size());
        var result = new HashMap<>(a);
        for (var term : b.entrySet()) {
            work.charge(1);
            merge(result, term.getKey(), subtract ? term.getValue().negate() : term.getValue(), modulus, work);
        }
        return result;
    }

    private static Map<Monomial, BigInteger> multiply(Map<Monomial, BigInteger> a, Map<Monomial, BigInteger> b,
            BigInteger modulus, Work work) {
        var result = new HashMap<Monomial, BigInteger>();
        for (var x : a.entrySet()) for (var y : b.entrySet()) {
            work.charge(1L + x.getKey().powers().size() + y.getKey().powers().size());
            if (x.getKey().totalDegree() + y.getKey().totalDegree() > MAX_DEGREE) throw new Unavailable("DEGREE_LIMIT");
            merge(result, x.getKey().multiply(y.getKey()), x.getValue().multiply(y.getValue()), modulus, work);
        }
        return result;
    }

    private static void merge(Map<Monomial, BigInteger> result, Monomial key, BigInteger value,
            BigInteger modulus, Work work) {
        var coefficient = result.getOrDefault(key, BigInteger.ZERO).add(value).mod(modulus);
        if (coefficient.signum() == 0) result.remove(key);
        else {
            if (!result.containsKey(key)) work.size(result.size() + 1);
            result.put(key, coefficient);
        }
    }

    static final class Work {
        private final IterationLimits limits;
        private final BooleanSupplier cancellation;
        private final long start = System.nanoTime();
        private final long timeout;
        long used;
        Work(IterationLimits limits, BooleanSupplier cancellation) {
            this.limits = Objects.requireNonNull(limits);
            this.cancellation = Objects.requireNonNull(cancellation);
            timeout = TimeUnit.MILLISECONDS.toNanos(limits.timeoutMillis());
        }
        void charge(long amount) {
            if (cancellation.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new Unavailable("CANCELLED");
            if (System.nanoTime() - start >= timeout) throw new Unavailable("TIME_LIMIT");
            if (amount < 0 || amount > limits.maximumWork() - used) throw new Unavailable("WORK_LIMIT");
            used += amount;
        }
        void size(int count) {
            charge(1);
            if (count > Math.min(limits.maximumTerms(), MAX_TERMS)) throw new Unavailable("TERM_LIMIT");
        }
    }

    static final class Unavailable extends RuntimeException {
        Unavailable(String reason) { super(reason, null, false, false); }
    }
}
