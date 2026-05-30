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
import java.util.Comparator;
import java.util.List;

/** Conservative conjugate-rationalization operator for {@code 1 / (sqrt(u) ± c)}. */
public class RationalizationHypothesisOperator implements HypothesisOperator {
    public static final String RULE_ID = "hypothesis_rationalize_denominator";
    private static final int DEFAULT_MAX_CANDIDATES = 4;

    private final ExpressionParser parser = new ExpressionParser();
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
    private final int maxCandidates;

    public RationalizationHypothesisOperator() {
        this(DEFAULT_MAX_CANDIDATES);
    }

    public RationalizationHypothesisOperator(int maxCandidates) {
        this.maxCandidates = Math.max(0, maxCandidates);
    }

    @Override
    public List<Transformation> generateCandidates(String expression) {
        Expr root;
        try {
            root = parser.parse(new InputRequest(InputType.TERM, expression)).terms().getFirst();
        } catch (IllegalArgumentException exception) {
            return List.of();
        }
        if (!(root instanceof BinaryExpr division) || division.operator() != BinaryOperator.DIV || !isOne(division.left())) {
            return List.of();
        }
        RadicalDenominator denominator = radicalDenominator(division.right());
        if (denominator == null) {
            return List.of();
        }
        Expr conjugateNumerator = new BinaryExpr(
            denominator.sqrt(),
            denominator.sign() == BinaryOperator.ADD ? BinaryOperator.SUB : BinaryOperator.ADD,
            new NumberExpr(denominator.constant())
        );
        Expr rationalizedDenominator = new BinaryExpr(
            denominator.radicand(),
            BinaryOperator.SUB,
            new NumberExpr(denominator.constant() * denominator.constant())
        );
        Expr transformed = new BinaryExpr(conjugateNumerator, BinaryOperator.DIV, rationalizedDenominator);
        String formattedInput = ExpressionFormatter.format(root);
        String formatted = ExpressionFormatter.format(transformed);
        String key = canonicalizer.stableHash(formatted);
        Transformation transformation = new Transformation(
            RULE_ID,
            formatted,
            RewriteKind.NORMALIZE,
            false,
            -1,
            true,
            RULE_ID + ":" + canonicalizer.stableHash(formattedInput) + "->" + key
                + ";assumption:" + assumption(denominator),
            List.of(assumption(denominator))
        );
        return List.of(transformation).stream()
            .sorted(Comparator.comparing(Transformation::transformedExpression))
            .limit(maxCandidates)
            .toList();
    }

    private RadicalDenominator radicalDenominator(Expr expression) {
        if (!(expression instanceof BinaryExpr binary)
            || (binary.operator() != BinaryOperator.ADD && binary.operator() != BinaryOperator.SUB)) {
            return null;
        }
        Radical left = radical(binary.left());
        if (left != null && binary.right() instanceof NumberExpr constant && constant.value() > 0) {
            return new RadicalDenominator(left.sqrt(), left.radicand(), constant.value(), binary.operator());
        }
        Radical right = radical(binary.right());
        if (right != null
            && binary.operator() == BinaryOperator.ADD
            && binary.left() instanceof NumberExpr constant
            && constant.value() > 0) {
            return new RadicalDenominator(right.sqrt(), right.radicand(), constant.value(), BinaryOperator.ADD);
        }
        return null;
    }

    private Radical radical(Expr expression) {
        if (expression instanceof FunctionExpr function
            && "sqrt".equals(function.name())
            && function.arguments().size() == 1) {
            return new Radical(function, function.argument());
        }
        return null;
    }

    private String assumption(RadicalDenominator denominator) {
        String radicand = ExpressionFormatter.format(denominator.radicand());
        double square = denominator.constant() * denominator.constant();
        if (Math.rint(square) == square) {
            return radicand + " != " + (long) square;
        }
        return radicand + " != " + square;
    }

    private boolean isOne(Expr expression) {
        return expression instanceof NumberExpr number && Double.compare(number.value(), 1.0) == 0;
    }

    private record Radical(Expr sqrt, Expr radicand) {
    }

    private record RadicalDenominator(Expr sqrt, Expr radicand, double constant, BinaryOperator sign) {
    }
}
