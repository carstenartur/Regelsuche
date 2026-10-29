package de.regelsuche.search.incremental;

import de.regelsuche.ast.*;
import java.math.BigInteger;
import java.util.*;

/**
 * Bounded synthesis of sufficient counter states, using the existing expression
 * AST. An observation f(count) and the allowed changes determine the partition;
 * no support/parity update templates are consulted. Refinement is conservative:
 * the result is sufficient, not promised to be a globally minimal automaton.
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
        int n = source.maximumCount + 1;
        BigInteger[] observations = new BigInteger[n];
        int[] classes = new int[n];
        var byOutput = new LinkedHashMap<BigInteger, Integer>();
        for (int count = 0; count < n; count++) {
            observations[count] = evaluate(source.observation, count, meter);
            classes[count] = byOutput.computeIfAbsent(observations[count], _ -> byOutput.size());
        }
        int classCount = byOutput.size();
        while (true) {
            var groups = new ArrayList<List<Integer>>(classCount);
            for (int i = 0; i < classCount; i++) groups.add(new ArrayList<>());
            for (int count = 0; count < n; count++) { meter.charge(); groups.get(classes[count]).add(count); }
            int[] refined = new int[n];
            int nextClass = 0;
            for (var group : groups) {
                // Undefined transitions at the count bounds are wildcards. They
                // are never executable: the accumulator enforces legal counts.
                var signatures = new ArrayList<int[]>();
                for (int count : group) {
                    int up = count + 1 < n ? classes[count + 1] : -1;
                    int down = source.allowDeletes && count > 0 ? classes[count - 1] : -1;
                    int[] match = null;
                    for (int[] signature : signatures) {
                        meter.charge();
                        if (compatible(up, signature[0]) && compatible(down, signature[1])) { match = signature; break; }
                    }
                    if (match == null) {
                        match = new int[] {up, down, nextClass++}; signatures.add(match);
                    } else {
                        if (match[0] == -1) match[0] = up;
                        if (match[1] == -1) match[1] = down;
                    }
                    refined[count] = match[2];
                }
            }
            classes = refined;
            if (nextClass == classCount) break;
            classCount = nextClass;
        }
        BigInteger[] outputs = new BigInteger[classCount];
        int[] plus = new int[classCount], minus = new int[classCount];
        Arrays.fill(plus, -1); Arrays.fill(minus, -1);
        for (int count = 0; count < n; count++) {
            meter.charge();
            int state = classes[count]; outputs[state] = observations[count];
            if (count + 1 < n) plus[state] = classes[count + 1];
            if (source.allowDeletes && count > 0) minus[state] = classes[count - 1];
        }
        Plan plan = new Plan(classes, outputs, plus, minus);
        long synthesisWork = meter.work;
        Verification verification = verify(source, plan, meter);
        if (!verification.accepted) throw new AssertionError("derived counter plan failed its checker: " + verification.detail);
        return new Derivation(plan, synthesisWork, verification);
    }

    private static boolean compatible(int left, int right) { return left == -1 || right == -1 || left == right; }

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
