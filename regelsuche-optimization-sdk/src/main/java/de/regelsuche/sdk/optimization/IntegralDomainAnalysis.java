/* Copyright (c) 2026 Carsten Hammer. SPDX-License-Identifier: EPL-2.0
 * Affine range calculation moved from Sandbox's IntegralMathematicalDomain.
 */
package de.regelsuche.sdk.optimization;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.JointComputationPlan;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Exact local no-overflow domains for affine primitive integral computations of
 * one input. All nested operations, casts, declared outputs and original trace
 * occurrences (including dead operations) constrain the domain. Division is
 * admitted only when exact for every integer input in the affine expression.
 *
 * <p>This is domain analysis, not a Java-equivalence proof outside that domain
 * and not a source of caller assumptions. A source adapter must retain the real
 * evaluation trace and explicitly authorize any change to overflow behavior.
 * Nonlinear, bitwise, floating-point and BigInteger computations return an
 * explicit unsupported result. Ranges do not erase the operations' Java kinds.
 */
public final class IntegralDomainAnalysis {
    public static final String REVISION = "integral-affine-domain/v1";
    private static final long DEFAULT_MAXIMUM_WORK = 100_000;
    private static final int MAX_COEFFICIENT_BITS = 4096;

    private IntegralDomainAnalysis() { }

    public sealed interface Result permits Domain, Unsupported { }
    public sealed interface Comparison permits Verified, Unsupported { }

    public record Range(BigInteger minimum, BigInteger maximum) {
        public Range {
            Objects.requireNonNull(minimum); Objects.requireNonNull(maximum);
            if (minimum.compareTo(maximum) > 0) throw unsupported("EMPTY_INTEGRAL_DOMAIN");
        }
        public boolean includes(Range other) {
            return minimum.compareTo(other.minimum) <= 0 && maximum.compareTo(other.maximum) >= 0;
        }
        @Override public String toString() { return "[" + minimum + ", " + maximum + "]"; }
    }

    /** The exact mathematical output is coefficient * input + constant. */
    public record AffineOutput(String name, NumericKind kind, BigInteger coefficient, BigInteger constant) { }

    public record Domain(String input, NumericKind inputKind, Range inputRange,
            List<AffineOutput> outputs) implements Result {
        public Domain { outputs = List.copyOf(outputs); }
    }

    /** Equal named outputs, with the candidate defined throughout the original domain. */
    public record Verified(Domain source, Domain candidate) implements Comparison { }
    public record Unsupported(String diagnostic) implements Result, Comparison { }

    public static Result analyze(JointComputationPlan plan, SourceEvaluationTrace trace) {
        return analyze(plan, trace, DEFAULT_MAXIMUM_WORK, CancellationToken.NONE);
    }

    public static Result analyze(JointComputationPlan plan, SourceEvaluationTrace trace, CancellationToken cancellation) {
        return analyze(plan, trace, DEFAULT_MAXIMUM_WORK, cancellation);
    }

    /** Deterministic work and structural/coefficient bounds apply even without a cancellation token.
     * Graph resolution charges the same allowance before each traversal and interning step.
     */
    public static Result analyze(JointComputationPlan plan, SourceEvaluationTrace trace,
            long maximumWork, CancellationToken cancellation) {
        var work = new Work(maximumWork, cancellation);
        try {
            return new Analysis(plan, trace, work).domain();
        } catch (IllegalArgumentException | ArithmeticException failure) {
            return new Unsupported(failure.getMessage());
        }
    }

    public static Comparison compare(JointComputationPlan source, SourceEvaluationTrace sourceTrace,
            JointComputationPlan candidate) {
        return compare(source, sourceTrace, candidate, DEFAULT_MAXIMUM_WORK, CancellationToken.NONE);
    }

    public static Comparison compare(JointComputationPlan source, SourceEvaluationTrace sourceTrace,
            JointComputationPlan candidate, CancellationToken cancellation) {
        return compare(source, sourceTrace, candidate, DEFAULT_MAXIMUM_WORK, cancellation);
    }

    /** Both analyses share the supplied work budget; candidate intermediates are derived from its graph. */
    public static Comparison compare(JointComputationPlan source, SourceEvaluationTrace sourceTrace,
            JointComputationPlan candidate, long maximumWork, CancellationToken cancellation) {
        var work = new Work(maximumWork, cancellation);
        try {
            work.charge(1);
            if (!source.inputs().equals(candidate.inputs())) throw unsupported("DOMAIN_INPUTS_DIFFER");
            Domain before = new Analysis(source, sourceTrace, work).domain();
            Domain after = new Analysis(candidate, new SourceEvaluationTrace(List.of()), work).domain();
            if (!before.outputs().equals(after.outputs())) throw unsupported("AFFINE_OUTPUTS_DIFFER");
            if (!after.inputRange().includes(before.inputRange())) throw unsupported("CANDIDATE_DOMAIN_NARROWER");
            return new Verified(before, after);
        } catch (IllegalArgumentException | ArithmeticException failure) {
            return new Unsupported(failure.getMessage());
        }
    }

