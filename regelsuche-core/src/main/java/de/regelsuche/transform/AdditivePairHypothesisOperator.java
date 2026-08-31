package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Applies a root-oriented hypothesis operator to every unordered pair of terms
 * in one associative top-level sum.
 *
 * <p>The adapter is deliberately structural rather than target-aware. It
 * flattens only addition, enumerates pairs in deterministic index order, tries
 * both operand orientations, replaces exactly one selected pair, and preserves
 * all delegated rule and provenance metadata. Subtraction is left opaque so no
 * sign convention or additional assumption is introduced implicitly.</p>
 */
public final class AdditivePairHypothesisOperator
        implements HypothesisOperator {
    private static final int DEFAULT_MAX_CANDIDATES = 64;

    private final HypothesisOperator delegate;
    private final int maxCandidates;
    private final ExpressionParser parser = new ExpressionParser();

    public AdditivePairHypothesisOperator(HypothesisOperator delegate) {
        this(delegate, DEFAULT_MAX_CANDIDATES);
    }

    public AdditivePairHypothesisOperator(
        HypothesisOperator delegate,
        int maxCandidates
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.maxCandidates = Math.max(0, maxCandidates);
    }

    @Override
    public List<Transformation> generateCandidates(String expression) {
        Expr root = parse(expression);
        if (root == null || maxCandidates == 0) {
            return List.of();
        }

        List<Expr> terms = flattenAddition(root);
        if (terms.size() < 2) {
            return List.of();
        }

        String source = ExpressionFormatter.format(root);
        Map<String, Transformation> retainedByExpression =
            new LinkedHashMap<>();
        pairs:
        for (int leftIndex = 0; leftIndex < terms.size(); leftIndex++) {
            for (int rightIndex = leftIndex + 1;
                    rightIndex < terms.size();
                    rightIndex++) {
                addPairOrientation(
                    terms,
                    source,
                    retainedByExpression,
                    leftIndex,
                    rightIndex,
                    false);
                if (retainedByExpression.size() >= maxCandidates) {
                    break pairs;
                }
                addPairOrientation(
                    terms,
                    source,
                    retainedByExpression,
                    leftIndex,
                    rightIndex,
                    true);
                if (retainedByExpression.size() >= maxCandidates) {
                    break pairs;
                }
            }
        }
        return List.copyOf(retainedByExpression.values());
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

    private void addPairOrientation(
        List<Expr> terms,
        String source,
        Map<String, Transformation> retainedByExpression,
        int leftIndex,
        int rightIndex,
        boolean reversed
    ) {
        Expr first = reversed
            ? terms.get(rightIndex)
            : terms.get(leftIndex);
        Expr second = reversed
            ? terms.get(leftIndex)
            : terms.get(rightIndex);
        Expr selectedPair = new BinaryExpr(
            first,
            BinaryOperator.ADD,
            second);
        List<Transformation> delegatedCandidates =
            delegate.generateCandidates(
                ExpressionFormatter.format(selectedPair));
        if (delegatedCandidates == null) {
            return;
        }
        for (Transformation delegated : delegatedCandidates) {
            if (retainedByExpression.size() >= maxCandidates) {
                return;
            }
            Transformation rewritten = rewriteCandidate(
                terms,
                source,
                selectedPair,
                delegated,
                leftIndex,
                rightIndex,
                reversed);
            if (rewritten != null) {
                retainedByExpression.putIfAbsent(
                    rewritten.transformedExpression(),
                    rewritten);
            }
        }
    }

    private Transformation rewriteCandidate(
        List<Expr> terms,
        String source,
        Expr selectedPair,
        Transformation delegated,
        int leftIndex,
        int rightIndex,
        boolean reversed
    ) {
        if (delegated == null) {
            return null;
        }
        Expr replacement = parse(delegated.transformedExpression());
        if (replacement == null || replacement.equals(selectedPair)) {
            return null;
        }

        Expr rewritten = replacePair(
            terms,
            leftIndex,
            rightIndex,
            replacement);
        String transformed = ExpressionFormatter.format(rewritten);
        if (transformed.equals(source)) {
            return null;
        }
        String key = applicationKey(
            delegated,
            source,
            transformed,
            leftIndex,
            rightIndex,
            reversed);
        return new Transformation(
            delegated.rule(),
            transformed,
            delegated.kind(),
            delegated.mayIncreaseComplexity(),
            delegated.estimatedCostDelta(),
            delegated.equivalencePreservingByConstruction(),
            key,
            delegated.assumptions(),
            delegated.packId(),
            delegated.license(),
            delegated.primitiveRuleIds());
    }

    private static List<Expr> flattenAddition(Expr expression) {
        List<Expr> terms = new ArrayList<>();
        collectAddition(expression, terms);
        return List.copyOf(terms);
    }

    private static void collectAddition(
        Expr expression,
        List<Expr> terms
    ) {
        if (expression instanceof BinaryExpr binary
                && binary.operator() == BinaryOperator.ADD) {
            collectAddition(binary.left(), terms);
            collectAddition(binary.right(), terms);
        } else {
            terms.add(expression);
        }
    }

    private static Expr replacePair(
        List<Expr> terms,
        int leftIndex,
        int rightIndex,
        Expr replacement
    ) {
        List<Expr> rewritten = new ArrayList<>(terms.size() - 1);
        for (int index = 0; index < terms.size(); index++) {
            if (index == leftIndex) {
                rewritten.add(replacement);
            } else if (index != rightIndex) {
                rewritten.add(terms.get(index));
            }
        }
        return buildAddition(rewritten);
    }

    private static Expr buildAddition(List<Expr> terms) {
        Expr result = terms.getFirst();
        for (int index = 1; index < terms.size(); index++) {
            result = new BinaryExpr(
                result,
                BinaryOperator.ADD,
                terms.get(index));
        }
        return result;
    }

    private static String applicationKey(
        Transformation delegated,
        String source,
        String transformed,
        int leftIndex,
        int rightIndex,
        boolean reversed
    ) {
        return "additive-pair-v1:"
            + delegated.rule()
            + ":" + leftIndex + "." + rightIndex
            + ":" + (reversed ? "reverse" : "forward")
            + ":delegate-" + digest(delegated.applicationKey())
            + ":transition-" + digest(source + "\u0000" + transformed);
    }

    private static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
