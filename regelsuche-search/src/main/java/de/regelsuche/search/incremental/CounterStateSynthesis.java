package de.regelsuche.search.incremental;

import de.regelsuche.ast.*;
import java.math.BigInteger;
import java.util.*;

/**
 * Bounded synthesis of sufficient counter states, using the existing expression
 * AST. Period analysis of the observation word determines a sufficient state;
 * no support/parity update templates are consulted. The shortest global period
 * is minimal for reversible changes; insert-only changes admit a prefix/cycle.
 */
public final class CounterStateSynthesis {
    private CounterStateSynthesis() { }
    public static final int MAXIMUM_COUNT = 8192;
    public static final long DEFAULT_WORK_BUDGET = 100_000_000L;
    private static final int MAXIMUM_VALUE_BITS = 4096;

    /** Count bounds are part of the proof, not estimates inferred from samples. */
    public record Specification(Expr observation, int maximumCount, boolean allowDeletes) {
        public Specification {
            Objects.requireNonNull(observation, "observation");
            if (maximumCount < 1 || maximumCount > MAXIMUM_COUNT)
                throw new IllegalArgumentException("count bound must be 1.." + MAXIMUM_COUNT);
            validateExpression(observation, 0, new int[1]);
        }
    }

    /** Data-only witness. Arrays are copied both on input and on access. */
    public record Plan(int[] statesByCount, BigInteger[] outputs, int[] onInsert, int[] onDelete) {
        public Plan {
            Objects.requireNonNull(statesByCount); Objects.requireNonNull(outputs);
            Objects.requireNonNull(onInsert); Objects.requireNonNull(onDelete);
            if (statesByCount.length > MAXIMUM_COUNT + 1 || outputs.length > MAXIMUM_COUNT + 1
                    || onInsert.length > MAXIMUM_COUNT + 1 || onDelete.length > MAXIMUM_COUNT + 1)
                throw new IllegalArgumentException("oversized counter plan");
            statesByCount = statesByCount.clone(); outputs = outputs.clone();
            onInsert = onInsert.clone(); onDelete = onDelete.clone();
        }
        @Override public int[] statesByCount() { return statesByCount.clone(); }
        @Override public BigInteger[] outputs() { return outputs.clone(); }
        @Override public int[] onInsert() { return onInsert.clone(); }
        @Override public int[] onDelete() { return onDelete.clone(); }
    }
    public record Verification(boolean accepted, long work, String detail) { }
    public record Derivation(Plan plan, long refinementWork, Verification verification) {
        public long totalWork() { return Math.addExact(refinementWork, verification.work()); }
    }
    public static final class BudgetExceededException extends IllegalArgumentException {
        public BudgetExceededException() { super("counter-state derivation/checking budget exhausted"); }
    }
    private static final class Meter {
        final long limit;
        long work;
        Meter(long limit) {
            if (limit < 1 || limit > DEFAULT_WORK_BUDGET) throw new IllegalArgumentException("invalid work budget");
            this.limit = limit;
        }
        void charge() { if (work == limit) throw new BudgetExceededException(); work++; }
    }

    public static Derivation derive(Specification source) { return derive(source, DEFAULT_WORK_BUDGET); }

    public static Derivation derive(Specification source, long workBudget) {
        Objects.requireNonNull(source);
        Meter meter = new Meter(workBudget);
        BigInteger[] observations = observationWord(source, meter);
        Shape shape = minimalShape(observations, source.allowDeletes, meter);
        Plan plan = buildPlan(observations, shape, source.allowDeletes, meter);
        long synthesisWork = meter.work;
        Verification verification = verify(source, plan, meter);
        if (!verification.accepted) throw new AssertionError("derived counter plan failed its checker: " + verification.detail);
        return new Derivation(plan, synthesisWork, verification);
    }

    private record Shape(int prefix, int period) {
        int size() { return prefix + period; }
        int state(int count) { return count < prefix ? count : prefix + (count - prefix) % period; }
    }

    private static BigInteger[] observationWord(Specification source, Meter meter) {
        BigInteger[] word = new BigInteger[source.maximumCount + 1];
        for (int count = 0; count < word.length; count++) word[count] = evaluate(source.observation, count, meter);
        return word;
    }

