package de.regelsuche.assumption;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.scalar.ExactRational;
import java.util.ArrayList;
import java.util.List;

/** Shared conservative real-domain guard for removing an expression during simplification. */
public final class ExpressionDefinedness {
    private ExpressionDefinedness() {
    }

    /** True only when removing the expression needs no additional assumptions. */
    public static boolean isTotal(Expr expression) {
        return canElideWithoutDomainLoss(expression, null);
    }

    /**
     * Returns whether {@code expression} may disappear from the canonical AST
     * without enlarging its documented real-domain semantics. Partial
     * operators are inspected recursively. If all required guards are
     * representable and an {@link AssumptionContext} is available, they are
     * recorded before the elision is allowed.
     */
    public static boolean canElideWithoutDomainLoss(
        Expr expression,
        AssumptionContext context
    ) {
        List<Assumption> requirements = new ArrayList<>();
        if (!collectElisionRequirements(expression, requirements)) {
            return false;
        }
        if (requirements.isEmpty()) {
            return true;
        }
        if (context == null) {
            return false;
        }
        context.addAll(requirements);
        return true;
    }

    private static boolean collectElisionRequirements(
        Expr expression,
        List<Assumption> requirements
    ) {
        if (expression instanceof NumberExpr
                || expression instanceof VariableExpr) {
            return true;
        }
        if (expression instanceof FunctionExpr function) {
            return collectFunctionElisionRequirements(
                function, requirements);
        }
        if (!(expression instanceof BinaryExpr binary)) {
            return false;
        }
        if (!collectElisionRequirements(binary.left(), requirements)
                || !collectElisionRequirements(binary.right(), requirements)) {
            return false;
        }
        if (binary.operator() == BinaryOperator.DIV) {
            return requireNonZeroForElision(
                binary.right(), requirements);
        }
        if (binary.operator() == BinaryOperator.POW) {
            return collectPowerElisionRequirements(
                binary.left(), binary.right(), requirements);
        }
        return true;
    }

    private static boolean collectFunctionElisionRequirements(
        FunctionExpr function,
        List<Assumption> requirements
    ) {
        for (Expr argument : function.arguments()) {
            if (!collectElisionRequirements(argument, requirements)) {
                return false;
            }
        }
        if (function.arguments().size() != 1) {
            return false;
        }
        Expr argument = function.argument();
        String argumentText = ExpressionFormatter.format(argument);
        return switch (function.name()) {
            case "sin", "cos", "exp", "abs" -> true;
            case "log", "ln" -> {
                requirements.add(Assumption.positive(argumentText));
                yield true;
            }
            case "sqrt" -> {
                requirements.add(Assumption.nonNegative(argumentText));
                yield true;
            }
            case "tan" -> {
                requirements.add(Assumption.nonZero(
                    "cos(" + argumentText + ")"));
                yield true;
            }
            default -> false;
        };
    }

    private static boolean collectPowerElisionRequirements(
        Expr base,
        Expr exponent,
        List<Assumption> requirements
    ) {
        ExactRational value = signedIntegerLiteralValue(exponent);
        if (value == null) {
            return false;
        }
        return value.signum() > 0
            || requireNonZeroForElision(base, requirements);
    }

    /**
     * Returns an integral numeric exponent represented by the AST.
     * Negative literals are parsed as {@code 0 - n}, so recognize that exact
     * parser shape without broadening this guard into a general evaluator.
     */
    private static ExactRational signedIntegerLiteralValue(Expr expression) {
        if (expression instanceof NumberExpr number) {
            ExactRational value = number.value();
            return value.isInteger()
                ? value
                : null;
        }
        if (expression instanceof BinaryExpr binary
                && binary.operator() == BinaryOperator.SUB
                && isNumber(binary.left(), 0)
                && binary.right() instanceof NumberExpr number) {
            ExactRational magnitude = number.value();
            return magnitude.isInteger()
                ? magnitude.negate()
                : null;
        }
        return null;
    }

    private static boolean requireNonZeroForElision(
        Expr expression,
        List<Assumption> requirements
    ) {
        if (expression instanceof NumberExpr number) {
            return !number.value().equalsInteger(0);
        }
        requirements.add(Assumption.nonZero(
            ExpressionFormatter.format(expression)));
        return true;
    }

    private static boolean isNumber(Expr expression, long value) {
        return expression instanceof NumberExpr number && number.value().equalsInteger(value);
    }
}