    private record Affine(BigInteger coefficient, BigInteger constant) {
        Affine {
            if (coefficient.abs().bitLength() > MAX_COEFFICIENT_BITS || constant.abs().bitLength() > MAX_COEFFICIENT_BITS)
                throw unsupported("DOMAIN_COEFFICIENT_BOUND");
        }
        Affine add(Affine other) { return new Affine(coefficient.add(other.coefficient), constant.add(other.constant)); }
        Affine scale(BigInteger factor) { return new Affine(coefficient.multiply(factor), constant.multiply(factor)); }
    }

    private record Cached(Affine value, int height) { }

    private static final class Analysis {
        private final JointComputationPlan plan;
        private final String input;
        private final NumericKind inputKind;
        private final Work work;
        private final JavaNumericBackend backend;
        private final Map<Expr, Cached> expressions = new IdentityHashMap<>();
        private final List<AffineOutput> outputs = new ArrayList<>();
        private Range range;

        Analysis(JointComputationPlan plan, SourceEvaluationTrace trace, Work work) {
            this.plan = Objects.requireNonNull(plan);
            this.work = work;
            work.charge(1);
            if (plan.inputs().size() != 1) throw unsupported("ONE_INTEGRAL_INPUT_REQUIRED");
            if (trace.occurrences().size() > JointComputationPlan.MAX_NODES) throw unsupported("DOMAIN_TRACE_BOUND");
            input = plan.inputs().keySet().iterator().next();
            inputKind = NumericKind.fromType(plan.inputs().get(input));
            range = limits(inputKind);
            backend = new JavaNumericBackend(plan.inputs());
            var resolved = plan.resolveOutputs(work::charge);
            if (resolved.expandedNodes() > JointComputationPlan.MAX_NODES) throw unsupported("DOMAIN_GRAPH_BOUND");
            for (var occurrence : trace.occurrences()) {
                work.charge(1);
                if (JavaExpressions.kindOf(occurrence.expression(), plan.inputs()) != occurrence.evaluatedKind())
                    throw unsupported("DOMAIN_TRACE_KIND_DIFFERS");
                Affine value = value(occurrence.expression(), 0);
                constrain(value, limits(occurrence.evaluatedKind()));
                constrain(value, limits(occurrence.declaredKind()));
            }
            for (int index = 0; index < plan.outputs().size(); index++) {
                var output = plan.outputs().get(index);
                var expression = resolved.expressions().get(index);
                NumericKind kind = NumericKind.fromType(output.type());
                if (JavaExpressions.kindOf(expression, plan.inputs()) != kind) throw unsupported("DOMAIN_OUTPUT_KIND_DIFFERS");
                Affine affine = value(expression, 0);
                constrain(affine, limits(kind));
                outputs.add(new AffineOutput(output.name(), kind, affine.coefficient, affine.constant));
            }
        }

        Domain domain() { return new Domain(input, inputKind, range, outputs); }

