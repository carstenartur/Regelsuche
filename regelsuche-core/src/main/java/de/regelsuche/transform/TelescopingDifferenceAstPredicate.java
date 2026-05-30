package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;

/** Structural predicate for unit-fraction differences such as {@code 1/u - 1/(u + 1)}. */
public final class TelescopingDifferenceAstPredicate {
    private static final ExpressionParser PARSER = new ExpressionParser();
    private static final ExpressionCanonicalizer CANONICALIZER = new ExpressionCanonicalizer();

    private TelescopingDifferenceAstPredicate() {
    }

    public static boolean containsTelescopingDifference(String expression) {
        try {
            Expr root = PARSER.parse(new InputRequest(InputType.TERM, expression)).terms().getFirst();
            return containsTelescopingDifference(root);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean containsTelescopingDifference(Expr expression) {
        if (expression instanceof BinaryExpr binary) {
            if (binary.operator() == BinaryOperator.SUB
                && unitDenominator(binary.left()) != null
                && isPlusOne(unitDenominator(binary.right()), unitDenominator(binary.left()))) {
                return true;
            }
            return containsTelescopingDifference(binary.left()) || containsTelescopingDifference(binary.right());
        }
        if (expression instanceof FunctionExpr function) {
            return function.arguments().stream().anyMatch(TelescopingDifferenceAstPredicate::containsTelescopingDifference);
        }
        return false;
    }

    private static Expr unitDenominator(Expr expression) {
        if (expression instanceof BinaryExpr binary
            && binary.operator() == BinaryOperator.DIV
            && binary.left() instanceof NumberExpr number
            && Double.compare(number.value(), 1.0) == 0) {
            return binary.right();
        }
        return null;
    }

    private static boolean isPlusOne(Expr candidate, Expr base) {
        if (!(candidate instanceof BinaryExpr binary) || binary.operator() != BinaryOperator.ADD) {
            return false;
        }
        return (isOne(binary.right()) && same(binary.left(), base))
            || (isOne(binary.left()) && same(binary.right(), base));
    }

    private static boolean isOne(Expr expression) {
        return expression instanceof NumberExpr number && Double.compare(number.value(), 1.0) == 0;
    }

    private static boolean same(Expr left, Expr right) {
        return CANONICALIZER.stableHash(ExpressionFormatter.format(left))
            .equals(CANONICALIZER.stableHash(ExpressionFormatter.format(right)));
    }
}
