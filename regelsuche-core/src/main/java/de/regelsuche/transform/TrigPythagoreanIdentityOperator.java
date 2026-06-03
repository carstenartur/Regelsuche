package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionParser;
import java.util.List;

/** SymPy-derived trig identities that were blockers in campaign 1. */
public final class TrigPythagoreanIdentityOperator implements HypothesisOperator {
    public static final String RULE_ID = "sympy.trig.basic.pythagorean_identity";
    private static final String PACK_ID = "sympy-trig-basic";
    private static final String LICENSE = "BSD-3-Clause";

    private final ExpressionParser parser = new ExpressionParser();

    @Override
    public List<Transformation> generateCandidates(String expression) {
        Expr root;
        try {
            root = parser.parse(new InputRequest(InputType.TERM, expression)).terms().getFirst();
        } catch (IllegalArgumentException exception) {
            return List.of();
        }
        if (matchesSinSquaredPlusCosSquared(root)) {
            return List.of(new Transformation(
                RULE_ID,
                "1",
                RewriteKind.SIMPLIFY,
                false,
                -2,
                true,
                RULE_ID + "|source=sympy-derived|pack=" + PACK_ID,
                List.of(),
                PACK_ID,
                LICENSE
            ));
        }
        if (matchesTanSquaredPlusOne(root)) {
            return List.of(new Transformation(
                RULE_ID,
                "sec(x) ^ 2",
                RewriteKind.SIMPLIFY,
                false,
                -2,
                true,
                RULE_ID + "|source=sympy-derived|pack=" + PACK_ID,
                List.of(),
                PACK_ID,
                LICENSE
            ));
        }
        return List.of();
    }

    private boolean matchesSinSquaredPlusCosSquared(Expr expression) {
        if (!(expression instanceof BinaryExpr sum) || sum.operator() != BinaryOperator.ADD) {
            return false;
        }
        return (isSquaredTrig(sum.left(), "sin") && isSquaredTrig(sum.right(), "cos"))
            || (isSquaredTrig(sum.left(), "cos") && isSquaredTrig(sum.right(), "sin"));
    }

    private boolean matchesTanSquaredPlusOne(Expr expression) {
        if (!(expression instanceof BinaryExpr sum) || sum.operator() != BinaryOperator.ADD) {
            return false;
        }
        return (isSquaredTrig(sum.left(), "tan") && isOne(sum.right()))
            || (isSquaredTrig(sum.right(), "tan") && isOne(sum.left()));
    }

    private boolean isSquaredTrig(Expr expression, String functionName) {
        if (!(expression instanceof BinaryExpr power) || power.operator() != BinaryOperator.POW) {
            return false;
        }
        if (!(power.right() instanceof NumberExpr exponent) || Double.compare(exponent.value(), 2.0) != 0) {
            return false;
        }
        if (!(power.left() instanceof FunctionExpr function)) {
            return false;
        }
        return functionName.equals(function.name()) && function.arguments().size() == 1;
    }

    private boolean isOne(Expr expression) {
        return expression instanceof NumberExpr number && Double.compare(number.value(), 1.0) == 0;
    }
}