    /** Reversal preserves periods. A prefix function on the reversed word
     * yields the shortest period of EVERY suffix in linear work. Reversible
     * changes require a global period; insertions alone allow a finite prefix. */
    private static Shape minimalShape(BigInteger[] word, boolean deletes, Meter meter) {
        int[] prefixFunction = reversedPrefixFunction(word, meter);
        Shape best = new Shape(0, word.length - prefixFunction[word.length - 1]);
        if (deletes) return best;
        for (int start = 1; start < word.length; start++) {
            meter.charge();
            int length = word.length - start;
            Shape candidate = new Shape(start, length - prefixFunction[length - 1]);
            if (candidate.size() < best.size()) best = candidate;
        }
        return best;
    }

    private static int[] reversedPrefixFunction(BigInteger[] word, Meter meter) {
        int[] prefix = new int[word.length];
        for (int i = 1; i < word.length; i++) {
            int matched = prefix[i - 1];
            BigInteger current = word[word.length - i - 1];
            while (matched > 0 && !same(current, word[word.length - matched - 1], meter)) matched = prefix[matched - 1];
            if (same(current, word[word.length - matched - 1], meter)) matched++;
            prefix[i] = matched;
        }
        return prefix;
    }

    private static boolean same(BigInteger left, BigInteger right, Meter meter) {
        meter.charge();
        return left.equals(right);
    }

    private static Plan buildPlan(BigInteger[] word, Shape shape, boolean deletes, Meter meter) {
        int[] states = new int[word.length];
        for (int count = 0; count < word.length; count++) { meter.charge(); states[count] = shape.state(count); }
        BigInteger[] outputs = Arrays.copyOf(word, shape.size());
        int[] plus = new int[shape.size()], minus = new int[shape.size()];
        Arrays.fill(plus, -1); Arrays.fill(minus, -1);
        for (int count = 0; count < word.length; count++) {
            meter.charge();
            if (count + 1 < word.length) plus[states[count]] = states[count + 1];
            if (deletes && count > 0) minus[states[count]] = states[count - 1];
        }
        return new Plan(states, outputs, plus, minus);
    }

    /** Checks the witness by enumeration of ALL legal counts and transitions;
     * does not call the partition refiner or trust the producer's state count. */
    public static Verification verify(Specification source, Plan plan) {
        return verify(Objects.requireNonNull(source), Objects.requireNonNull(plan), new Meter(DEFAULT_WORK_BUDGET));
    }
    private static Verification verify(Specification source, Plan plan, Meter meter) {
        long start = meter.work;
        int n = source.maximumCount + 1, states = plan.outputs.length;
        if (plan.statesByCount.length != n || states < 1 || states > n
                || plan.onInsert.length != states || plan.onDelete.length != states)
            return new Verification(false, meter.work - start, "table shape");
        boolean[] reached = new boolean[states];
        for (int state = 0; state < states; state++) {
            meter.charge();
            if (plan.outputs[state] == null || plan.outputs[state].bitLength() > MAXIMUM_VALUE_BITS
                    || plan.onInsert[state] < -1 || plan.onInsert[state] >= states
                    || plan.onDelete[state] < -1 || plan.onDelete[state] >= states
                    || !source.allowDeletes && plan.onDelete[state] != -1)
                return new Verification(false, meter.work - start, "invalid state");
        }
        for (int count = 0; count < n; count++) {
            meter.charge();
            int state = plan.statesByCount[count];
            if (state < 0 || state >= states || !evaluate(source.observation, count, meter).equals(plan.outputs[state]))
                return new Verification(false, meter.work - start, "observation at " + count);
            reached[state] = true;
            if (count + 1 < n && plan.onInsert[state] != plan.statesByCount[count + 1]
                    || source.allowDeletes && count > 0 && plan.onDelete[state] != plan.statesByCount[count - 1])
                return new Verification(false, meter.work - start, "transition at " + count);
        }
        for (boolean used : reached) if (!used) return new Verification(false, meter.work - start, "unreachable state");
        return new Verification(true, meter.work - start, "all bounded observations/transitions checked");
    }

