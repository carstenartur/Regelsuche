package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionParser;

public final class PerfectSquareAstPredicate {
    private static final ExpressionParser PARSER = new ExpressionParser();

    private PerfectSquareAstPredicate() {
    }

    public static boolean containsPerfectSquare(String expression) {
        try {
            Expr root = PARSER.parse(new InputRequest(InputType.TERM, expression)).terms().getFirst();
            return containsPerfectSquare(root);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static boolean isSquare(Expr expression) {
        return expression instanceof BinaryExpr binary
            && binary.operator() == BinaryOperator.POW
            && binary.right() instanceof NumberExpr exponent
            && Double.compare(exponent.value(), 2.0) == 0;
    }

    private static boolean containsPerfectSquare(Expr expression) {
        if (isSquare(expression)) {
            return true;
        }
        if (expression instanceof BinaryExpr binary) {
            return containsPerfectSquare(binary.left()) || containsPerfectSquare(binary.right());
        }
        if (expression instanceof FunctionExpr function) {
            return function.arguments().stream().anyMatch(PerfectSquareAstPredicate::containsPerfectSquare);
        }
        return false;
    }
}
