package de.regelsuche.parse;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Equation;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;

public final class ExpressionFormatter {
    private ExpressionFormatter() {
    }

    public static String format(Expr expr) {
        return format(expr, 0);
    }

    public static String format(Equation equation) {
        return format(equation.left()) + " = " + format(equation.right());
    }

    private static String format(Expr expr, int parentPrecedence) {
        if (expr instanceof NumberExpr numberExpr) {
            String formatted;
            if (Math.rint(numberExpr.value()) == numberExpr.value()) {
                formatted = Long.toString((long) numberExpr.value());
            } else {
                formatted = Double.toString(numberExpr.value());
            }
            if (numberExpr.value() < 0 && parentPrecedence > 0) {
                return "(" + formatted + ")";
            }
            return formatted;
        }
        if (expr instanceof VariableExpr variableExpr) {
            return variableExpr.name();
        }
        if (expr instanceof FunctionExpr functionExpr) {
            StringBuilder builder = new StringBuilder(functionExpr.name());
            builder.append('(');
            for (int i = 0; i < functionExpr.arguments().size(); i++) {
                if (i > 0) {
                    builder.append(", ");
                }
                builder.append(format(functionExpr.arguments().get(i), 0));
            }
            builder.append(')');
            return builder.toString();
        }
        BinaryExpr binaryExpr = (BinaryExpr) expr;
        BinaryOperator operator = binaryExpr.operator();
        int precedence = operator.precedence();

        String left = format(binaryExpr.left(), precedence + (operator == BinaryOperator.POW ? 1 : 0));
        String right = format(binaryExpr.right(), precedence + (operator == BinaryOperator.POW ? -1 : 0));
        String value = left + " " + operator.symbol() + " " + right;
        if (precedence < parentPrecedence) {
            return "(" + value + ")";
        }
        return value;
    }
}