    public static Prepared prepareVerified(Specification source, Plan plan) {
        Verification checked = verify(source, plan);
        if (!checked.accepted) throw new IllegalArgumentException("counter plan rejected: " + checked.detail);
        return new Prepared(source, plan);
    }
    public static final class Prepared {
        private final Specification source;
        private final Plan plan;
        private Prepared(Specification source, Plan plan) { this.source = source; this.plan = plan; }
        public <K> Accumulator<K> newAccumulator() {
            if (plan.outputs[plan.statesByCount[0]].signum() != 0)
                throw new IllegalArgumentException("grouped sums require f(0)=0");
            return new Accumulator<>(source, plan);
        }
    }
    /** Sum of per-key observations. Sessions are independent and not thread-safe.
     * Key equality/hash codes must remain stable while a key is present. */
    public static final class Accumulator<K> {
        private record Bucket(int count, int state) { }
        private final Specification source;
        private final Plan plan;
        private final Map<K, Bucket> buckets = new HashMap<>();
        private BigInteger value = BigInteger.ZERO;
        private Accumulator(Specification source, Plan plan) { this.source = source; this.plan = plan; }
        public BigInteger value() { return value; }
        public void insert(K key) { change(key, 1); }
        public void delete(K key) { change(key, -1); }
        private void change(K key, int delta) {
            Objects.requireNonNull(key, "key");
            Bucket old = buckets.get(key);
            int count = old == null ? 0 : old.count;
            int state = old == null ? plan.statesByCount[0] : old.state;
            if (delta < 0 && (!source.allowDeletes || count == 0) || delta > 0 && count == source.maximumCount)
                throw new IllegalStateException("change outside the proven count/update bounds");
            int next = delta > 0 ? plan.onInsert[state] : plan.onDelete[state];
            BigInteger updated = value.subtract(plan.outputs[state]).add(plan.outputs[next]);
            if (count + delta == 0) buckets.remove(key);
            else buckets.put(key, new Bucket(count + delta, next));
            value = updated;
        }
    }

    private static void validateExpression(Expr expression, int depth, int[] nodes) {
        if (depth > 32 || ++nodes[0] > 128) throw new IllegalArgumentException("observer AST too large");
        switch (expression) {
            case NumberExpr number -> {
                if (!number.value().denominator().equals(BigInteger.ONE) || number.value().numerator().bitLength() > 256)
                    throw new IllegalArgumentException("observer requires bounded integer constants");
            }
            case VariableExpr variable -> {
                if (!variable.name().equals("count")) throw new IllegalArgumentException("only count is bound");
            }
            case BinaryExpr binary -> {
                if (binary.operator() != BinaryOperator.ADD && binary.operator() != BinaryOperator.SUB && binary.operator() != BinaryOperator.MUL)
                    throw new IllegalArgumentException("observer supports +, -, *");
                validateExpression(binary.left(), depth + 1, nodes); validateExpression(binary.right(), depth + 1, nodes);
            }
            case FunctionExpr function -> {
                int arity = switch (function.name()) { case "positive" -> 1; case "mod" -> 2; default -> 0; };
                if (arity == 0 || function.arguments().size() != arity) throw new IllegalArgumentException("unknown observer function/arity");
                for (Expr argument : function.arguments()) validateExpression(argument, depth + 1, nodes);
            }
        }
    }
    private static BigInteger evaluate(Expr expression, int count, Meter meter) {
        meter.charge();
        BigInteger value = switch (expression) {
            case NumberExpr number -> number.value().numerator();
            case VariableExpr _ -> BigInteger.valueOf(count);
            case BinaryExpr binary -> {
                BigInteger left = evaluate(binary.left(), count, meter), right = evaluate(binary.right(), count, meter);
                yield switch (binary.operator()) {
                    case ADD -> left.add(right); case SUB -> left.subtract(right); case MUL -> left.multiply(right);
                    default -> throw new IllegalArgumentException("unsupported observer operation");
                };
            }
            case FunctionExpr function -> {
                BigInteger first = evaluate(function.argument(), count, meter);
                if (function.name().equals("positive")) yield first.signum() > 0 ? BigInteger.ONE : BigInteger.ZERO;
                BigInteger modulus = evaluate(function.arguments().get(1), count, meter);
                if (modulus.signum() <= 0) throw new IllegalArgumentException("mod requires a positive modulus");
                yield first.mod(modulus);
            }
        };
        if (value.bitLength() > MAXIMUM_VALUE_BITS) throw new IllegalArgumentException("observer value too large");
        return value;
    }
}
