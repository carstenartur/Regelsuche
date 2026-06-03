package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.List;
import java.util.Locale;

/** Assumption-aware simplification rules for roots and powers. */
public final class PowerRootAssumptionRules implements HypothesisOperator {
    public static final String RULE_ID = "sympy.power.basic.sqrt_square_nonnegative";
    private static final String PACK_ID = "sympy-power-basic";
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
        if (!(root instanceof FunctionExpr sqrt) || !"sqrt".equals(sqrt.name()) || sqrt.arguments().size() != 1) {
            return List.of();
        }
        Expr argument = sqrt.argument();
        if (!(argument instanceof BinaryExpr power) || power.operator() != BinaryOperator.POW) {
            return List.of();
        }
        if (!(power.right() instanceof NumberExpr exponent) || Double.compare(exponent.value(), 2.0) != 0) {
            return List.of();
        }
        String base = ExpressionFormatter.format(power.left());
        if (base.isBlank() || base.toLowerCase(Locale.ROOT).contains("unknown")) {
            return List.of();
        }
        return List.of(new Transformation(
            RULE_ID,
            base,
            RewriteKind.SIMPLIFY,
            false,
            -1,
            true,
            RULE_ID + "|source=sympy-derived|pack=" + PACK_ID,
            List.of(base + " >= 0"),
            PACK_ID,
            LICENSE
        ));
    }
}
