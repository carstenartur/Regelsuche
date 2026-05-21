package de.regelsuche.scoring.cost;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.scoring.ExpressionScore;

/**
 * Prefers shallow expressions over deeply nested ones. Useful for
 * proof-oriented search where flatter structures are easier to reason
 * about line-by-line.
 */
public final class DepthCost implements CostModel {

    @Override
    public int cost(String expression, Expr parsedAst, ExpressionScore score) {
        if (parsedAst == null) {
            return Math.max(0, score.nestingDepth());
        }
        return depth(parsedAst);
    }

    @Override
    public String id() {
        return "depth";
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
        return 1;
    }
}
