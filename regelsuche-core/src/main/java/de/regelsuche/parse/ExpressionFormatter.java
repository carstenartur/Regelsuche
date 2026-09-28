package de.regelsuche.parse;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Equation;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
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
        return formatMeasured(expr, NativeEmission.INSTANCE);
    }

    /**
     * Delegates emitted code units before appending each fragment. Native observation
     * additionally pays workspace, allocation and copying; it does not charge the
     * delegated emission again. An active native scope requires an audited callback view.
     */
    public static String formatMeasured(Expr expr, java.util.function.LongConsumer emittedCodeUnits) {
        Objects.requireNonNull(expr, "expr");
        Objects.requireNonNull(emittedCodeUnits, "emittedCodeUnits");
        if (expr instanceof VariableExpr variable) {
            try (var input = RetainedOperation.retain(expr, emittedCodeUnits)) {
                emit(emittedCodeUnits, variable.name().length());
                RetainedOperation.work(1);
                return variable.name();
            }
        }
        Output builder = new Output(expr, emittedCodeUnits);
        try (var output = RetainedOperation.retain(builder)) {
            append(builder);
            return builder.value();
        }
    }

    public static String format(Equation equation) {
        Objects.requireNonNull(equation, "equation");
        Output builder = new Output(equation, NativeEmission.INSTANCE);
        try (var output = RetainedOperation.retain(builder)) {
            append(builder);
            return builder.value();
        }
    }

    private static void append(Output builder) {
        while (!builder.pending.isEmpty()) {
            Action action = builder.pending.pop();
            builder.current = action;
            RetainedOperation.work(2);
            if (action instanceof AppendText text) {
                builder.appendOwned(text.value());
            } else {
                FormatExpression format = (FormatExpression) action;
                schedule(format.expression(), format.parentPrecedence(), builder.pending, builder);
            }
            builder.current = null;
            RetainedOperation.work(1);
        }
    }

    private static void push(Deque<Action> pending, Action action) {
        pending.push(action);
        RetainedOperation.work(1);
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
            builder.appendOwned(variable.name());
            return;
        }
        if (expression instanceof FunctionExpr function) {
            scheduleFunction(function, pending, builder);
            RetainedOperation.checkpoint();
            return;
        }
        if (expression instanceof BinaryExpr binary) {
            scheduleBinary(binary, parentPrecedence, pending, builder);
            RetainedOperation.checkpoint();
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
        builder.append(formatted, (value.signum() < 0 || fraction) && parentPrecedence > 0);
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
        builder.appendOwned(function.name()).append('(');
        push(pending, new AppendText(")"));
        List<Expr> arguments = function.arguments();
        for (int index = arguments.size() - 1;
                index >= 0;
                index--) {
            if (index < arguments.size() - 1) {
                push(pending, new AppendText(", "));
            }
            push(pending, new FormatExpression(
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
            push(pending, new AppendText(")"));
        }

        int leftAdjust = operator == BinaryOperator.POW ? 1 : 0;
        int rightAdjust = switch (operator) {
            // Equal-precedence powers associate to the right. Lowering this
            // to MUL/DIV precedence would turn x^(A*B) into x^A*B.
            case POW -> 0;
            case DIV, SUB -> 1;
            case MUL -> isDivision(binary.right()) ? 1 : 0;
            default -> 0;
        };
        push(pending, new FormatExpression(
            binary.right(),
            precedence + rightAdjust));
        push(pending, new AppendText(switch (operator) {
            case ADD -> " + "; case SUB -> " - "; case MUL -> " * ";
            case DIV -> " / "; case POW -> " ^ ";
        }));
        push(pending, new FormatExpression(
            binary.left(),
            precedence + leftAdjust));
    }

    private static boolean isDivision(Expr expression) {
        return expression instanceof BinaryExpr binary
            && binary.operator() == BinaryOperator.DIV;
    }

    private enum NativeEmission implements java.util.function.LongConsumer, RetainedGraph.View {
        INSTANCE;
        @Override public void accept(long units) { RetainedOperation.work(units); }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { }
    }

    private static void emit(java.util.function.LongConsumer callback, long units) {
        if (callback == NativeEmission.INSTANCE) {
            // This closed implementation only updates the existing work counter.
            callback.accept(units);
            return;
        }
        try {
            callback.accept(units);
        } catch (RuntimeException | Error failure) {
            try { RetainedOperation.checkpoint(); }
            catch (RuntimeException | Error observation) {
                if (observation != failure) failure.addSuppressed(observation);
            }
            throw failure;
        }
        RetainedOperation.checkpoint();
    }

    private static final class Output implements RetainedGraph.View {
        private final Object input;
        private char[] text = new char[16];
        private int size;
        private final Deque<Action> pending = new ArrayDeque<>();
        private Action current;
        private String fragment;
        private final java.util.function.LongConsumer emittedCodeUnits;

        private Output(Object input, java.util.function.LongConsumer emittedCodeUnits) {
            this.input = input;
            this.emittedCodeUnits = Objects.requireNonNull(emittedCodeUnits, "emittedCodeUnits");
            RetainedOperation.work(19);
            if (input instanceof Expr expression) {
                push(pending, new FormatExpression(expression, 0));
            } else {
                Equation equation = (Equation) input;
                push(pending, new FormatExpression(equation.right(), 0));
                push(pending, new AppendText(" = "));
                push(pending, new FormatExpression(equation.left(), 0));
            }
        }

        /** Only source names or the current AppendText value enter here; their owner stays live. */
        private Output appendOwned(String value) {
            emit(emittedCodeUnits, value.length());
            ensureCapacity(value.length());
            value.getChars(0, value.length(), text, size);
            size += value.length();
            RetainedOperation.work(1);
            return this;
        }

        private Output append(String value, boolean parenthesized) {
            fragment = value;
            RetainedOperation.work(1);
            try {
                RetainedOperation.checkpoint();
                if (parenthesized) append('(');
                emit(emittedCodeUnits, value.length());
                ensureCapacity(value.length());
                value.getChars(0, value.length(), text, size);
                size += value.length();
                RetainedOperation.work(1);
                if (parenthesized) append(')');
            } catch (RuntimeException | Error failure) {
                try { clearFragment(); }
                catch (RuntimeException | Error cleanup) {
                    if (cleanup != failure) failure.addSuppressed(cleanup);
                }
                throw failure;
            }
            clearFragment();
            return this;
        }

        private void clearFragment() {
            fragment = null;
            RetainedOperation.work(1);
        }

        private Output append(char value) {
            emit(emittedCodeUnits, 1);
            ensureCapacity(1);
            text[size++] = value;
            RetainedOperation.work(1);
            return this;
        }

        private void ensureCapacity(int additional) {
            int needed = Math.addExact(size, additional);
            if (needed <= text.length) return;
            var old = text;
            var replacement = new char[Math.max(needed, Math.addExact(Math.multiplyExact(old.length, 2), 2))];
            RetainedOperation.work(replacement.length);
            try (var growth = RetainedOperation.retain(old, replacement)) {
                System.arraycopy(old, 0, replacement, 0, size);
                text = replacement;
                RetainedOperation.work(size + 1L);
            }
        }

        private String value() {
            RetainedOperation.work(size);
            return RetainedOperation.produced(new String(text, 0, size));
        }

        @Override public void retainedReferences(RetainedGraph.Visitor visitor) {
            visitor.reference(input);
            visitor.reference(text);
            visitor.reference(emittedCodeUnits);
            visitor.reference(pending);
            visitor.reference(current);
            visitor.reference(fragment);
        }
    }

    private sealed interface Action extends RetainedGraph.View
            permits AppendText, FormatExpression {
    }

    private record AppendText(String value) implements Action {
        private AppendText {
            Objects.requireNonNull(value, "value");
            RetainedOperation.work(1);
        }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(value); }
    }

    private record FormatExpression(
        Expr expression,
        int parentPrecedence
    ) implements Action {
        private FormatExpression {
            Objects.requireNonNull(expression, "expression");
            RetainedOperation.work(1);
        }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(expression); }
    }
}
