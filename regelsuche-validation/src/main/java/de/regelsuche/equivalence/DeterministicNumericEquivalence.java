package de.regelsuche.equivalence;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionParser;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class DeterministicNumericEquivalence {
    private final ExpressionParser parser = new ExpressionParser();

    Boolean areEquivalent(String leftExpression, String rightExpression) {
        Expr left;
        Expr right;
        try {
            left = parser.parseTerm(leftExpression);
            right = parser.parseTerm(rightExpression);
        } catch (IllegalArgumentException ex) {
            return null;
        }
        Set<String> variables = new HashSet<>();
        collectVariables(left, variables);
        collectVariables(right, variables);
        for (int sample = 1; sample <= 7; sample++) {
            Map<String, Double> assignment = new HashMap<>();
            int offset = 0;
            for (String variable : variables) {
                assignment.put(variable, (double) (sample + offset + 1));
                offset++;
            }
            double leftValue = evaluate(left, assignment);
            double rightValue = evaluate(right, assignment);
            if (!Double.isFinite(leftValue) || !Double.isFinite(rightValue)) {
                return null;
            }
            if (Math.abs(leftValue - rightValue) > 1e-7) {
                return false;
            }
        }
        return true;
    }

    private void collectVariables(Expr expression, Set<String> variables) {
        if (expression instanceof VariableExpr variableExpr) {
            variables.add(variableExpr.name());
        } else if (expression instanceof BinaryExpr binaryExpr) {
            collectVariables(binaryExpr.left(), variables);
            collectVariables(binaryExpr.right(), variables);
        }
    }

    private double evaluate(Expr expression, Map<String, Double> variables) {
        if (expression instanceof NumberExpr numberExpr) {
            return numberExpr.value();
        }
        if (expression instanceof VariableExpr variableExpr) {
            return variables.getOrDefault(variableExpr.name(), 0.0);
        }
        BinaryExpr binaryExpr = (BinaryExpr) expression;
        double left = evaluate(binaryExpr.left(), variables);
        double right = evaluate(binaryExpr.right(), variables);
        BinaryOperator operator = binaryExpr.operator();
        return switch (operator) {
            case ADD -> left + right;
            case SUB -> left - right;
            case MUL -> left * right;
            case DIV -> Math.abs(right) < 1e-12 ? Double.NaN : left / right;
            case POW -> Math.pow(left, right);
        };
    }
}
