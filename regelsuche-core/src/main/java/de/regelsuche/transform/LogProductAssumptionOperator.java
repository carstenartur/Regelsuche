package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionParser;
import java.util.List;

/** Assumption-aware log product decomposition for positive factors. */
public final class LogProductAssumptionOperator implements HypothesisOperator {
    public static final String RULE_ID = "sympy.log.basic.product_assumption";
    private static final String PACK_ID = "sympy-log-basic";
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
        if (!(root instanceof FunctionExpr log) || !"log".equals(log.name()) || log.arguments().size() != 1) {
            return List.of();
        }
        Expr argument = log.argument();
        if (!(argument instanceof BinaryExpr product) || product.operator() != BinaryOperator.MUL) {
            return List.of();
        }
        String left = ExpressionFormatter.format(product.left());
        String right = ExpressionFormatter.format(product.right());
        if (isUnknown(left) || isUnknown(right)) {
            return List.of();
        }
        String transformed = "log(" + left + ") + log(" + right + ")";
        return List.of(new Transformation(
            RULE_ID,
            transformed,
            RewriteKind.NORMALIZE,
            false,
            -1,
            true,
            RULE_ID + "|source=sympy-derived|pack=" + PACK_ID,
            List.of(left + " > 0", right + " > 0"),
            PACK_ID,
            LICENSE
        ));
    }

    private boolean isUnknown(String symbolExpression) {
        return symbolExpression == null || symbolExpression.isBlank()
            || symbolExpression.toLowerCase(java.util.Locale.ROOT).contains("unknown");
    }
}
