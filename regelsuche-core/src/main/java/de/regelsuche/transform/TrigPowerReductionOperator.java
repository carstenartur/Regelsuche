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

/** SymPy-derived conservative trig power-reduction identities. */
public final class TrigPowerReductionOperator implements HypothesisOperator {
    public static final String RULE_ID = "sympy.trig.basic.power_reduction";
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
        if (!(root instanceof BinaryExpr subtract) || subtract.operator() != BinaryOperator.SUB || !isOne(subtract.left())) {
            return List.of();
        }
        if (isSquaredTrig(subtract.right(), "sin")) {
            return List.of(candidate("cos(x) ^ 2"));
        }
        if (isSquaredTrig(subtract.right(), "cos")) {
            return List.of(candidate("sin(x) ^ 2"));
        }
        return List.of();
    }

    private Transformation candidate(String transformedExpression) {
        return new Transformation(
            RULE_ID,
            transformedExpression,
            RewriteKind.SIMPLIFY,
            false,
            -2,
            true,
            RULE_ID + "|source=sympy-derived|pack=" + PACK_ID + "|to=" + transformedExpression,
            List.of(),
            PACK_ID,
            LICENSE
        );
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
