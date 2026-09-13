package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.polynomial.PolynomialWorkAuthority;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Objects;

/**
 * Dense univariate polynomial with exact integer-valued coefficients.
 *
 * <p>The type exists so that a rewrite rule can decide <em>structurally</em>
 * whether one expression divides another. It deliberately refuses everything
 * it cannot represent exactly: a non-integer coefficient, a symbolic exponent,
 * a second variable or any function application makes {@link #of(Expr)} return
 * {@code null} rather than an approximation. No rewrite may be derived from a
 * rounded value.</p>
 */
final class UnivariatePolynomial {
    /** Highest exponent that may be expanded from a power node. */
    private static final int MAX_EXPONENT = 8;
    /** Highest degree accepted, keeping the rule a local, cheap decision. */
    private static final int MAX_DEGREE = 16;
    /** Finite coefficient work bound for this local polynomial algorithm; the AST itself is exact. */
    private static final BigInteger MAX_ABSOLUTE_COEFFICIENT =
        BigInteger.valueOf(1_000_000_000_000L);

    private final String variable;
    private final BigInteger[] coefficients;
    private final PreparationArithmetic arithmetic;

    private UnivariatePolynomial(
        String variable,
        BigInteger[] coefficients,
        PreparationArithmetic arithmetic
    ) {
        this.variable = variable;
        this.arithmetic = arithmetic;
        this.coefficients = trim(coefficients, arithmetic);
    }

    /**
     * @param expression the subtree to interpret
     * @return the polynomial, or {@code null} when the subtree is not an exact
     *     univariate integer-coefficient polynomial
     */
    static UnivariatePolynomial of(Expr expression) {
        return of(expression, PolynomialWorkAuthority.unbounded());
    }

    static UnivariatePolynomial of(Expr expression, PolynomialWorkAuthority authority) {
        return of(expression, new PreparationArithmetic(Objects.requireNonNull(authority, "authority")));
    }

    private static UnivariatePolynomial of(Expr expression, PreparationArithmetic arithmetic) {
        arithmetic.work.consume("polynomial.ast-node-visits", 1);
        if (expression == null) {
            return null;
        }
        if (expression instanceof NumberExpr number) {
            BigInteger value = exactInteger(number.value(), arithmetic);
            return value == null
                ? null
                : new UnivariatePolynomial("", coefficients(arithmetic, value), arithmetic);
        }
        if (expression instanceof VariableExpr variable) {
            return new UnivariatePolynomial(
                variable.name(),
                coefficients(arithmetic, BigInteger.ZERO, BigInteger.ONE), arithmetic);
        }
        if (!(expression instanceof BinaryExpr binary)) {
            return null;
        }
        UnivariatePolynomial left = of(binary.left(), arithmetic);
        if (left == null) {
            return null;
        }
        if (binary.operator() == BinaryOperator.POW) {
            return left.power(binary.right());
        }
        UnivariatePolynomial right = of(binary.right(), arithmetic);
        if (right == null) {
            return null;
        }
        return switch (binary.operator()) {
            case ADD -> left.combine(right, BigInteger.ONE);
            case SUB -> left.combine(right, arithmetic.negate(BigInteger.ONE));
            case MUL -> left.multiply(right);
            case DIV -> left.divideByConstant(right);
            default -> null;
        };
    }

    String variable() {
        return variable;
    }

    int degree() {
        return coefficients.length - 1;
    }

    boolean isConstant() {
        return degree() == 0;
    }

    boolean isZero() {
        return isConstant() && arithmetic.signum(coefficients[0]) == 0;
    }

