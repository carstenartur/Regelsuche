package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.scalar.ExactRational;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Exact coefficient arithmetic for the matcher's bounded monomial fragment.
 *
 * <p>The temporary input bridge interprets finite legacy numeric leaves under
 * their shortest-decimal convention. It does not recover source precision lost
 * before matching. Symbolic divisors are outside this assumption-free fragment.
 * Inferred rational coefficients are emitted as integer/fraction syntax only
 * when every numeric leaf round-trips without changing its decimal value.</p>
 */
record BoundedExactMonomial(ExactRational coefficient, Map<String, Integer> powers) {
    BoundedExactMonomial {
        powers = coefficient.isZero() ? Map.of()
            : Collections.unmodifiableMap(new TreeMap<>(powers));
    }

    static Optional<BoundedExactMonomial> from(Expr expression, Budget budget) {
        return from(expression, budget, 0);
    }

    private static Optional<BoundedExactMonomial> from(
        Expr expression, Budget budget, int depth
    ) {
        budget.visit(depth);
        if (expression instanceof NumberExpr number) {
            return Double.isFinite(number.value())
                ? Optional.of(new BoundedExactMonomial(decimalValue(number.value()), Map.of()))
                : Optional.empty();
        }
        if (expression instanceof VariableExpr variable) {
            return Optional.of(new BoundedExactMonomial(
                ExactRational.ONE, Map.of(variable.name(), 1)));
        }
        if (!(expression instanceof BinaryExpr binary)) {
            return Optional.empty();
        }
        return switch (binary.operator()) {
            case MUL, DIV -> product(binary, budget, depth);
            case POW -> power(binary, budget, depth);
            default -> Optional.empty();
        };
    }

    private static Optional<BoundedExactMonomial> product(
        BinaryExpr binary, Budget budget, int depth
    ) {
        var left = from(binary.left(), budget, depth + 1);
        var right = from(binary.right(), budget, depth + 1);
        if (left.isEmpty() || right.isEmpty()) {
            return Optional.empty();
        }
        boolean divide = binary.operator() == BinaryOperator.DIV;
        // Even x/x and 0/x retain an undefined point. There is no assumption
        // context here that could authorize cancelling a symbolic denominator.
        if (divide && (right.get().coefficient.isZero() || !right.get().powers.isEmpty())) {
            return Optional.empty();
        }
        return Optional.of(left.get().combine(right.get(), divide, budget));
    }

    private static Optional<BoundedExactMonomial> power(
        BinaryExpr binary, Budget budget, int depth
    ) {
        if (!(binary.right() instanceof NumberExpr number)) {
            return Optional.empty();
        }
        int exponent = positiveInteger(number.value());
        if (exponent < 1) {
            return Optional.empty();
        }
        return from(binary.left(), budget, depth + 1)
            .map(base -> base.pow(exponent, budget));
    }

    private BoundedExactMonomial combine(
        BoundedExactMonomial other, boolean divide, Budget budget
    ) {
        ExactRational right = divide ? other.coefficient.reciprocal() : other.coefficient;
        budget.coefficientBits(
            (long) bits(coefficient.numerator()) + bits(right.numerator()),
            (long) bits(coefficient.denominator()) + bits(right.denominator()));
        Map<String, Integer> result = new TreeMap<>(powers);
        if (!divide) {
            other.powers.forEach((name, exponent) -> result.merge(name, exponent,
                (first, second) -> checkedExponent((long) first + second)));
        }
        return new BoundedExactMonomial(coefficient.multiply(right), result);
    }

    private BoundedExactMonomial pow(int exponent, Budget budget) {
        budget.coefficientBits(powerBits(coefficient.numerator(), exponent),
            powerBits(coefficient.denominator(), exponent));
        Map<String, Integer> result = new TreeMap<>();
        powers.forEach((name, power) ->
            result.put(name, checkedExponent((long) power * exponent)));
        return new BoundedExactMonomial(coefficient.pow(exponent), result);
    }

    Optional<BoundedExactMonomial> exactRoot(int exponent, Budget budget) {
        if (coefficient.signum() < 0 && exponent % 2 == 0) {
            return Optional.empty();
        }
        Map<String, Integer> result = new TreeMap<>();
        for (var entry : powers.entrySet()) {
            if (entry.getValue() % exponent != 0) {
                return Optional.empty();
            }
            result.put(entry.getKey(), entry.getValue() / exponent);
        }
        var numerator = integerRoot(coefficient.numerator().abs(), exponent, budget);
        var denominator = integerRoot(coefficient.denominator(), exponent, budget);
        if (numerator.isEmpty() || denominator.isEmpty()) {
            return Optional.empty();
        }
        BigInteger signed = coefficient.signum() < 0 ? numerator.get().negate() : numerator.get();
        return Optional.of(new BoundedExactMonomial(
            new ExactRational(signed, denominator.get()), result));
    }

