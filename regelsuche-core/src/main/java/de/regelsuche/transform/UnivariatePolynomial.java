package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
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

    private final String variable;
    private final double[] coefficients;

    private UnivariatePolynomial(String variable, double[] coefficients) {
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
            return isExactInteger(number.value())
                ? new UnivariatePolynomial("", new double[] {number.value()})
                : null;
        }
        if (expression instanceof VariableExpr variable) {
            return new UnivariatePolynomial(
                variable.name(), new double[] {0.0, 1.0});
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
            case ADD -> left.combine(right, 1.0);
            case SUB -> left.combine(right, -1.0);
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
        return isConstant() && coefficients[0] == 0.0;
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
        double[] remainder = coefficients.clone();
        double[] quotient = new double[degree() - divisor.degree() + 1];
        double leading = divisor.coefficients[divisor.degree()];
        for (int index = quotient.length - 1; index >= 0; index--) {
            double factor = remainder[index + divisor.degree()] / leading;
            if (!isExactInteger(factor)) {
                return null;
            }
            quotient[index] = factor;
            if (factor == 0.0) {
                continue;
            }
            for (int offset = 0; offset <= divisor.degree(); offset++) {
                remainder[index + offset] -=
                    factor * divisor.coefficients[offset];
            }
        }
        for (int index = 0; index < divisor.degree(); index++) {
            if (remainder[index] != 0.0) {
                return null;
            }
        }
        String resultVariable = variable.isEmpty() ? divisor.variable : variable;
        return new UnivariatePolynomial(resultVariable, quotient);
    }

    /** @return the polynomial rendered back into an expression tree. */
    Expr toExpression() {
        if (isZero() || variable.isEmpty()) {
            return new NumberExpr(coefficients[0]);
        }
        Expr result = null;
        for (int exponent = degree(); exponent >= 0; exponent--) {
            double coefficient = coefficients[exponent];
            if (coefficient == 0.0) {
                continue;
            }
            Expr term = term(Math.abs(coefficient), exponent);
            if (result == null) {
                result = coefficient < 0.0
                    ? new BinaryExpr(
                        new NumberExpr(0), BinaryOperator.SUB, term)
                    : term;
            } else {
                result = new BinaryExpr(
                    result,
                    coefficient < 0.0 ? BinaryOperator.SUB : BinaryOperator.ADD,
                    term);
            }
        }
        return result == null ? new NumberExpr(0) : result;
    }

    private Expr term(double coefficient, int exponent) {
        if (exponent == 0) {
            return new NumberExpr(coefficient);
        }
        Expr power = exponent == 1
            ? new VariableExpr(variable)
            : new BinaryExpr(
                new VariableExpr(variable),
                BinaryOperator.POW,
                new NumberExpr(exponent));
        return coefficient == 1.0
            ? power
            : new BinaryExpr(
                new NumberExpr(coefficient), BinaryOperator.MUL, power);
    }

    private UnivariatePolynomial combine(
        UnivariatePolynomial other,
        double sign
    ) {
        if (!sameVariable(other)) {
            return null;
        }
        double[] result = new double[
            Math.max(coefficients.length, other.coefficients.length)];
        for (int index = 0; index < coefficients.length; index++) {
            result[index] += coefficients[index];
        }
        for (int index = 0; index < other.coefficients.length; index++) {
            result[index] += sign * other.coefficients[index];
        }
        return build(mergedVariable(other), result);
    }

    private UnivariatePolynomial multiply(UnivariatePolynomial other) {
        if (!sameVariable(other)) {
            return null;
        }
        double[] result =
            new double[coefficients.length + other.coefficients.length - 1];
        for (int left = 0; left < coefficients.length; left++) {
            for (int right = 0; right < other.coefficients.length; right++) {
                result[left + right] +=
                    coefficients[left] * other.coefficients[right];
            }
        }
        return build(mergedVariable(other), result);
    }

    private UnivariatePolynomial divideByConstant(UnivariatePolynomial other) {
        if (!other.isConstant() || other.coefficients[0] == 0.0) {
            return null;
        }
        double[] result = new double[coefficients.length];
        for (int index = 0; index < coefficients.length; index++) {
            result[index] = coefficients[index] / other.coefficients[0];
        }
        return build(variable, result);
    }

    private UnivariatePolynomial power(Expr exponentExpression) {
        if (!(exponentExpression instanceof NumberExpr exponent)
                || !isExactInteger(exponent.value())
                || exponent.value() < 0.0
                || exponent.value() > MAX_EXPONENT) {
            return null;
        }
        UnivariatePolynomial result =
            new UnivariatePolynomial(variable, new double[] {1.0});
        for (int step = 0; step < (int) exponent.value(); step++) {
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
        double[] coefficients
    ) {
        double[] trimmed = trim(coefficients);
        if (trimmed.length - 1 > MAX_DEGREE) {
            return null;
        }
        for (double coefficient : trimmed) {
            if (!isExactInteger(coefficient)) {
                return null;
            }
        }
        return new UnivariatePolynomial(variable, trimmed);
    }

    private static double[] trim(double[] coefficients) {
        int degree = coefficients.length - 1;
        while (degree > 0 && coefficients[degree] == 0.0) {
            degree--;
        }
        return Arrays.copyOf(coefficients, degree + 1);
    }

    private static boolean isExactInteger(double value) {
        return Double.isFinite(value) && Math.rint(value) == value
            && Math.abs(value) <= 1e12;
    }
}
