package de.regelsuche.discovery.representation;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.value.ExprValueFactory;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Computes deterministic raw description measures without choosing one scalar score. */
public final class SemanticDescriptionMeasurer {
    private final ExpressionParser parser;

    public SemanticDescriptionMeasurer() {
        this(new ExpressionParser());
    }

    public SemanticDescriptionMeasurer(ExpressionParser parser) {
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    public SemanticDescriptionMetrics measure(String expression) {
        return measure(parser.parseTerm(Objects.requireNonNull(expression, "expression")));
    }

    public SemanticDescriptionMetrics measure(Expr expression) {
        Objects.requireNonNull(expression, "expression");
        SyntaxStatistics syntax = new SyntaxStatistics();
        inspect(expression, syntax);

        int distinctValues;
        try (ExprValueFactory factory = new ExprValueFactory()) {
            distinctValues = new LinkedHashSet<>(
                factory.project(expression).valuesBySyntaxIdentity().values()).size();
        }

        String normalized = ExpressionFormatter.format(expression);
        return new SemanticDescriptionMetrics(
            normalized,
            tokenCount(normalized),
            syntax.astNodeCount,
            syntax.operatorCount,
            syntax.numericBitLength,
            syntax.astNodeCount,
            distinctValues,
            syntax.astNodeCount - distinctValues,
            syntax.variables.stream().toList(),
            syntax.functions.stream().toList()
        );
    }

    private static void inspect(Expr expression, SyntaxStatistics statistics) {
        statistics.astNodeCount++;
        if (expression instanceof BinaryExpr binary) {
            statistics.operatorCount++;
            inspect(binary.left(), statistics);
            inspect(binary.right(), statistics);
        } else if (expression instanceof FunctionExpr function) {
            statistics.operatorCount++;
            statistics.functions.add(function.name());
            function.arguments().forEach(argument -> inspect(argument, statistics));
        } else if (expression instanceof VariableExpr variable) {
            statistics.variables.add(variable.name());
        } else if (expression instanceof NumberExpr number) {
            statistics.numericBitLength = Math.addExact(
                statistics.numericBitLength, numericBitLength(number.value()));
        } else {
            throw new IllegalArgumentException(
                "Unsupported expression implementation: "
                    + expression.getClass().getName());
        }
    }

    private static int numericBitLength(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("non-finite numbers are not measurable");
        }
        BigDecimal decimal = BigDecimal.valueOf(value).stripTrailingZeros();
        int bits = Math.max(1, decimal.unscaledValue().abs().bitLength());
        return decimal.scale() == 0
            ? bits
            : Math.addExact(
                bits,
                BigInteger.TEN.pow(Math.abs(decimal.scale())).bitLength());
    }

    private static int tokenCount(String expression) {
        int tokens = 0;
        int index = 0;
        while (index < expression.length()) {
            char current = expression.charAt(index);
            if (Character.isWhitespace(current)) {
                index++;
            } else if (Character.isLetter(current) || current == '_') {
                index = scanIdentifier(expression, index);
                tokens++;
            } else if (Character.isDigit(current) || current == '.') {
                index = scanNumber(expression, index);
                tokens++;
            } else if ("+-*/^(),".indexOf(current) >= 0) {
                index++;
                tokens++;
            } else {
                throw new IllegalArgumentException(
                    "unsupported formatted token at position " + index);
            }
        }
        return Math.max(1, tokens);
    }

    private static int scanIdentifier(String expression, int index) {
        int cursor = index + 1;
        while (cursor < expression.length()) {
            char current = expression.charAt(cursor);
            if (!Character.isLetterOrDigit(current) && current != '_') {
                break;
            }
            cursor++;
        }
        return cursor;
    }

    private static int scanNumber(String expression, int index) {
        int cursor = index;
        while (cursor < expression.length()
                && Character.isDigit(expression.charAt(cursor))) {
            cursor++;
        }
        if (cursor < expression.length() && expression.charAt(cursor) == '.') {
            cursor++;
            while (cursor < expression.length()
                    && Character.isDigit(expression.charAt(cursor))) {
                cursor++;
            }
        }
        if (cursor < expression.length()
                && (expression.charAt(cursor) == 'e'
                    || expression.charAt(cursor) == 'E')) {
            cursor++;
            if (cursor < expression.length()
                    && (expression.charAt(cursor) == '+'
                        || expression.charAt(cursor) == '-')) {
                cursor++;
            }
            int exponentStart = cursor;
            while (cursor < expression.length()
                    && Character.isDigit(expression.charAt(cursor))) {
                cursor++;
            }
            if (cursor == exponentStart) {
                throw new IllegalArgumentException(
                    "invalid formatted exponent at position " + index);
            }
        }
        if (cursor == index
                || cursor == index + 1 && expression.charAt(index) == '.') {
            throw new IllegalArgumentException(
                "invalid formatted number at position " + index);
        }
        return cursor;
    }

    private static final class SyntaxStatistics {
        private int astNodeCount;
        private int operatorCount;
        private int numericBitLength;
        private final Set<String> variables = new TreeSet<>();
        private final Set<String> functions = new TreeSet<>();
    }
}
