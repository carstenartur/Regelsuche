package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionParser;

public final class FactoredProductAstPredicate {
    private static final ExpressionParser PARSER = new ExpressionParser();

    private FactoredProductAstPredicate() {
    }

    public static boolean containsFactoredProduct(String expression) {
        try {
            Expr root = PARSER.parse(new InputRequest(InputType.TERM, expression)).terms().getFirst();
            return containsFactoredProduct(root);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean containsFactoredProduct(Expr expression) {
        if (expression instanceof BinaryExpr binary) {
            if (binary.operator() == BinaryOperator.MUL
                && (isAdditive(binary.left()) || isAdditive(binary.right()))) {
                return true;
            }
            return containsFactoredProduct(binary.left()) || containsFactoredProduct(binary.right());
        }
        if (expression instanceof FunctionExpr function) {
            return function.arguments().stream().anyMatch(FactoredProductAstPredicate::containsFactoredProduct);
        }
        return false;
    }

    private static boolean isAdditive(Expr expression) {
        return expression instanceof BinaryExpr binary
            && (binary.operator() == BinaryOperator.ADD || binary.operator() == BinaryOperator.SUB);
    }
}
