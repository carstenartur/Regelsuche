package de.regelsuche.parse;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Equation;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

public final class ExpressionFormatter {
    private ExpressionFormatter() {
    }

    public static String format(Expr expr) {
        StringBuilder builder = new StringBuilder();
        append(
            Objects.requireNonNull(expr, "expr"),
            0,
            builder);
        return builder.toString();
    }

    public static String format(Equation equation) {
        Objects.requireNonNull(equation, "equation");
        StringBuilder builder = new StringBuilder();
        append(equation.left(), 0, builder);
        builder.append(" = ");
        append(equation.right(), 0, builder);
        return builder.toString();
    }

    private static void append(
        Expr expression,
        int parentPrecedence,
        StringBuilder builder
    ) {
        Deque<Action> pending = new ArrayDeque<>();
        pending.push(new FormatExpression(
            expression,
            parentPrecedence));
        while (!pending.isEmpty()) {
            Action action = pending.pop();
            if (action instanceof AppendText text) {
                builder.append(text.value());
            } else {
                FormatExpression format = (FormatExpression) action;
                schedule(
                    format.expression(),
                    format.parentPrecedence(),
                    pending,
                    builder);
            }
        }
    }

    private static void schedule(
        Expr expression,
        int parentPrecedence,
        Deque<Action> pending,
        StringBuilder builder
    ) {
        if (expression instanceof NumberExpr number) {
            appendNumber(number, parentPrecedence, builder);
            return;
        }
        if (expression instanceof VariableExpr variable) {
            builder.append(variable.name());
            return;
        }
        if (expression instanceof FunctionExpr function) {
            scheduleFunction(function, pending, builder);
            return;
        }
        if (expression instanceof BinaryExpr binary) {
            scheduleBinary(binary, parentPrecedence, pending, builder);
            return;
        }
        throw new IllegalArgumentException(
            "unsupported expression type: "
                + expression.getClass().getName());
    }

    private static void appendNumber(
        NumberExpr number,
        int parentPrecedence,
        StringBuilder builder
    ) {
        String formatted;
        if (Math.rint(number.value()) == number.value()) {
            formatted = Long.toString((long) number.value());
        } else {
            formatted = Double.toString(number.value());
        }
        if (number.value() < 0 && parentPrecedence > 0) {
            builder.append('(')
                .append(formatted)
                .append(')');
        } else {
            builder.append(formatted);
        }
    }

    private static void scheduleFunction(
        FunctionExpr function,
        Deque<Action> pending,
        StringBuilder builder
    ) {
        builder.append(function.name()).append('(');
        pending.push(new AppendText(")"));
        List<Expr> arguments = function.arguments();
        for (int index = arguments.size() - 1;
                index >= 0;
                index--) {
            if (index < arguments.size() - 1) {
                pending.push(new AppendText(", "));
            }
            pending.push(new FormatExpression(
                arguments.get(index),
                0));
        }
    }

    private static void scheduleBinary(
        BinaryExpr binary,
        int parentPrecedence,
        Deque<Action> pending,
        StringBuilder builder
    ) {
        BinaryOperator operator = binary.operator();
        int precedence = operator.precedence();
        boolean parenthesized = precedence < parentPrecedence;
        if (parenthesized) {
            builder.append('(');
            pending.push(new AppendText(")"));
        }

        int leftAdjust = operator == BinaryOperator.POW ? 1 : 0;
        int rightAdjust = switch (operator) {
            case POW -> -1;
            case DIV, SUB -> 1;
            default -> 0;
        };
        pending.push(new FormatExpression(
            binary.right(),
            precedence + rightAdjust));
        pending.push(new AppendText(
            " " + operator.symbol() + " "));
        pending.push(new FormatExpression(
            binary.left(),
            precedence + leftAdjust));
    }

    private sealed interface Action
            permits AppendText, FormatExpression {
    }

    private record AppendText(String value) implements Action {
        private AppendText {
            Objects.requireNonNull(value, "value");
        }
    }

    private record FormatExpression(
        Expr expression,
        int parentPrecedence
    ) implements Action {
        private FormatExpression {
            Objects.requireNonNull(expression, "expression");
        }
    }
}
