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

/** Conservative together/cancel-style candidates for rational expressions. */
public class RationalNormalizationHypothesisOperator implements HypothesisOperator {
    public static final String RULE_ID = "hypothesis_rational_normalization";

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
        Map<String, Transformation> candidates = new LinkedHashMap<>();
        addSameDenominatorCandidate(root, candidates);
        addCancellationCandidate(root, candidates);
        return candidates.values().stream()
            .sorted(Comparator.comparing(Transformation::transformedExpression))
            .toList();
    }

    private void addSameDenominatorCandidate(Expr root, Map<String, Transformation> candidates) {
        if (!(root instanceof BinaryExpr binary)
            || (binary.operator() != BinaryOperator.ADD && binary.operator() != BinaryOperator.SUB)) {
            return;
        }
        DivisionParts left = division(binary.left());
        DivisionParts right = division(binary.right());
        if (left == null || right == null || !same(left.denominator(), right.denominator())) {
            return;
        }
        Expr numerator = new BinaryExpr(left.numerator(), binary.operator(), right.numerator());
        Expr combined = new BinaryExpr(numerator, BinaryOperator.DIV, left.denominator());
        addCandidate(root, combined, candidates);
    }

    private void addCancellationCandidate(Expr root, Map<String, Transformation> candidates) {
        if (!(root instanceof BinaryExpr binary) || binary.operator() != BinaryOperator.DIV) {
            return;
        }
        List<Expr> numeratorFactors = flattenMultiplication(binary.left());
        List<Expr> denominatorFactors = flattenMultiplication(binary.right());
        Expr common = firstCommonNonNumericFactor(numeratorFactors, denominatorFactors);
        if (common == null) {
            return;
        }
        Expr simplified = new BinaryExpr(
            buildProduct(removeFirstMatch(numeratorFactors, common)),
            BinaryOperator.DIV,
            buildProduct(removeFirstMatch(denominatorFactors, common))
        );
        addCandidate(root, simplified, candidates);
    }

    private DivisionParts division(Expr expression) {
        if (expression instanceof BinaryExpr binary && binary.operator() == BinaryOperator.DIV) {
            return new DivisionParts(binary.left(), binary.right());
        }
        return null;
    }

    private Expr firstCommonNonNumericFactor(List<Expr> left, List<Expr> right) {
        Map<String, Expr> rightByHash = new LinkedHashMap<>();
        for (Expr factor : right) {
            if (!(factor instanceof NumberExpr)) {
                rightByHash.putIfAbsent(hash(factor), factor);
            }
        }
        for (Expr factor : left) {
            if (!(factor instanceof NumberExpr) && rightByHash.containsKey(hash(factor))) {
                return factor;
            }
        }
        return null;
    }

    private void addCandidate(Expr original, Expr candidate, Map<String, Transformation> candidates) {
        String formattedInput = ExpressionFormatter.format(original);
        String formatted = ExpressionFormatter.format(candidate);
        if (formatted.equals(formattedInput)) {
            return;
        }
        candidates.putIfAbsent(canonicalizer.stableHash(formatted), new Transformation(
            RULE_ID,
            formatted,
            RewriteKind.NORMALIZE,
            false,
            -1,
            true,
            RULE_ID + ":" + canonicalizer.stableHash(formattedInput) + "->" + canonicalizer.stableHash(formatted)
        ));
    }

    private boolean same(Expr left, Expr right) {
        return hash(left).equals(hash(right));
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

    private record DivisionParts(Expr numerator, Expr denominator) {
    }
}
