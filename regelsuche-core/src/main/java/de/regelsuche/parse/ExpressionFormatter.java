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
    private static final java.math.BigInteger NUMERIC_SYNTAX_LIMIT = java.math.BigInteger.TEN.pow(
        de.regelsuche.scalar.ExactRationalDomain.MAX_DIGITS);
    private ExpressionFormatter() {
    }

    public static String format(Expr expr) {
        return formatMeasured(expr, units -> { });
    }

    /** Charges emitted code units before appending each formatter fragment. */
    public static String formatMeasured(Expr expr, java.util.function.LongConsumer emittedCodeUnits) {
        Output builder = new Output(emittedCodeUnits);
        append(
            Objects.requireNonNull(expr, "expr"),
            0,
            builder);
        return builder.toString();
    }

    public static String format(Equation equation) {
        Objects.requireNonNull(equation, "equation");
        Output builder = new Output(units -> { });
        append(equation.left(), 0, builder);
        builder.append(" = ");
        append(equation.right(), 0, builder);
        return builder.toString();
    }

    private static void append(
        Expr expression,
        int parentPrecedence,
        Output builder
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
        Output builder
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
        Output builder
    ) {
        var value = number.value();
        if (!withinNumericSyntaxLimits(value)) {
            throw new IllegalArgumentException("Numeric leaf exceeds parser digit limits");
        }
        String formatted;
        boolean fraction = false;
        if (value.isInteger()) {
            formatted = value.numerator().toString();
        } else try {
            var decimal = value.toBigDecimal(java.math.MathContext.UNLIMITED).stripTrailingZeros();
            if (decimal.scale() > de.regelsuche.scalar.ExactRationalDomain.MAX_DECIMAL_SCALE) {
                throw new ArithmeticException("render using integer fraction syntax");
            }
            formatted = decimal.toPlainString();
            if (formatted.replace("-", "").replace(".", "").length()
                    > de.regelsuche.scalar.ExactRationalDomain.MAX_DIGITS) {
                throw new ArithmeticException("render using integer fraction syntax");
            }
        } catch (ArithmeticException repeatingDecimal) {
            formatted = value.numerator() + " / " + value.denominator();
            fraction = true;
        }
        if ((value.signum() < 0 && parentPrecedence > 0)
                || (fraction && parentPrecedence > 0)) {
            builder.append('(').append(formatted).append(')');
        } else {
            builder.append(formatted);
        }
    }

    /** Whether integer/fraction syntax can represent both components within parser limits. */
    public static boolean withinNumericSyntaxLimits(de.regelsuche.scalar.ExactRational value) {
        return value.numerator().abs().compareTo(NUMERIC_SYNTAX_LIMIT) < 0
            && value.denominator().compareTo(NUMERIC_SYNTAX_LIMIT) < 0;
    }

    private static void scheduleFunction(
        FunctionExpr function,
        Deque<Action> pending,
        Output builder
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
        Output builder
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
            case MUL -> isDivision(binary.right()) ? 1 : 0;
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

    private static boolean isDivision(Expr expression) {
        return expression instanceof BinaryExpr binary
            && binary.operator() == BinaryOperator.DIV;
    }

    private static final class Output {
        private final StringBuilder text = new StringBuilder();
        private final java.util.function.LongConsumer emittedCodeUnits;

        private Output(java.util.function.LongConsumer emittedCodeUnits) {
            this.emittedCodeUnits = Objects.requireNonNull(emittedCodeUnits, "emittedCodeUnits");
        }

        private Output append(String value) {
            emittedCodeUnits.accept(value.length());
            text.append(value);
            return this;
        }

        private Output append(char value) {
            emittedCodeUnits.accept(1);
            text.append(value);
            return this;
        }

        @Override
        public String toString() {
            return text.toString();
        }
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
