package de.regelsuche.scoring.cost;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.scoring.ExpressionScore;

/**
 * Rewards expressions that resemble standard school-book notation:
 * shallow nesting, small integer coefficients, no unnecessary parentheses
 * (approximated via nesting depth), no advanced functions in the result.
 *
 * <p>Used by {@link TransformationGoal#TEACHING_FRIENDLY TEACHING_FRIENDLY}
 * — the search ends up preferring {@code (x + 1)^2} over the algebraically
 * equivalent but didactically denser {@code x^2 + 2*x + 1} when both are
 * reachable, and avoids exotic numerator/denominator constructs whenever a
 * polynomial form exists.</p>
 */
public final class TeachingFriendlinessCost implements CostModel {

    private static final int LARGE_COEFFICIENT_THRESHOLD = 20;

    @Override
    public int cost(String expression, Expr parsedAst, ExpressionScore score) {
        if (parsedAst == null) {
            return Math.max(0, score.operatorCount() + score.nestingDepth());
        }
        int base = baseCost(parsedAst);
        int depth = depth(parsedAst);
        int largeCoefficients = largeCoefficientPenalty(parsedAst);
        int divisionPenalty = divisionPenalty(parsedAst);
        int advancedFunctionPenalty = advancedFunctionPenalty(parsedAst);
        // depth is squared-ish so small step from 2 → 5 hurts a lot
        return base + depth * 2 + largeCoefficients + divisionPenalty + advancedFunctionPenalty;
    }

    @Override
    public String id() {
        return "teaching-friendly";
    }

    private int baseCost(Expr expression) {
        if (expression instanceof BinaryExpr binary) {
            return 1 + baseCost(binary.left()) + baseCost(binary.right());
        }
        if (expression instanceof FunctionExpr function) {
            int total = 1;
            for (Expr argument : function.arguments()) {
                total += baseCost(argument);
            }
            return total;
        }
        return 0;
    }

    private int depth(Expr expression) {
        if (expression instanceof BinaryExpr binary) {
            return 1 + Math.max(depth(binary.left()), depth(binary.right()));
        }
        if (expression instanceof FunctionExpr function) {
            int max = 0;
            for (Expr argument : function.arguments()) {
                max = Math.max(max, depth(argument));
            }
            return 1 + max;
        }
        return 0;
    }

    private int largeCoefficientPenalty(Expr expression) {
        if (expression instanceof NumberExpr number) {
            double absValue = Math.abs(number.value());
            return absValue > LARGE_COEFFICIENT_THRESHOLD ? 2 : 0;
        }
        if (expression instanceof BinaryExpr binary) {
            return largeCoefficientPenalty(binary.left()) + largeCoefficientPenalty(binary.right());
        }
        if (expression instanceof FunctionExpr function) {
            int total = 0;
            for (Expr argument : function.arguments()) {
                total += largeCoefficientPenalty(argument);
            }
            return total;
        }
        return 0;
    }

    private int divisionPenalty(Expr expression) {
        if (expression instanceof BinaryExpr binary) {
            int self = binary.operator() == BinaryOperator.DIV ? 3 : 0;
            return self + divisionPenalty(binary.left()) + divisionPenalty(binary.right());
        }
        if (expression instanceof FunctionExpr function) {
            int total = 0;
            for (Expr argument : function.arguments()) {
                total += divisionPenalty(argument);
            }
            return total;
        }
        return 0;
    }

    private int advancedFunctionPenalty(Expr expression) {
        if (expression instanceof FunctionExpr function) {
            int self = switch (function.name()) {
                case "log", "ln", "exp", "sin", "cos", "tan" -> 2;
                default -> 0;
            };
            int total = self;
            for (Expr argument : function.arguments()) {
                total += advancedFunctionPenalty(argument);
            }
            return total;
        }
        if (expression instanceof BinaryExpr binary) {
            return advancedFunctionPenalty(binary.left()) + advancedFunctionPenalty(binary.right());
        }
        return 0;
    }
}
