package de.regelsuche.sdk.optimization;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

/** Conservative magnitude proof, independent of algebraic equivalence and candidate execution.
 * The supported fragment stays well below BigInteger's implementation range. Resource exhaustion
 * is outside the value contract, but its supported-range ArithmeticException is not.
 */
final class BigIntegerBounds {
    static final long MAX_BITS = 1_000_000;
    private final OptimizationRequest request;
    private final VerificationWork work;
    private final long maximum;
    private final Map<Expr, Long> cache = new HashMap<>();

    BigIntegerBounds(OptimizationRequest request, VerificationWork work) { this(request, work, MAX_BITS); }
    BigIntegerBounds(OptimizationRequest request, VerificationWork work, long maximum) {
        this.request = request; this.work = work; this.maximum = maximum;
    }
    void require(Expr expression) {
        if (JavaExpressions.kindOf(expression, request.plan().inputs()) == NumericKind.BIG_INTEGER) bits(expression);
    }
    private long bits(Expr expression) {
        work.charge(1);
        var known = cache.get(expression);
        if (known != null) return known;
        long bound;
        if (JavaExpressions.isLiteral(expression)) {
            bound = SemanticChecker.integer(JavaExpressions.literalValue(expression)).abs().bitLength();
        } else if (expression instanceof VariableExpr variable) {
            bound = assumption(SemanticAssumption.Kind.BIG_INTEGER_BIT_LENGTH_BOUND, variable.name());
        } else {
            var args = JavaExpressions.operands(expression);
            var operation = JavaExpressions.operationOf(expression).orElseThrow();
            long left = bits(args.getFirst());
            bound = switch (operation) {
                case NEGATE, ABS -> left;
                case NOT -> left + 1;
                case ADD, SUBTRACT, AND, OR, XOR -> Math.max(left, bits(args.get(1))) + 1;
                case MULTIPLY -> left + bits(args.get(1));
                case DIVIDE -> { bits(args.get(1)); yield left; }
                case REMAINDER -> Math.min(left, bits(args.get(1)));
                case MOD -> bits(args.get(1));
                case MOD_MULTIPLY -> { bounded(left + bits(args.get(1))); yield bits(args.get(2)); }
                case MOD_POW -> { bits(args.get(1)); yield bits(args.get(2)); }
                case POW -> {
                    long exponent = nonnegativeScalar(args.get(1));
                    yield exponent == 0 ? 1 : Math.multiplyExact(left, exponent);
                }
                case SHIFT_LEFT, SHIFT_RIGHT -> {
                    long distance = JavaExpressions.isLiteral(args.get(1))
                        ? SemanticChecker.integer(JavaExpressions.literalValue(args.get(1))).longValueExact()
                        : nonnegativeScalar(args.get(1));
                    yield left + (operation == NumericOperation.SHIFT_LEFT ? Math.max(0, distance) : Math.max(0, -distance));
                }
                default -> throw new IllegalArgumentException("BIG_INTEGER_MAGNITUDE_OPERATION_UNSUPPORTED");
            };
        }
        bounded(bound);
        cache.put(expression, bound);
        return bound;
    }
    private long nonnegativeScalar(Expr expression) {
        if (JavaExpressions.isLiteral(expression)) {
            var value = SemanticChecker.integer(JavaExpressions.literalValue(expression));
            if (value.signum() >= 0 && value.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) <= 0) return value.longValue();
            throw new IllegalArgumentException("NONNEGATIVE_INT_SCALAR_REQUIRED");
        }
        if (expression instanceof VariableExpr variable) return assumption(SemanticAssumption.Kind.NON_NEGATIVE_UPPER_BOUND, variable.name());
        throw new IllegalArgumentException("BOUNDED_SCALAR_VALUE_REQUIRED");
    }
    private long assumption(SemanticAssumption.Kind kind, String subject) {
        return request.assumptions().stream().filter(a -> a.kind() == kind && a.subject().equals(subject))
            .mapToLong(a -> parameter(a.parameter())).min().orElseThrow(() -> new IllegalArgumentException(
                kind == SemanticAssumption.Kind.BIG_INTEGER_BIT_LENGTH_BOUND ? "BIG_INTEGER_MAGNITUDE_BOUND_REQUIRED" : "BOUNDED_SCALAR_VALUE_REQUIRED"));
    }
    static long parameter(String parameter) {
        try {
            if (!parameter.matches("0|[1-9][0-9]{0,9}")) throw new NumberFormatException();
            long value = Long.parseLong(parameter);
            if (value > Integer.MAX_VALUE) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException invalid) { throw new IllegalArgumentException("INVALID_NUMERIC_BOUND_ASSUMPTION"); }
    }
    private void bounded(long bound) {
        if (bound < 0 || bound > maximum) throw new IllegalArgumentException("BIG_INTEGER_SUPPORTED_RANGE_NOT_PROVED");
    }
}
