package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionParser;

public final class SquareDifferenceAstPredicate {
    private static final ExpressionParser PARSER = new ExpressionParser();

    private SquareDifferenceAstPredicate() {
    }

    public static boolean containsSquareDifference(String expression) {
        try {
            Expr root = PARSER.parse(new InputRequest(InputType.TERM, expression)).terms().getFirst();
            return containsSquareDifference(root);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean containsSquareDifference(Expr expression) {
        if (expression instanceof BinaryExpr binary) {
            if (binary.operator() == BinaryOperator.SUB && isSquare(binary.left()) && isSquare(binary.right())) {
                return true;
            }
            return containsSquareDifference(binary.left()) || containsSquareDifference(binary.right());
        }
        if (expression instanceof FunctionExpr function) {
            return function.arguments().stream().anyMatch(SquareDifferenceAstPredicate::containsSquareDifference);
        }
        return false;
    }

    private static boolean isSquare(Expr expression) {
        return expression instanceof BinaryExpr binary
            && binary.operator() == BinaryOperator.POW
            && binary.right() instanceof NumberExpr exponent
            && Double.compare(exponent.value(), 2.0) == 0;
    }
}
