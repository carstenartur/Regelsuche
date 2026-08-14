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
        Objects.requireNonNull(expression, "expression");
        return measure(parser.parseTerm(expression));
    }

    public SemanticDescriptionMetrics measure(Expr expression) {
        Objects.requireNonNull(expression, "expression");
        SyntaxStatistics syntax = new SyntaxStatistics();
        inspect(expression, syntax);

        int semanticOccurrences;
        int distinctValues;
        try (ExprValueFactory factory = new ExprValueFactory()) {
            ExprValueFactory.Projection projection = factory.project(expression);
            semanticOccurrences = projection.valuesBySyntaxIdentity().size();
            distinctValues = new LinkedHashSet<>(
                projection.valuesBySyntaxIdentity().values()).size();
        }

        String normalized = ExpressionFormatter.format(expression);
        return new SemanticDescriptionMetrics(
            normalized,
            tokenCount(normalized),
            syntax.astNodeCount,
            syntax.operatorCount,
            syntax.numericBitLength,
            semanticOccurrences,
            distinctValues,
            semanticOccurrences - distinctValues,
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
            return;
        }
        if (expression instanceof FunctionExpr function) {
            statistics.operatorCount++;
            statistics.functions.add(function.name());
            for (Expr argument : function.arguments()) {
                inspect(argument, statistics);
            }
            return;
        }
        if (expression instanceof VariableExpr variable) {
            statistics.variables.add(variable.name());
            return;
        }
        if (expression instanceof NumberExpr number) {
            statistics.numericBitLength = Math.addExact(
                statistics.numericBitLength,
                numericBitLength(number.value())
            );
            return;
        }
        throw new IllegalArgumentException(
            "Unsupported expression implementation: " + expression.getClass().getName());
    }

    private static int numericBitLength(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("non-finite numbers are not measurable");
        }
        BigDecimal decimal = BigDecimal.valueOf(value).stripTrailingZeros();
        BigInteger unscaled = decimal.unscaledValue().abs();
        int bits = Math.max(1, unscaled.bitLength());
        int scale = decimal.scale();
        if (scale != 0) {
            bits = Math.addExact(
                bits,
                BigInteger.TEN.pow(Math.abs(scale)).bitLength()
            );
        }
        return bits;
    }

    private static int tokenCount(String expression) {
        int tokens = 0;
        int index = 0;
        while (index < expression.length()) {
            char current = expression.charAt(index);
            if (Character.isWhitespace(current)) {
                index++;
                continue;
            }
            if (Character.isLetter(current) || current == '_') {
                index++;
                while (index < expression.length()) {
                    char next = expression.charAt(index);
                    if (!Character.isLetterOrDigit(next) && next != '_') {
                        break;
                    }
                    index++;
                }
                tokens++;
                continue;
            }
            if (Character.isDigit(current) || current == '.') {
                index++;
                while (index < expression.length()) {
                    char next = expression.charAt(index);
                    if (!Character.isDigit(next) && next != '.') {
                        break;
                    }
                    index++;
                }
                tokens++;
                continue;
            }
            tokens++;
            index++;
        }
        return Math.max(1, tokens);
    }

    private static final class SyntaxStatistics {
        private int astNodeCount;
        private int operatorCount;
        private int numericBitLength;
        private final Set<String> variables = new TreeSet<>();
        private final Set<String> functions = new TreeSet<>();
    }
}
