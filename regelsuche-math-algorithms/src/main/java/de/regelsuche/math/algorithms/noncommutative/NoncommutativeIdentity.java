package de.regelsuche.math.algorithms.noncommutative;

import de.regelsuche.math.algorithms.noncommutative.NoncommutativeFormula.*;
import de.regelsuche.scalar.ExactRational;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Experimental deterministic identity test in the free associative algebra over Q (family 116). */
public final class NoncommutativeIdentity {
    private NoncommutativeIdentity() {}

    /** Expanded +, * tree size charges three gates for every subtraction, including the comparison root. */
    public record Limits(int maxVariables, int maxTreeSize, int maxDepth, int maxDimension,
                         int maxBits, long maxWork) {
        public Limits {
            if (maxVariables < 0 || maxTreeSize < 0 || maxDepth < 0 || maxDepth > 64
                    || maxDimension < 0 || maxBits < 1 || maxWork < 0) {
                throw new IllegalArgumentException("Nonnegative budgets, positive bit limit and AST depth at most 64 required");
            }
        }

        public static Limits defaults() {
            return new Limits(32, 1023, 64, 4096, 8192, 200_000);
        }
    }

    public enum Status { IDENTICAL, DISTINCT, BUDGET_EXCEEDED, CANCELLED, UNSUPPORTED }

    public sealed interface Result {
        Status status();
    }

    /** In-process evidence from a completed evaluation, bound to its immutable formula and ordered generators. */
    public static final class Completed implements Result {
        private final Status status;
        private final NoncommutativeFormula formula;
        private final List<Integer> variableOrder;
        private final int treeSize;
        private final List<ExactRational> firstColumn;

        private Completed(Status status, NoncommutativeFormula formula, List<Integer> order, int size,
                          ExactRational[] column) {
            this.status = status;
            this.formula = formula;
            variableOrder = List.copyOf(order);
            treeSize = size;
            firstColumn = List.of(column);
        }

        public Status status() { return status; }
        public NoncommutativeFormula formula() { return formula; }
        public List<Integer> variableOrder() { return variableOrder; }
        public int treeSize() { return treeSize; }
        public List<ExactRational> firstColumn() { return firstColumn; }
    }

    /** No identity verdict or partial column is available for a stopped computation. */
    public record Stopped(Status status, String reason) implements Result {
        public Stopped {
            if (status != Status.BUDGET_EXCEEDED && status != Status.CANCELLED && status != Status.UNSUPPORTED) {
                throw new IllegalArgumentException("Stopped status required");
            }
            Objects.requireNonNull(reason);
        }
    }

    public static Result compare(NoncommutativeFormula left, NoncommutativeFormula right, List<Integer> variableOrder,
                                 Limits limits, BooleanSupplier cancelled) {
        return checkZero(NoncommutativeFormula.subtract(left, right), variableOrder, limits, cancelled);
    }

    public static Result checkZero(NoncommutativeFormula formula, List<Integer> variableOrder,
                                   Limits limits, BooleanSupplier cancelled) {
        Objects.requireNonNull(formula);
        Objects.requireNonNull(variableOrder);
        var run = new Run(Objects.requireNonNull(limits), Objects.requireNonNull(cancelled));
        try {
            return run.check(formula, variableOrder);
        } catch (Stop stop) {
            return new Stopped(stop.status, stop.getMessage());
        }
    }

    private static final class Stop extends RuntimeException {
        final Status status;

        Stop(Status status, String reason) {
            super(reason, null, false, false);
            this.status = status;
        }
    }

    private static final class Run {
        final Limits limits;
        final BooleanSupplier cancelled;
        final Map<Integer, Integer> indices = new HashMap<>();
        final Map<NoncommutativeFormula, Shape> shapes = new IdentityHashMap<>();
        int treeSize;
        boolean hasVariable;

        Run(Limits limits, BooleanSupplier cancelled) {
            this.limits = limits;
            this.cancelled = cancelled;
        }