    /** No floating-point proposal can authorize a root. */
    private static Optional<BigInteger> integerRoot(BigInteger value, int exponent, Budget budget) {
        budget.visit(0);
        if (exponent == 1 || value.compareTo(BigInteger.ONE) <= 0) {
            return Optional.of(value);
        }
        if (exponent > value.bitLength()) {
            return Optional.empty();
        }
        BigInteger low = BigInteger.ZERO;
        BigInteger high = BigInteger.ONE.shiftLeft((value.bitLength() + exponent - 1) / exponent);
        while (high.subtract(low).compareTo(BigInteger.ONE) > 0) {
            budget.visit(0);
            BigInteger middle = low.add(high).shiftRight(1);
            int comparison = middle.pow(exponent).compareTo(value);
            if (comparison == 0) {
                return Optional.of(middle);
            }
            if (comparison < 0) {
                low = middle;
            } else {
                high = middle;
            }
        }
        return low.pow(exponent).equals(value) ? Optional.of(low) : Optional.empty();
    }

    boolean equivalentTo(BoundedExactMonomial other) {
        return coefficient.equals(other.coefficient) && powers.equals(other.powers);
    }

    boolean isConstant(double expected) {
        return Double.isFinite(expected) && powers.isEmpty()
            && coefficient.equals(decimalValue(expected));
    }

    Expr toExpr() {
        Expr result = null;
        if (!coefficient.isOne() || powers.isEmpty()) {
            result = integerLeaf(coefficient.numerator());
            if (!coefficient.isInteger()) {
                result = new BinaryExpr(result, BinaryOperator.DIV,
                    integerLeaf(coefficient.denominator()));
            }
        }
        for (var entry : powers.entrySet()) {
            Expr factor = new VariableExpr(entry.getKey());
            if (entry.getValue() != 1) {
                factor = new BinaryExpr(factor, BinaryOperator.POW, new NumberExpr(entry.getValue()));
            }
            result = result == null ? factor : new BinaryExpr(result, BinaryOperator.MUL, factor);
        }
        return result == null ? new NumberExpr(1) : result;
    }

    private static NumberExpr integerLeaf(BigInteger value) {
        double legacy = value.doubleValue();
        if (!Double.isFinite(legacy)
                || !BigDecimal.valueOf(legacy).toBigIntegerExact().equals(value)) {
            throw new LimitExceeded("ALGEBRAIC_BINDING_NOT_REPRESENTABLE");
        }
        return new NumberExpr(legacy);
    }

    private static ExactRational decimalValue(double value) {
        BigDecimal decimal = BigDecimal.valueOf(value);
        BigInteger unscaled = decimal.unscaledValue();
        return decimal.scale() < 0
            ? ExactRational.integer(unscaled.multiply(BigInteger.TEN.pow(-decimal.scale())))
            : new ExactRational(unscaled, BigInteger.TEN.pow(decimal.scale()));
    }

    static int positiveInteger(double value) {
        return value > 0 && value <= Integer.MAX_VALUE && value == Math.rint(value)
            ? (int) value : -1;
    }

    private static int checkedExponent(long value) {
        if (value > Integer.MAX_VALUE) {
            throw new LimitExceeded("ALGEBRAIC_EXPONENT_LIMIT");
        }
        return (int) value;
    }

    private static int bits(BigInteger value) {
        return value.abs().bitLength();
    }

    private static long powerBits(BigInteger value, int exponent) {
        return value.abs().compareTo(BigInteger.ONE) <= 0 ? 1L : (long) bits(value) * exponent;
    }

    /** Shared by every inference/pre-filter in one matcher invocation. */
    static final class Budget {
        private static final int MAX_VISITS = 10_000;
        private static final int MAX_DEPTH = 128;
        private static final int MAX_COEFFICIENT_BITS = 4_096;
        private int remaining = MAX_VISITS;

        private void visit(int depth) {
            if (depth > MAX_DEPTH || remaining-- <= 0) {
                throw new LimitExceeded("ALGEBRAIC_WORK_LIMIT");
            }
        }

        private void coefficientBits(long numerator, long denominator) {
            visit(0);
            if (numerator > MAX_COEFFICIENT_BITS || denominator > MAX_COEFFICIENT_BITS) {
                throw new LimitExceeded("ALGEBRAIC_COEFFICIENT_LIMIT");
            }
        }
    }

    static final class LimitExceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final String code;

        private LimitExceeded(String code) {
            super(code, null, false, false);
            this.code = code;
        }
    }
}
