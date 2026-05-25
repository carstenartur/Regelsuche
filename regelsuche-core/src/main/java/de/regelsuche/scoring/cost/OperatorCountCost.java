package de.regelsuche.scoring.cost;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.scoring.ExpressionScore;

/**
 * Default cost model: counts AST operator nodes. Matches the implicit
 * historical behaviour of the search heuristic, so {@link
 * TransformationGoal#SIMPLIFY SIMPLIFY} preserves the pre-PR-3 search
 * order.
 */
public final class OperatorCountCost implements CostModel {

    @Override
    public int cost(String expression, Expr parsedAst, ExpressionScore score) {
        if (parsedAst == null) {
            return Math.max(0, score.operatorCount());
        }
        return countOperators(parsedAst);
    }

    @Override
    public String id() {
        return "operator-count";
    }

    private int countOperators(Expr expression) {
        if (expression instanceof BinaryExpr binary) {
            return 1 + countOperators(binary.left()) + countOperators(binary.right());
        }
        if (expression instanceof FunctionExpr function) {
            int total = 1;
            for (Expr argument : function.arguments()) {
                total += countOperators(argument);
            }
            return total;
        }
        return 0;
    }
}
