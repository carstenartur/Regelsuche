package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Exposes the exact square symmetry {@code X^2 -> (-X)^2} in one direction.
 *
 * <p>The rewrite is representation-only and introduces no assumptions. It is
 * deliberately not emitted for numeric bases or bases already represented as
 * an explicit negation, so repeated application cannot create an immediate
 * two-cycle. Because the explicit negation increases structural complexity,
 * the move is classified as an expansion and participates in the search's
 * expansion budget. Tree-local use is provided by
 * {@link SubtreeHypothesisOperator} rather than being duplicated here.</p>
 */
public final class SquareBaseSignSymmetryOperator
        implements HypothesisOperator {
    public static final String RULE_ID = "square_base_sign_symmetry";

    private final ExpressionParser parser = new ExpressionParser();

    @Override
    public List<Transformation> generateCandidates(String expression) {
        Expr root = parse(expression);
        if (!(root instanceof BinaryExpr power)
                || power.operator() != BinaryOperator.POW
                || !isTwo(power.right())
                || power.left() instanceof NumberExpr
                || isExplicitNegation(power.left())) {
            return List.of();
        }

        Expr negatedBase = new BinaryExpr(
            new NumberExpr(0),
            BinaryOperator.SUB,
            power.left());
        Expr transformedExpression = new BinaryExpr(
            negatedBase,
            BinaryOperator.POW,
            new NumberExpr(2));
        String source = ExpressionFormatter.format(root);
        String transformed =
            ExpressionFormatter.format(transformedExpression);
        return List.of(new Transformation(
            RULE_ID,
            transformed,
            RewriteKind.EXPAND,
            true,
            2,
            true,
            RULE_ID + ":" + syntaxHash(source) + "->"
                + syntaxHash(transformed)));
    }

    private Expr parse(String expression) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        try {
            return parser.parseTerm(expression);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static boolean isTwo(Expr expression) {
        return expression instanceof NumberExpr number
            && Double.compare(number.value(), 2.0) == 0;
    }

    private static boolean isExplicitNegation(Expr expression) {
        return expression instanceof BinaryExpr subtraction
            && subtraction.operator() == BinaryOperator.SUB
            && subtraction.left() instanceof NumberExpr zero
            && Double.compare(zero.value(), 0.0) == 0;
    }

    private static String syntaxHash(String expression) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(expression.getBytes(StandardCharsets.UTF_8));
            return "syntax-v1:" + HexFormat.of().formatHex(hash, 0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