        Completed check(NoncommutativeFormula formula, List<Integer> order) {
            poll();
            requireBudget(order.size() <= limits.maxVariables(), "variable count");
            var snapshot = new ArrayList<Integer>();
            for (Integer id : order) {
                poll();
                requireBudget(snapshot.size() < limits.maxVariables(), "variable count");
                if (id == null || indices.put(id, snapshot.size() + 1) != null) {
                    throw new Stop(Status.UNSUPPORTED, "duplicate or null generator");
                }
                snapshot.add(id);
            }
            inspect(formula, 1);
            int d = dimension(snapshot.size());
            requireBudget((long) treeSize * d <= limits.maxWork(), "formula size times dimension");
            poll();
            var basis = new ExactRational[d];
            Arrays.fill(basis, ExactRational.ZERO);
            basis[0] = ExactRational.ONE;
            var column = apply(formula, basis);
            Status status = Status.IDENTICAL;
            for (var entry : column) {
                poll();
                if (!entry.isZero()) {
                    status = Status.DISTINCT;
                    break;
                }
            }
            var result = new Completed(status, formula, snapshot, treeSize, column);
            poll();
            return result;
        }

        private int dimension(int n) {
            if (!hasVariable || n == 1 && treeSize == 1) {
                requireBudget(limits.maxDimension() >= 1, "dimension");
                return 1;
            }
            long twiceN = 2L * n;
            // Check against the allocation limit before multiplying the full dimension.
            requireBudget(treeSize <= limits.maxDimension() / twiceN / treeSize, "dimension");
            return (int) (twiceN * treeSize * treeSize);
        }

        private record Shape(int size, int height) {}

        private int inspect(NoncommutativeFormula formula, int depth) {
            poll();
            requireBudget(depth <= limits.maxDepth(), "AST depth");
            var known = shapes.get(formula);
            if (known != null) {
                requireBudget(known.height() <= limits.maxDepth() - depth + 1, "AST depth");
                charge(known.size());
                return known.height();
            }
            int before = treeSize;
            charge(formula instanceof Binary b && b.op() == Op.SUBTRACT ? 3 : 1);
            int height = switch (formula) {
                case Scalar(var value) -> {
                    checked(value);
                    yield 1;
                }
                case Variable(var id) -> {
                    if (!indices.containsKey(id)) throw new Stop(Status.UNSUPPORTED, "undeclared generator");
                    hasVariable = true;
                    yield 1;
                }
                case Binary(var op, var left, var right) ->
                    1 + Math.max(inspect(left, depth + 1), inspect(right, depth + 1));
            };
            shapes.put(formula, new Shape(treeSize - before, height));
            return height;
        }

        private void charge(int count) {
            requireBudget(count <= limits.maxTreeSize() - treeSize, "expanded formula size");
            requireBudget(count <= limits.maxWork() - treeSize, "formula size times dimension");
            treeSize += count;
        }

        private ExactRational[] apply(NoncommutativeFormula formula, ExactRational[] input) {
            poll();
            return switch (formula) {
                case Variable(var id) -> applyGenerator(indices.get(id), input);
                case Scalar(var value) -> {
                    var output = new ExactRational[input.length];
                    for (int r = 0; r < output.length; r++) {
                        poll();
                        output[r] = checked(input[r].multiply(value));
                    }
                    yield output;
                }
                case Binary(var op, var left, var right) -> {
                    if (op == Op.MULTIPLY) yield apply(left, apply(right, input));
                    var a = apply(left, input);
                    var b = apply(right, input);
                    for (int r = 0; r < a.length; r++) {
                        poll();
                        a[r] = checked(op == Op.ADD ? a[r].add(b[r]) : a[r].subtract(b[r]));
                    }
                    yield a;
                }
            };
        }

        private ExactRational[] applyGenerator(int i, ExactRational[] input) {
            if (input.length == 1) return input.clone(); // n=s=1 uses T_1=[1]
            var output = new ExactRational[input.length];
            output[0] = ExactRational.ZERO;
            for (int r = 1; r < output.length; r++) {
                poll();
                var factor = checked(ExactRational.integer(r - 1));
                var divisor = checked(ExactRational.integer((long) r * i));
                var previous = checked(output[r - 1].multiply(factor));
                var difference = checked(input[r - 1].subtract(previous));
                output[r] = checked(difference.divide(divisor));
            }
            return output;
        }

        private ExactRational checked(ExactRational value) {
            requireBudget(value.numerator().bitLength() <= limits.maxBits()
                && value.denominator().bitLength() <= limits.maxBits()
                && value.numerator().abs().bitLength() <= limits.maxBits(), "rational bits");
            return value;
        }

        private void poll() {
            if (cancelled.getAsBoolean()) throw new Stop(Status.CANCELLED, "cancelled");
        }

        private static void requireBudget(boolean condition, String limit) {
            if (!condition) throw new Stop(Status.BUDGET_EXCEEDED, limit);
        }
    }
}