    /**
     * Exact division without remainder.
     *
     * @param divisor the divisor polynomial
     * @return the quotient, or {@code null} when the division leaves a
     *     remainder, produces a non-integer coefficient, or is not defined
     */
    UnivariatePolynomial divideExactly(UnivariatePolynomial divisor) {
        Objects.requireNonNull(divisor, "divisor");
        if ((divisor.isConstant() && arithmetic.signum(divisor.coefficients[0]) == 0) || !sameVariable(divisor)
                || divisor.degree() > degree()) {
            return null;
        }
        arithmetic.work.consume("polynomial.array-copy-slots", coefficients.length);
        BigInteger[] remainder = coefficients.clone();
        BigInteger[] quotient = zeroCoefficients(
            degree() - divisor.degree() + 1, arithmetic);
        BigInteger leading = divisor.coefficients[divisor.degree()];
        for (int index = quotient.length - 1; index >= 0; index--) {
            BigInteger[] division = arithmetic.divide(remainder[index + divisor.degree()], leading);
            if (arithmetic.signum(division[1]) != 0
                    || !isAcceptedCoefficient(division[0], arithmetic)) {
                return null;
            }
            BigInteger factor = division[0];
            quotient[index] = factor;
            if (arithmetic.signum(factor) == 0) {
                continue;
            }
            for (int offset = 0; offset <= divisor.degree(); offset++) {
                int remainderIndex = index + offset;
                remainder[remainderIndex] = arithmetic.subtract(remainder[remainderIndex],
                    arithmetic.multiply(factor, divisor.coefficients[offset]));
            }
        }
        if (Arrays.stream(remainder).anyMatch(value -> arithmetic.signum(value) != 0)) {
            return null;
        }
        String resultVariable = variable.isEmpty() ? divisor.variable : variable;
        return build(resultVariable, quotient, arithmetic);
    }

    /** @return the polynomial rendered back into an expression tree. */
    Expr toExpression() {
        if (isZero() || variable.isEmpty()) {
            return arithmetic.number(coefficients[0]);
        }
        Expr result = null;
        for (int exponent = degree(); exponent >= 0; exponent--) {
            BigInteger coefficient = coefficients[exponent];
            if (arithmetic.signum(coefficient) == 0) {
                continue;
            }
            Expr term = term(arithmetic.abs(coefficient), exponent);
            if (result == null) {
                result = arithmetic.signum(coefficient) < 0
                    ? arithmetic.binary(
                        arithmetic.number(0), BinaryOperator.SUB, term)
                    : term;
            } else {
                result = arithmetic.binary(
                    result,
                    arithmetic.signum(coefficient) < 0
                        ? BinaryOperator.SUB
                        : BinaryOperator.ADD,
                    term);
            }
        }
        return result == null ? arithmetic.number(0) : result;
    }

    private Expr term(BigInteger coefficient, int exponent) {
        if (exponent == 0) {
            return arithmetic.number(coefficient);
        }
        Expr power = exponent == 1
            ? arithmetic.variable(variable)
            : arithmetic.binary(
                arithmetic.variable(variable),
                BinaryOperator.POW,
                arithmetic.number(exponent));
        return arithmetic.equal(coefficient, BigInteger.ONE)
            ? power
            : arithmetic.binary(
                arithmetic.number(coefficient),
                BinaryOperator.MUL,
                power);
    }

    private UnivariatePolynomial combine(
        UnivariatePolynomial other,
        BigInteger sign
    ) {
        if (!sameVariable(other)) {
            return null;
        }
        BigInteger[] result = zeroCoefficients(
            Math.max(coefficients.length, other.coefficients.length), arithmetic);
        for (int index = 0; index < coefficients.length; index++) {
            result[index] = arithmetic.add(result[index], coefficients[index]);
        }
        for (int index = 0; index < other.coefficients.length; index++) {
            result[index] = arithmetic.add(result[index], arithmetic.multiply(sign, other.coefficients[index]));
        }
        return build(mergedVariable(other), result, arithmetic);
    }

    private UnivariatePolynomial multiply(UnivariatePolynomial other) {
        if (!sameVariable(other)
                || degree() + other.degree() > MAX_DEGREE) {
            return null;
        }
        BigInteger[] result = zeroCoefficients(
            coefficients.length + other.coefficients.length - 1, arithmetic);
        for (int left = 0; left < coefficients.length; left++) {
            for (int right = 0; right < other.coefficients.length; right++) {
                int resultIndex = left + right;
                result[resultIndex] = arithmetic.add(result[resultIndex],
                    arithmetic.multiply(coefficients[left], other.coefficients[right]));
            }
        }
        return build(mergedVariable(other), result, arithmetic);
    }