        private Affine value(Expr expression, int depth) {
            work.charge(1);
            if (depth > JointComputationPlan.MAX_DEPTH) throw unsupported("DOMAIN_DEPTH_BOUND");
            Cached cached = expressions.get(expression);
            if (cached != null) {
                if (depth + cached.height > JointComputationPlan.MAX_DEPTH) throw unsupported("DOMAIN_DEPTH_BOUND");
                return cached.value;
            }
            NumericKind kind = JavaExpressions.kindOf(expression, plan.inputs());
            Range resultLimits = limits(kind);
            Affine result;
            int height;
            if (expression instanceof VariableExpr variable) {
                if (!variable.name().equals(input)) throw unsupported("UNBOUND_DOMAIN_INPUT");
                result = new Affine(BigInteger.ONE, BigInteger.ZERO);
                height = 0;
            } else if (JavaExpressions.isLiteral(expression)) {
                Object literal = JavaExpressions.literalValue(expression);
                result = new Affine(BigInteger.ZERO,
                        BigInteger.valueOf(literal instanceof Character c ? c : ((Number) literal).longValue()));
                height = 1; // The typed literal wraps its exact integer payload.
            } else {
                var operands = JavaExpressions.operands(expression);
                var signature = backend.operation(expression);
                if (operands.size() != signature.arguments().size()) throw unsupported("DOMAIN_OPERATOR_ARITY");
                for (int index = 0; index < operands.size(); index++) {
                    if (!JavaExpressions.kindOf(operands.get(index), plan.inputs()).type().equals(signature.arguments().get(index)))
                        throw unsupported("DOMAIN_OPERAND_KIND_DIFFERS");
                }
                Affine left = value(operands.getFirst(), depth + 1);
                if (JavaExpressions.castSourceKind(expression).isPresent()) {
                    limits(JavaExpressions.castSourceKind(expression).orElseThrow());
                    result = left;
                } else {
                    result = switch (JavaExpressions.operationOf(expression).orElseThrow()) {
                        case ADD, ADD_EXACT -> left.add(value(operands.get(1), depth + 1));
                        case SUBTRACT, SUBTRACT_EXACT -> left.add(value(operands.get(1), depth + 1).scale(BigInteger.ONE.negate()));
                        case NEGATE, NEGATE_EXACT -> left.scale(BigInteger.ONE.negate());
                        case MULTIPLY, MULTIPLY_EXACT -> {
                            Affine right = value(operands.get(1), depth + 1);
                            if (left.coefficient.signum() == 0) yield right.scale(left.constant);
                            if (right.coefficient.signum() == 0) yield left.scale(right.constant);
                            throw unsupported("NON_AFFINE_PRODUCT");
                        }
                        case DIVIDE -> {
                            Affine right = value(operands.get(1), depth + 1);
                            if (right.coefficient.signum() != 0 || right.constant.signum() == 0
                                    || left.coefficient.remainder(right.constant).signum() != 0
                                    || left.constant.remainder(right.constant).signum() != 0)
                                throw unsupported("NON_EXACT_AFFINE_DIVISION");
                            yield new Affine(left.coefficient.divide(right.constant), left.constant.divide(right.constant));
                        }
                        default -> throw unsupported("UNSUPPORTED_DOMAIN_OPERATION");
                    };
                }
                height = 1 + operands.stream().mapToInt(operand -> expressions.get(operand).height).max().orElse(0);
            }
            if (depth + height > JointComputationPlan.MAX_DEPTH) throw unsupported("DOMAIN_DEPTH_BOUND");
            constrain(result, resultLimits);
            expressions.put(expression, new Cached(result, height));
            return result;
        }

        private void constrain(Affine affine, Range limits) {
            work.charge(1);
            BigInteger a = affine.coefficient, b = affine.constant;
            if (a.signum() == 0) {
                if (b.compareTo(limits.minimum) < 0 || b.compareTo(limits.maximum) > 0)
                    throw unsupported("EMPTY_INTEGRAL_DOMAIN");
                return;
            }
            BigInteger low = limits.minimum.subtract(b), high = limits.maximum.subtract(b);
            if (a.signum() < 0) {
                BigInteger previousLow = low;
                low = high.negate(); high = previousLow.negate(); a = a.negate();
            }
            range = new Range(range.minimum.max(floor(low.negate(), a).negate()), range.maximum.min(floor(high, a)));
        }
    }

    private static BigInteger floor(BigInteger numerator, BigInteger positiveDenominator) {
        BigInteger[] parts = numerator.divideAndRemainder(positiveDenominator);
        return parts[1].signum() < 0 ? parts[0].subtract(BigInteger.ONE) : parts[0];
    }

    private static Range limits(NumericKind kind) {
        if (!kind.integral()) throw unsupported("PRIMITIVE_INTEGRAL_DOMAIN_REQUIRED");
        return kind == NumericKind.CHAR ? new Range(BigInteger.ZERO, BigInteger.valueOf(65535))
                : new Range(BigInteger.ONE.shiftLeft(kind.bits() - 1).negate(),
                        BigInteger.ONE.shiftLeft(kind.bits() - 1).subtract(BigInteger.ONE));
    }

    private static final class Work {
        private final long maximum;
        private final CancellationToken cancellation;
        private long used;
        Work(long maximum, CancellationToken cancellation) {
            if (maximum < 1) throw unsupported("INVALID_DOMAIN_WORK_BOUND");
            this.maximum = maximum;
            this.cancellation = Objects.requireNonNull(cancellation);
        }
        void charge(long amount) {
            if (cancellation.isCancelled() || Thread.currentThread().isInterrupted()) throw unsupported("CANCELLED");
            if (amount < 0 || amount > maximum - used) throw unsupported("DOMAIN_WORK_BOUND");
            used += amount;
        }
    }

    private static IllegalArgumentException unsupported(String diagnostic) { return new IllegalArgumentException(diagnostic); }
}
