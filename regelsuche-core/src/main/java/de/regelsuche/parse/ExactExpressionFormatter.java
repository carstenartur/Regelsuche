package de.regelsuche.parse;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.scalar.ExactRational;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Formats one parser-issued exact term without reading numeric leaf values back
 * from the legacy {@code double} representation.
 */
public final class ExactExpressionFormatter {
    private ExactExpressionFormatter() {
    }

    public static String format(
        Expr expression,
        ExactParsedTerm parsed
    ) {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(parsed, "parsed");
        return format(expression, parsed, 0);
    }

    private static String format(
        Expr expression,
        ExactParsedTerm parsed,
        int parentPrecedence
    ) {
        if (expression instanceof NumberExpr number) {
            String formatted = parsed.literalFor(number)
                .map(ExactParsedTerm.LiteralOccurrence::exactValue)
                .map(ExactExpressionFormatter::canonicalLiteral)
                .orElseGet(() -> syntheticNumber(number));
            if (formatted.startsWith("-") && parentPrecedence > 0) {
                return "(" + formatted + ")";
            }
            return formatted;
        }
        if (expression instanceof VariableExpr variable) {
            return variable.name();
        }
        if (expression instanceof FunctionExpr function) {
            StringBuilder result = new StringBuilder(function.name());
            result.append('(');
            for (int index = 0;
                    index < function.arguments().size();
                    index++) {
                if (index > 0) {
                    result.append(", ");
                }
                result.append(format(
                    function.arguments().get(index),
                    parsed,
                    0));
            }
            return result.append(')').toString();
        }

        BinaryExpr binary = (BinaryExpr) expression;
        BinaryOperator operator = binary.operator();
        int precedence = operator.precedence();
        int leftAdjust = operator == BinaryOperator.POW ? 1 : 0;
        int rightAdjust = switch (operator) {
            case POW -> -1;
            case DIV, SUB -> 1;
            default -> 0;
        };
        String left = format(
            binary.left(),
            parsed,
            precedence + leftAdjust);
        String right = format(
            binary.right(),
            parsed,
            precedence + rightAdjust);
        String value = left + " " + operator.symbol() + " " + right;
        return precedence < parentPrecedence
            ? "(" + value + ")"
            : value;
    }

    private static String syntheticNumber(NumberExpr number) {
        if (number.value() == 0.0d) {
            return "0";
        }
        throw new IllegalArgumentException(
            "numeric node lacks parser-issued exact source evidence");
    }

    private static String canonicalLiteral(ExactRational value) {
        if (value.isInteger()) {
            return value.numerator().toString();
        }
        BigDecimal decimal = new BigDecimal(value.numerator())
            .divide(new BigDecimal(value.denominator()))
            .stripTrailingZeros();
        return decimal.toPlainString();
    }
}
