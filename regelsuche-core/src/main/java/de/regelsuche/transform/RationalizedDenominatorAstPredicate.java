package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionParser;

/** Structural predicate for fractions whose denominator no longer contains {@code sqrt(...)}. */
public final class RationalizedDenominatorAstPredicate {
    private static final ExpressionParser PARSER = new ExpressionParser();

    private RationalizedDenominatorAstPredicate() {
    }

    public static boolean hasRationalizedDenominator(String expression) {
        try {
            Expr root = PARSER.parse(new InputRequest(InputType.TERM, expression)).terms().getFirst();
            return hasRationalizedDenominator(root);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean hasRationalizedDenominator(Expr expression) {
        if (expression instanceof BinaryExpr binary && binary.operator() == BinaryOperator.DIV) {
            return containsSqrt(binary.left()) && !containsSqrt(binary.right());
        }
        if (expression instanceof BinaryExpr binary) {
            return hasRationalizedDenominator(binary.left()) || hasRationalizedDenominator(binary.right());
        }
        if (expression instanceof FunctionExpr function) {
            return function.arguments().stream().anyMatch(RationalizedDenominatorAstPredicate::hasRationalizedDenominator);
        }
        return false;
    }

    private static boolean containsSqrt(Expr expression) {
        if (expression instanceof FunctionExpr function) {
            return "sqrt".equals(function.name())
                || function.arguments().stream().anyMatch(RationalizedDenominatorAstPredicate::containsSqrt);
        }
        if (expression instanceof BinaryExpr binary) {
            return containsSqrt(binary.left()) || containsSqrt(binary.right());
        }
        return false;
    }
}
