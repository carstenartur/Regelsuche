package de.regelsuche.parse;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Equation;
import de.regelsuche.ast.Expr;
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
            if (Math.rint(numberExpr.value()) == numberExpr.value()) {
                return Long.toString((long) numberExpr.value());
            }
            return Double.toString(numberExpr.value());
        }
        if (expr instanceof VariableExpr variableExpr) {
            return variableExpr.name();
        }
        BinaryExpr binaryExpr = (BinaryExpr) expr;
        BinaryOperator operator = binaryExpr.operator();
        int precedence = operator.precedence();

        String left = format(binaryExpr.left(), precedence);
        String right = format(binaryExpr.right(), precedence + (operator == BinaryOperator.POW ? -1 : 0));
        String value = left + " " + operator.symbol() + " " + right;
        if (precedence < parentPrecedence) {
            return "(" + value + ")";
        }
        return value;
    }
}
