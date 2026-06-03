package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Detects a repeated multiplicative subexpression and factors it out of a sum or difference. */
public class RepeatedSubexpressionFactorizationHypothesisOperator implements HypothesisOperator {
    public static final String RULE_ID = "hypothesis_repeated_subexpression_factorization";

    private final ExpressionParser parser = new ExpressionParser();
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();

    @Override
    public List<Transformation> generateCandidates(String expression) {
        Expr root;
        try {
            root = parser.parse(new InputRequest(InputType.TERM, expression)).terms().getFirst();
        } catch (IllegalArgumentException exception) {
            return List.of();
        }
        if (!(root instanceof BinaryExpr binary)
            || (binary.operator() != BinaryOperator.ADD && binary.operator() != BinaryOperator.SUB)) {
            return List.of();
        }
        CommonFactor common = commonFactor(binary.left(), binary.right());
        if (common == null) {
            return List.of();
        }
        Expr grouped = new BinaryExpr(common.leftRemainder(), binary.operator(), common.rightRemainder());
        Expr factored = new BinaryExpr(common.factor(), BinaryOperator.MUL, grouped);
        String formattedInput = ExpressionFormatter.format(root);
        String formatted = ExpressionFormatter.format(factored);
        if (formatted.equals(formattedInput)) {
            return List.of();
        }
        return List.of(new ScoredCandidate(
                canonicalizer.astNodeCount(formatted),
                new Transformation(
                    RULE_ID,
                    formatted,
                    RewriteKind.NORMALIZE,
                    true,
                    -1,
                    true,
                    RULE_ID + ":" + canonicalizer.stableHash(formattedInput) + "->" + canonicalizer.stableHash(formatted)
                )))
            .stream()
            .sorted(Comparator.comparingInt(ScoredCandidate::score)
                .thenComparing(candidate -> candidate.transformation().transformedExpression()))
            .map(ScoredCandidate::transformation)
            .toList();
    }

    private CommonFactor commonFactor(Expr left, Expr right) {
        List<Expr> leftFactors = flattenMultiplication(left);
        List<Expr> rightFactors = flattenMultiplication(right);
        Map<String, Expr> rightByHash = new LinkedHashMap<>();
        for (Expr factor : rightFactors) {
            if (factor instanceof NumberExpr) {
                continue;
            }
            rightByHash.putIfAbsent(hash(factor), factor);
        }
        Expr selected = null;
        for (Expr factor : leftFactors) {
            if (factor instanceof NumberExpr) {
                continue;
            }
            Expr candidate = rightByHash.get(hash(factor));
            if (candidate != null && (selected == null || betterFactor(factor, selected))) {
                selected = factor;
            }
        }
        if (selected == null) {
            return null;
        }
        Expr factor = selected;
        Expr leftRemainder = buildProduct(removeFirstMatch(leftFactors, factor));
        Expr rightRemainder = buildProduct(removeFirstMatch(rightFactors, factor));
        return new CommonFactor(factor, leftRemainder, rightRemainder);
    }

    private boolean betterFactor(Expr candidate, Expr selected) {
        int candidateSize = canonicalizer.astNodeCount(ExpressionFormatter.format(candidate));
        int selectedSize = canonicalizer.astNodeCount(ExpressionFormatter.format(selected));
        if (candidateSize != selectedSize) {
            return candidateSize > selectedSize;
        }
        return ExpressionFormatter.format(candidate).compareTo(ExpressionFormatter.format(selected)) < 0;
    }

    private List<Expr> flattenMultiplication(Expr expression) {
        if (expression instanceof BinaryExpr binary && binary.operator() == BinaryOperator.MUL) {
            List<Expr> result = new ArrayList<>();
            result.addAll(flattenMultiplication(binary.left()));
            result.addAll(flattenMultiplication(binary.right()));
            return result;
        }
        return List.of(expression);
    }

    private List<Expr> removeFirstMatch(List<Expr> factors, Expr target) {
        List<Expr> result = new ArrayList<>();
        boolean removed = false;
        String targetHash = hash(target);
        for (Expr factor : factors) {
            if (!removed && hash(factor).equals(targetHash)) {
                removed = true;
                continue;
            }
            result.add(factor);
        }
        return result;
    }

    private Expr buildProduct(List<Expr> factors) {
        if (factors.isEmpty()) {
            return new NumberExpr(1);
        }
        Expr result = factors.getFirst();
        for (int index = 1; index < factors.size(); index++) {
            result = new BinaryExpr(result, BinaryOperator.MUL, factors.get(index));
        }
        return result;
    }

    private String hash(Expr expression) {
        return canonicalizer.stableHash(ExpressionFormatter.format(expression));
    }

    private record CommonFactor(Expr factor, Expr leftRemainder, Expr rightRemainder) {
    }

    private record ScoredCandidate(int score, Transformation transformation) {
    }
}
