package de.regelsuche.sdk.optimization;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.PreparedJointComputation;
import java.util.*;

/** Static word-work estimates, not timings or arithmetic proof. Unknown magnitudes stay unknown. */
public final class NumericExecutionCost {
    private NumericExecutionCost() { }
    public record Estimate(long operationWork, long peakWords, long inspectionWork) { }

    public static Optional<Estimate> estimate(OptimizationRequest request, PreparedJointComputation prepared) {
        return estimate(request, prepared, false);
    }
    /** Original occurrences are charged separately even when their value graph shares a node. */
    public static Optional<Estimate> estimateSource(OptimizationRequest request, PreparedJointComputation prepared) {
        return estimate(request, prepared, true);
    }
    private static Optional<Estimate> estimate(OptimizationRequest request, PreparedJointComputation prepared, boolean original) {
        Objects.requireNonNull(request); Objects.requireNonNull(prepared);
        var nodes = prepared.nodes();
        long[] bits = new long[nodes.size()], storage = new long[nodes.size()];
        int[] last = new int[nodes.size()];
        Map<Expr, Long> costs = new HashMap<>();
        long total = 0, live = 0, peak = 0, inspected = 1;
        try {
            for (int i = 0; i < nodes.size(); i++) {
                var node = nodes.get(i); last[i] = i;
                for (int argument : node.arguments()) last[argument] = i;
                inspected += 1L + node.arguments().size();
            }
            for (int index : prepared.outputBindings().values()) last[index] = nodes.size();
            for (int i = 0; i < nodes.size(); i++) {
                var node = nodes.get(i); Expr expression = node.expression();
                NumericKind kind = NumericKind.fromType(node.type());
                long cost = node.operation() == null ? 0 : node.operation().work();
                if (kind == NumericKind.BIG_INTEGER) {
                    bits[i] = magnitude(request, expression, node.arguments().stream().mapToLong(j -> bits[j]).toArray());
                    if (bits[i] < 0 || bits[i] > BigIntegerBounds.MAX_BITS) return Optional.empty();
                    if (node.operation() != null && !JavaExpressions.isLiteral(expression)) {
                        cost = bigIntegerOperationWork(node, bits, i);
                    }
                    storage[i] = 2 + words(bits[i]);
                } else { bits[i] = kind.bits(); storage[i] = node.storage(); }
                total = Math.addExact(total, cost); costs.put(expression, cost);
                live += storage[i]; peak = Math.max(peak, live);
                for (int argument : new HashSet<>(node.arguments())) if (last[argument] == i) live -= storage[argument];
                if (last[i] == i) live -= storage[i];
                inspected += 2L + node.arguments().size();
            }
            if (original) {
                long traced = 0;
                for (var occurrence : request.sourceTrace().occurrences()) {
                    inspected++;
                    Long cost = costs.get(occurrence.expression());
                    // A dead operation may not be in the output DAG. Keep the existing source estimate
                    // rather than invent its magnitude or pretend it did not execute.
                    if (cost == null) return Optional.empty();
                    traced = Math.addExact(traced, cost);
                }
                total = Math.max(total, traced);
            }
            return Optional.of(new Estimate(total, peak, inspected));
        } catch (ArithmeticException | IllegalArgumentException unsupported) { return Optional.empty(); }
    }
    /** Word-work for one already bounded BigInteger node; no liveness or occurrence accounting. */
    private static long bigIntegerOperationWork(PreparedJointComputation.Node node, long[] bits, int index) {
        long a = node.arguments().isEmpty() ? 1 : words(bits[node.arguments().getFirst()]);
        long b = node.arguments().size() < 2 ? 1 : words(bits[node.arguments().get(1)]);
        long modulus = node.arguments().size() < 3 ? b : words(bits[node.arguments().get(2)]);
        return 4 * switch (JavaExpressions.operationOf(node.expression()).orElseThrow()) {
            case MULTIPLY -> a * b;
            case DIVIDE, REMAINDER, MOD -> a * b + a;
            case MOD_MULTIPLY -> a * b + words(bits[node.arguments().getFirst()] + bits[node.arguments().get(1)]) * modulus;
            case MOD_POW -> 32 + 2 * Math.max(1, bits[node.arguments().get(1)]) * modulus * modulus;
            case MOD_INVERSE -> 32 + Math.max(a,b) * Math.max(a,b) * 2;
            case POW -> Math.max(1, bits[index]) * Math.max(a, words(bits[index]));
            default -> Math.max(words(bits[index]), Math.max(a, b));
        };
    }
    private static long words(long bits) { return Math.max(1, (bits + 31) / 32); }
    private static long magnitude(OptimizationRequest request, Expr expression, long[] args) {
        if (JavaExpressions.isLiteral(expression)) return SemanticChecker.integer(JavaExpressions.literalValue(expression)).abs().bitLength();
        if (expression instanceof VariableExpr variable) return request.assumptions().stream()
                .filter(a -> a.kind() == SemanticAssumption.Kind.BIG_INTEGER_BIT_LENGTH_BOUND && a.subject().equals(variable.name()))
                .mapToLong(a -> BigIntegerBounds.parameter(a.parameter())).min().orElse(-1);
        if (args.length == 0 || Arrays.stream(args).anyMatch(n -> n < 0)) return -1;
        long a = args[0], b = args.length < 2 ? 0 : args[1];
        var operands = JavaExpressions.operands(expression);
        return switch (JavaExpressions.operationOf(expression).orElseThrow()) {
            case NEGATE, ABS, DIVIDE -> a;
            case NOT -> a + 1;
            case ADD, SUBTRACT, AND, OR, XOR -> Math.max(a, b) + 1;
            case MULTIPLY -> a + b;
            case REMAINDER -> Math.min(a, b);
            case MOD, MOD_INVERSE -> b;
            case MOD_MULTIPLY, MOD_POW -> args[2];
            case POW -> JavaExpressions.isLiteral(operands.get(1))
                    ? Math.multiplyExact(a, Math.max(1, SemanticChecker.integer(JavaExpressions.literalValue(operands.get(1))).longValueExact())) : -1;
            case SHIFT_LEFT, SHIFT_RIGHT -> {
                if (!JavaExpressions.isLiteral(operands.get(1))) yield -1;
                long distance = SemanticChecker.integer(JavaExpressions.literalValue(operands.get(1))).longValueExact();
                yield a + Math.max(0, JavaExpressions.operationOf(expression).orElseThrow() == NumericOperation.SHIFT_LEFT ? distance : -distance);
            }
            default -> -1;
        };
    }
}
