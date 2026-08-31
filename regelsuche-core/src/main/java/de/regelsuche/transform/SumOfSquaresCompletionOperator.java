package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Generic completion of an explicitly visible sum of two squares. */
public final class SumOfSquaresCompletionOperator
        implements HypothesisOperator {
    public static final String RULE_ID = "complete_sum_of_two_squares";

    private final ExpressionParser parser = new ExpressionParser();

    @Override
    public List<Transformation> generateCandidates(String expression) {
        if (expression == null || expression.isBlank()) {
            return List.of();
        }
        Expr root;
        try {
            root = parser.parse(new InputRequest(InputType.TERM, expression))
                .terms()
                .getFirst();
        } catch (IllegalArgumentException exception) {
            return List.of();
        }
        if (!(root instanceof BinaryExpr sum)
                || sum.operator() != BinaryOperator.ADD) {
            return List.of();
        }
        Expr leftBase = explicitSquareBase(sum.left());
        Expr rightBase = explicitSquareBase(sum.right());
        if (leftBase == null || rightBase == null) {
            return List.of();
        }
        Expr result = completed(leftBase, rightBase);
        String transformed = ExpressionFormatter.format(result);
        String source = ExpressionFormatter.format(root);
        return List.of(new Transformation(
            RULE_ID,
            transformed,
            RewriteKind.NORMALIZE,
            true,
            4,
            true,
            RULE_ID + ":" + syntaxHash(source) + "->"
                + syntaxHash(transformed)));
    }

    private static Expr completed(Expr leftBase, Expr rightBase) {
        Expr square = new BinaryExpr(
            new BinaryExpr(leftBase, BinaryOperator.ADD, rightBase),
            BinaryOperator.POW,
            new NumberExpr(2));
        Expr crossTerm = new BinaryExpr(
            new BinaryExpr(new NumberExpr(2), BinaryOperator.MUL, leftBase),
            BinaryOperator.MUL,
            rightBase);
        return new BinaryExpr(square, BinaryOperator.SUB, crossTerm);
    }

    private static Expr explicitSquareBase(Expr expression) {
        if (expression instanceof BinaryExpr power
                && power.operator() == BinaryOperator.POW
                && power.right() instanceof NumberExpr exponent
                && exponent.value() == 2) {
            return power.left();
        }
        return null;
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
