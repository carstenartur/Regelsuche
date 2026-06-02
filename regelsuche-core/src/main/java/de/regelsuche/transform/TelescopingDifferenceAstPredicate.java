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

/** Structural predicate for unit-fraction differences such as {@code 1/u - 1/(u + k)} for positive integer k. */
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
                && isPositiveIntegerStep(unitDenominator(binary.right()), unitDenominator(binary.left()))) {
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

    private static boolean isPositiveIntegerStep(Expr candidate, Expr base) {
        AdditiveOffset candidateOffset = additiveOffset(candidate);
        AdditiveOffset baseOffset = additiveOffset(base);
        if (candidateOffset != null
            && baseOffset != null
            && same(candidateOffset.symbolicPart(), baseOffset.symbolicPart())) {
            double step = candidateOffset.offset() - baseOffset.offset();
            if (step >= 1.0 && step == Math.floor(step)) {
                return true;
            }
        }
        return isPositiveIntegerAddition(candidate, base);
    }

    private static boolean isPositiveIntegerAddition(Expr candidate, Expr base) {
        if (!(candidate instanceof BinaryExpr binary) || binary.operator() != BinaryOperator.ADD) {
            return false;
        }
        return (isPositiveInteger(binary.right()) && same(binary.left(), base))
            || (isPositiveInteger(binary.left()) && same(binary.right(), base));
    }

    private static boolean isPositiveInteger(Expr expression) {
        return expression instanceof NumberExpr number
            && number.value() >= 1.0
            && number.value() == Math.floor(number.value());
    }

    private static boolean same(Expr left, Expr right) {
        return CANONICALIZER.stableHash(ExpressionFormatter.format(left))
            .equals(CANONICALIZER.stableHash(ExpressionFormatter.format(right)));
    }

    private static AdditiveOffset additiveOffset(Expr expression) {
        if (expression instanceof NumberExpr number) {
            return new AdditiveOffset(new NumberExpr(0), number.value());
        }
        if (expression instanceof BinaryExpr binary && binary.operator() == BinaryOperator.ADD) {
            if (binary.right() instanceof NumberExpr right) {
                return new AdditiveOffset(binary.left(), right.value());
            }
            if (binary.left() instanceof NumberExpr left) {
                return new AdditiveOffset(binary.right(), left.value());
            }
        }
        return new AdditiveOffset(expression, 0.0);
    }

    private record AdditiveOffset(Expr symbolicPart, double offset) {
    }
}
