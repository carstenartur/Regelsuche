package de.regelsuche.scoring.cost;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.scoring.ExpressionScore;

/**
 * Penalises operations that are known to amplify floating-point error:
 * subtraction of similar quantities (catastrophic cancellation), division
 * by potentially small numbers, and constant powers above three computed via
 * repeated multiplication of large coefficients.
 *
 * <p>The model is intentionally heuristic — it operates on the symbolic
 * AST rather than IEEE-754 — but it captures the textbook reasoning that
 * Horner-form is preferred over the expanded polynomial and that
 * {@code (a - b)/c} is suspicious when {@code a} and {@code b} look
 * structurally similar.</p>
 */
public final class NumericStabilityCost implements CostModel {

    @Override
    public int cost(String expression, Expr parsedAst, ExpressionScore score) {
        if (parsedAst == null) {
            return boundedCost((long) score.operatorCount() + score.nestingDepth());
        }
        return boundedCost((long) baseCost(parsedAst) + instabilityPenalty(parsedAst));
    }

    @Override
    public String id() {
        return "numeric-stability";
    }

    private int baseCost(Expr expression) {
        if (expression instanceof BinaryExpr binary) {
            return boundedCost(1L + baseCost(binary.left()) + baseCost(binary.right()));
        }
        if (expression instanceof FunctionExpr function) {
            long total = 1;
            for (Expr argument : function.arguments()) {
                total += baseCost(argument);
            }
            return boundedCost(total);
        }
        return 0;
    }

    private int instabilityPenalty(Expr expression) {
        long penalty = 0;
        if (expression instanceof BinaryExpr binary) {
            switch (binary.operator()) {
                case SUB -> {
                    // catastrophic cancellation: a - b with similarly-shaped
                    // operands or both numeric is suspect.
                    if (binary.left().equals(binary.right())) {
                        penalty += 6;
                    } else if (binary.left() instanceof NumberExpr && binary.right() instanceof NumberExpr) {
                        penalty += 2;
                    }
                }
                case DIV -> {
                    // division by a small or potentially zero denominator
                    if (binary.right() instanceof NumberExpr number && number.value().abs().compareTo(de.regelsuche.scalar.ExactRational.ONE) < 0) {
                        penalty += 5;
                    } else if (!(binary.right() instanceof NumberExpr)) {
                        penalty += 2;
                    }
                }
                case POW -> {
                    // High constant powers expanded raise instability vs. Horner
                    if (binary.right() instanceof NumberExpr number) {
                        penalty += powerPenalty(number.value());
                    }
                }
                default -> {
                    /* ADD, MUL: no extra penalty */
                }
            }
            penalty += instabilityPenalty(binary.left());
            penalty += instabilityPenalty(binary.right());
        } else if (expression instanceof FunctionExpr function) {
            for (Expr argument : function.arguments()) {
                penalty += instabilityPenalty(argument);
            }
        }
        return boundedCost(penalty);
    }

    private static int powerPenalty(ExactRational exponent) {
        ExactRational excess = exponent.subtract(ExactRational.integer(3));
        if (excess.signum() <= 0) {
            return 0;
        }
        if (excess.compareTo(ExactRational.integer(Integer.MAX_VALUE)) >= 0) {
            return Integer.MAX_VALUE;
        }
        return excess.numerator().divide(excess.denominator()).intValueExact();
    }

    private static int boundedCost(long value) {
        return (int) Math.clamp(value, 0, Integer.MAX_VALUE);
    }
}
