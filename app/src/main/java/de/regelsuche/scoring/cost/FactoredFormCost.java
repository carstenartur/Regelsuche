package de.regelsuche.scoring.cost;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.scoring.ExpressionScore;

/**
 * Rewards factored forms over expanded polynomials.
 *
 * <p>Every multiplication of two non-trivial sub-expressions counts as a
 * factorization win (small bonus), every top-level addition counts as a
 * penalty. So {@code (x+1)*(x+2)} beats {@code x^2 + 3*x + 2}, and a
 * single-term power like {@code x^2} is cheaper still.</p>
 */
public final class FactoredFormCost implements CostModel {

    @Override
    public int cost(String expression, Expr parsedAst, ExpressionScore score) {
        if (parsedAst == null) {
            // Fall back to operator count without structural knowledge.
            return Math.max(0, score.operatorCount());
        }
        return baseCost(parsedAst) + topLevelAdditionPenalty(parsedAst) - factorizationBonus(parsedAst);
    }

    @Override
    public String id() {
        return "factored-form";
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

    /** Each top-level summand beyond the first adds friction. */
    private int topLevelAdditionPenalty(Expr expression) {
        int summands = countTopLevelSummands(expression);
        return Math.max(0, summands - 1) * 2;
    }

    private int countTopLevelSummands(Expr expression) {
        if (expression instanceof BinaryExpr binary
            && (binary.operator() == BinaryOperator.ADD || binary.operator() == BinaryOperator.SUB)) {
            return countTopLevelSummands(binary.left()) + countTopLevelSummands(binary.right());
        }
        return 1;
    }

    /**
     * Reward multiplications and powers between non-trivial sub-expressions
     * (i.e. structures that look like factors, not bare variables or
     * coefficients).
     */
    private int factorizationBonus(Expr expression) {
        int bonus = 0;
        if (expression instanceof BinaryExpr binary) {
            if (binary.operator() == BinaryOperator.MUL
                && isNonTrivial(binary.left()) && isNonTrivial(binary.right())) {
                bonus += 3;
            }
            if (binary.operator() == BinaryOperator.POW
                && isNonTrivial(binary.left())
                && binary.right() instanceof NumberExpr number
                && number.value() >= 2) {
                bonus += 2;
            }
            bonus += factorizationBonus(binary.left());
            bonus += factorizationBonus(binary.right());
        }
        if (expression instanceof FunctionExpr function) {
            for (Expr argument : function.arguments()) {
                bonus += factorizationBonus(argument);
            }
        }
        return bonus;
    }

    private boolean isNonTrivial(Expr expression) {
        if (expression instanceof NumberExpr || expression instanceof VariableExpr) {
            return false;
        }
        return true;
    }
}
