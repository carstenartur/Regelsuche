package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
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
    /** Largest coefficient that can be represented exactly by the AST number type. */
    private static final BigInteger MAX_ABSOLUTE_COEFFICIENT =
        BigInteger.valueOf(1_000_000_000_000L);

    private final String variable;
    private final BigInteger[] coefficients;

    private UnivariatePolynomial(
        String variable,
        BigInteger[] coefficients
    ) {
        this.variable = variable;
        this.coefficients = trim(coefficients);
    }

    /**
     * @param expression the subtree to interpret
     * @return the polynomial, or {@code null} when the subtree is not an exact
     *     univariate integer-coefficient polynomial
     */
    static UnivariatePolynomial of(Expr expression) {
        if (expression == null) {
            return null;
        }
        if (expression instanceof NumberExpr number) {
            BigInteger value = exactInteger(number.value());
            return value == null
                ? null
                : new UnivariatePolynomial("", new BigInteger[] {value});
        }
        if (expression instanceof VariableExpr variable) {
            return new UnivariatePolynomial(
                variable.name(),
                new BigInteger[] {BigInteger.ZERO, BigInteger.ONE});
        }
        if (!(expression instanceof BinaryExpr binary)) {
            return null;
        }
        UnivariatePolynomial left = of(binary.left());
        if (left == null) {
            return null;
        }
        if (binary.operator() == BinaryOperator.POW) {
            return left.power(binary.right());
        }
        UnivariatePolynomial right = of(binary.right());
        if (right == null) {
            return null;
        }
        return switch (binary.operator()) {
            case ADD -> left.combine(right, BigInteger.ONE);
            case SUB -> left.combine(right, BigInteger.ONE.negate());
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
        return isConstant() && coefficients[0].signum() == 0;
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
        if (divisor.isZero() || !sameVariable(divisor)
                || divisor.degree() > degree()) {
            return null;
        }
        BigInteger[] remainder = coefficients.clone();
        BigInteger[] quotient = zeroCoefficients(
            degree() - divisor.degree() + 1);
        BigInteger leading = divisor.coefficients[divisor.degree()];
        for (int index = quotient.length - 1; index >= 0; index--) {
            BigInteger[] division = remainder[index + divisor.degree()]
                .divideAndRemainder(leading);
            if (division[1].signum() != 0
                    || !isAcceptedCoefficient(division[0])) {
                return null;
            }
            BigInteger factor = division[0];
            quotient[index] = factor;
            if (factor.signum() == 0) {
                continue;
            }
            for (int offset = 0; offset <= divisor.degree(); offset++) {
                int remainderIndex = index + offset;
                remainder[remainderIndex] = remainder[remainderIndex]
                    .subtract(factor.multiply(divisor.coefficients[offset]));
            }
        }
        if (Arrays.stream(remainder).anyMatch(value -> value.signum() != 0)) {
            return null;
        }
        String resultVariable = variable.isEmpty() ? divisor.variable : variable;
        return build(resultVariable, quotient);
    }

    /** @return the polynomial rendered back into an expression tree. */
    Expr toExpression() {
        if (isZero() || variable.isEmpty()) {
            return new NumberExpr(coefficients[0].doubleValue());
        }
        Expr result = null;
        for (int exponent = degree(); exponent >= 0; exponent--) {
            BigInteger coefficient = coefficients[exponent];
            if (coefficient.signum() == 0) {
                continue;
            }
            Expr term = term(coefficient.abs(), exponent);
            if (result == null) {
                result = coefficient.signum() < 0
                    ? new BinaryExpr(
                        new NumberExpr(0), BinaryOperator.SUB, term)
                    : term;
            } else {
                result = new BinaryExpr(
                    result,
                    coefficient.signum() < 0
                        ? BinaryOperator.SUB
                        : BinaryOperator.ADD,
                    term);
            }
        }
        return result == null ? new NumberExpr(0) : result;
    }

    private Expr term(BigInteger coefficient, int exponent) {
        if (exponent == 0) {
            return new NumberExpr(coefficient.doubleValue());
        }
        Expr power = exponent == 1
            ? new VariableExpr(variable)
            : new BinaryExpr(
                new VariableExpr(variable),
                BinaryOperator.POW,
                new NumberExpr(exponent));
        return coefficient.equals(BigInteger.ONE)
            ? power
            : new BinaryExpr(
                new NumberExpr(coefficient.doubleValue()),
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
            Math.max(coefficients.length, other.coefficients.length));
        for (int index = 0; index < coefficients.length; index++) {
            result[index] = result[index].add(coefficients[index]);
        }
        for (int index = 0; index < other.coefficients.length; index++) {
            result[index] = result[index]
                .add(sign.multiply(other.coefficients[index]));
        }
        return build(mergedVariable(other), result);
    }

    private UnivariatePolynomial multiply(UnivariatePolynomial other) {
        if (!sameVariable(other)
                || degree() + other.degree() > MAX_DEGREE) {
            return null;
        }
        BigInteger[] result = zeroCoefficients(
            coefficients.length + other.coefficients.length - 1);
        for (int left = 0; left < coefficients.length; left++) {
            for (int right = 0; right < other.coefficients.length; right++) {
                int resultIndex = left + right;
                result[resultIndex] = result[resultIndex].add(
                    coefficients[left].multiply(other.coefficients[right]));
            }
        }
        return build(mergedVariable(other), result);
    }

    private UnivariatePolynomial divideByConstant(
        UnivariatePolynomial other
    ) {
        if (!other.isConstant() || other.coefficients[0].signum() == 0) {
            return null;
        }
        BigInteger[] result = zeroCoefficients(coefficients.length);
        for (int index = 0; index < coefficients.length; index++) {
            BigInteger[] division = coefficients[index]
                .divideAndRemainder(other.coefficients[0]);
            if (division[1].signum() != 0) {
                return null;
            }
            result[index] = division[0];
        }
        return build(variable, result);
    }

    private UnivariatePolynomial power(Expr exponentExpression) {
        if (!(exponentExpression instanceof NumberExpr exponent)) {
            return null;
        }
        BigInteger exactExponent = exactInteger(exponent.value());
        if (exactExponent == null
                || exactExponent.signum() < 0
                || exactExponent.compareTo(
                    BigInteger.valueOf(MAX_EXPONENT)) > 0) {
            return null;
        }
        UnivariatePolynomial result = new UnivariatePolynomial(
            variable,
            new BigInteger[] {BigInteger.ONE});
        for (int step = 0; step < exactExponent.intValue(); step++) {
            result = result.multiply(this);
            if (result == null) {
                return null;
            }
        }
        return result;
    }

    private boolean sameVariable(UnivariatePolynomial other) {
        return variable.isEmpty() || other.variable.isEmpty()
            || variable.equals(other.variable);
    }

    private String mergedVariable(UnivariatePolynomial other) {
        return variable.isEmpty() ? other.variable : variable;
    }

    private static UnivariatePolynomial build(
        String variable,
        BigInteger[] coefficients
    ) {
        BigInteger[] trimmed = trim(coefficients);
        if (trimmed.length - 1 > MAX_DEGREE
                || Arrays.stream(trimmed)
                    .anyMatch(value -> !isAcceptedCoefficient(value))) {
            return null;
        }
        return new UnivariatePolynomial(variable, trimmed);
    }

    private static BigInteger[] zeroCoefficients(int length) {
        BigInteger[] coefficients = new BigInteger[length];
        Arrays.fill(coefficients, BigInteger.ZERO);
        return coefficients;
    }

    private static BigInteger[] trim(BigInteger[] coefficients) {
        int degree = coefficients.length - 1;
        while (degree > 0 && coefficients[degree].signum() == 0) {
            degree--;
        }
        return Arrays.copyOf(coefficients, degree + 1);
    }

    private static BigInteger exactInteger(double value) {
        if (!Double.isFinite(value)
                || Math.rint(value) != value
                || Math.abs(value) > MAX_ABSOLUTE_COEFFICIENT.doubleValue()) {
            return null;
        }
        return BigInteger.valueOf((long) value);
    }

    private static boolean isAcceptedCoefficient(BigInteger value) {
        return value.abs().compareTo(MAX_ABSOLUTE_COEFFICIENT) <= 0;
    }
}