    private UnivariatePolynomial divideByConstant(
        UnivariatePolynomial other
    ) {
        if (!other.isConstant() || arithmetic.signum(other.coefficients[0]) == 0) {
            return null;
        }
        BigInteger[] result = zeroCoefficients(coefficients.length, arithmetic);
        for (int index = 0; index < coefficients.length; index++) {
            BigInteger[] division = arithmetic.divide(coefficients[index], other.coefficients[0]);
            if (arithmetic.signum(division[1]) != 0) {
                return null;
            }
            result[index] = division[0];
        }
        return build(variable, result, arithmetic);
    }

    private UnivariatePolynomial power(Expr exponentExpression) {
        arithmetic.work.consume("polynomial.ast-node-visits", 1);
        if (!(exponentExpression instanceof NumberExpr exponent)) {
            return null;
        }
        BigInteger exactExponent = exactInteger(exponent.value(), arithmetic);
        if (exactExponent == null
                || arithmetic.signum(exactExponent) < 0
                || arithmetic.compare(exactExponent, BigInteger.valueOf(MAX_EXPONENT)) > 0) {
            return null;
        }
        UnivariatePolynomial result = new UnivariatePolynomial(
            variable,
            coefficients(arithmetic, BigInteger.ONE), arithmetic);
        for (int step = 0; step < arithmetic.intValue(exactExponent); step++) {
            result = result.multiply(this);
            if (result == null) {
                return null;
            }
        }
        return result;
    }

    private boolean sameVariable(UnivariatePolynomial other) {
        return variable.isEmpty() || other.variable.isEmpty()
            || arithmetic.sameText(variable, other.variable);
    }

    private String mergedVariable(UnivariatePolynomial other) {
        return variable.isEmpty() ? other.variable : variable;
    }

    private static UnivariatePolynomial build(
        String variable,
        BigInteger[] coefficients,
        PreparationArithmetic arithmetic
    ) {
        BigInteger[] trimmed = trim(coefficients, arithmetic);
        if (trimmed.length - 1 > MAX_DEGREE
                || Arrays.stream(trimmed)
                    .anyMatch(value -> !isAcceptedCoefficient(value, arithmetic))) {
            return null;
        }
        return new UnivariatePolynomial(variable, trimmed, arithmetic);
    }

    private static BigInteger[] zeroCoefficients(int length, PreparationArithmetic arithmetic) {
        arithmetic.work.consume("polynomial.array-allocated-slots", length);
        BigInteger[] coefficients = new BigInteger[length];
        arithmetic.work.consume("polynomial.array-filled-slots", length);
        Arrays.fill(coefficients, BigInteger.ZERO);
        return coefficients;
    }

    private static BigInteger[] trim(BigInteger[] coefficients, PreparationArithmetic arithmetic) {
        int degree = coefficients.length - 1;
        while (degree > 0 && arithmetic.signum(coefficients[degree]) == 0) {
            degree--;
        }
        arithmetic.work.consume("polynomial.array-copy-slots", degree + 1L);
        return Arrays.copyOf(coefficients, degree + 1);
    }

    private static BigInteger[] coefficients(PreparationArithmetic arithmetic, BigInteger value) {
        arithmetic.work.consume("polynomial.literal-coefficient-slots", 1);
        return new BigInteger[] {value};
    }

    private static BigInteger[] coefficients(PreparationArithmetic arithmetic, BigInteger first, BigInteger second) {
        arithmetic.work.consume("polynomial.literal-coefficient-slots", 2);
        return new BigInteger[] {first, second};
    }

    private static BigInteger exactInteger(ExactRational value, PreparationArithmetic arithmetic) {
        return arithmetic.isInteger(value) && isAcceptedCoefficient(value.numerator(), arithmetic)
            ? value.numerator() : null;
    }

    private static boolean isAcceptedCoefficient(BigInteger value, PreparationArithmetic arithmetic) {
        return arithmetic.compare(arithmetic.abs(value), MAX_ABSOLUTE_COEFFICIENT) <= 0;
    }
}
