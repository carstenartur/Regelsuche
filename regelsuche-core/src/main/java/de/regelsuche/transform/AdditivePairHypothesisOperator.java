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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Applies a root-oriented learned operator to every positive term pair of an
 * additive expression.
 *
 * <p>Additive parentheses are deliberately not used as persistent grouping:
 * the formatter may omit redundant grouping and the parser then rebuilds a
 * left-associated tree. This adapter therefore flattens addition and
 * subtraction into signed terms, selects two positive terms, delegates their
 * synthetic sum, and reinserts the delegated result while preserving every
 * other signed term.</p>
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
        if (expression == null || expression.isBlank()
                || maxCandidates == 0) {
            return List.of();
        }
        Expr root = parseTerm(expression);
        return root == null ? List.of() : generateCandidates(root);
    }

    private List<Transformation> generateCandidates(Expr root) {
        List<SignedTerm> terms = signedTerms(root);
        List<TermPair> pairs = positivePairs(terms);
        if (pairs.isEmpty()) {
            return List.of();
        }

        String formattedSource = ExpressionFormatter.format(root);
        Map<String, Transformation> retained = new LinkedHashMap<>();
        for (TermPair pair : pairs) {
            appendDelegatedCandidates(
                terms,
                pair,
                formattedSource,
                retained);
            if (retained.size() >= maxCandidates) {
                break;
            }
        }
        return List.copyOf(retained.values());
    }

    private void appendDelegatedCandidates(
        List<SignedTerm> terms,
        TermPair pair,
        String formattedSource,
        Map<String, Transformation> retained
    ) {
        Expr selectedPair = selectedPair(terms, pair);
        for (Transformation candidate : delegatedCandidates(selectedPair)) {
            retainCandidate(
                terms,
                pair,
                formattedSource,
                selectedPair,
                candidate,
                retained);
            if (retained.size() >= maxCandidates) {
                return;
            }
        }
    }

    private List<Transformation> delegatedCandidates(Expr selectedPair) {
        List<Transformation> generated = delegate.generateCandidates(
            ExpressionFormatter.format(selectedPair));
        return generated == null ? List.of() : generated;
    }

    private void retainCandidate(
        List<SignedTerm> terms,
        TermPair pair,
        String formattedSource,
        Expr selectedPair,
        Transformation candidate,
        Map<String, Transformation> retained
    ) {
        if (candidate == null) {
            return;
        }
        Expr replacement = parseTerm(candidate.transformedExpression());
        if (replacement == null || replacement.equals(selectedPair)) {
            return;
        }

        String transformed = ExpressionFormatter.format(
            rebuild(terms, pair.left(), pair.right(), replacement));
        if (transformed.equals(formattedSource)) {
            return;
        }

        String key = applicationKey(
            candidate,
            formattedSource,
            pair.left(),
            pair.right(),
            transformed);
        retained.putIfAbsent(key, retained(candidate, transformed, key));
    }

    private static Transformation retained(
        Transformation candidate,
        String transformed,
        String key
    ) {
        return new Transformation(
            candidate.rule(),
            transformed,
            candidate.kind(),
            candidate.mayIncreaseComplexity(),
            candidate.estimatedCostDelta(),
            candidate.equivalencePreservingByConstruction(),
            key,
            candidate.assumptions(),
            candidate.packId(),
            candidate.license(),
            candidate.primitiveRuleIds());
    }

    private Expr parseTerm(String expression) {
        try {
            return parser.parseTerm(expression);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static Expr selectedPair(
        List<SignedTerm> terms,
        TermPair pair
    ) {
        return new BinaryExpr(
            terms.get(pair.left()).expression(),
            BinaryOperator.ADD,
            terms.get(pair.right()).expression());
    }

    private static List<TermPair> positivePairs(List<SignedTerm> terms) {
        List<Integer> positive = new ArrayList<>();
        for (int index = 0; index < terms.size(); index++) {
            if (terms.get(index).positive()) {
                positive.add(index);
            }
        }

        List<TermPair> pairs = new ArrayList<>();
        for (int left = 0; left < positive.size(); left++) {
            for (int right = left + 1; right < positive.size(); right++) {
                pairs.add(new TermPair(
                    positive.get(left),
                    positive.get(right)));
            }
        }
        return List.copyOf(pairs);
    }

    private static List<SignedTerm> signedTerms(Expr root) {
        List<SignedTerm> terms = new ArrayList<>();
        collect(root, 1, terms);
        return List.copyOf(terms);
    }

    private static void collect(
        Expr expression,
        int sign,
        List<SignedTerm> terms
    ) {
        if (expression instanceof BinaryExpr binary
                && binary.operator() == BinaryOperator.ADD) {
            collect(binary.left(), sign, terms);
            collect(binary.right(), sign, terms);
            return;
        }
        if (expression instanceof BinaryExpr binary
                && binary.operator() == BinaryOperator.SUB) {
            collectSubtraction(binary, sign, terms);
            return;
        }
        terms.add(new SignedTerm(sign, expression));
    }

    private static void collectSubtraction(
        BinaryExpr subtraction,
        int sign,
        List<SignedTerm> terms
    ) {
        if (isZero(subtraction.left())) {
            collect(subtraction.right(), -sign, terms);
            return;
        }
        collect(subtraction.left(), sign, terms);
        collect(subtraction.right(), -sign, terms);
    }

    private static boolean isZero(Expr expression) {
        return expression instanceof NumberExpr number
            && Double.compare(number.value(), 0.0) == 0;
    }

    private static Expr rebuild(
        List<SignedTerm> source,
        int left,
        int right,
        Expr replacement
    ) {
        List<SignedTerm> rewritten = replacedTerms(
            source,
            left,
            right,
            replacement);
        Expr result = firstTerm(rewritten.getFirst());
        for (int index = 1; index < rewritten.size(); index++) {
            result = appendTerm(result, rewritten.get(index));
        }
        return result;
    }

    private static List<SignedTerm> replacedTerms(
        List<SignedTerm> source,
        int left,
        int right,
        Expr replacement
    ) {
        List<SignedTerm> rewritten = new ArrayList<>();
        for (int index = 0; index < source.size(); index++) {
            if (index == left) {
                rewritten.add(new SignedTerm(1, replacement));
            } else if (index != right) {
                rewritten.add(source.get(index));
            }
        }
        return rewritten;
    }

    private static Expr firstTerm(SignedTerm first) {
        return first.positive()
            ? first.expression()
            : new BinaryExpr(
                new NumberExpr(0),
                BinaryOperator.SUB,
                first.expression());
    }

    private static Expr appendTerm(Expr result, SignedTerm term) {
        return new BinaryExpr(
            result,
            term.positive() ? BinaryOperator.ADD : BinaryOperator.SUB,
            term.expression());
    }

    private static String applicationKey(
        Transformation delegated,
        String source,
        int left,
        int right,
        String transformed
    ) {
        return "additive-pair-v1:"
            + delegated.rule()
            + ":pair-" + left + "-" + right
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

    private record TermPair(int left, int right) {
        private TermPair {
            if (left < 0 || right <= left) {
                throw new IllegalArgumentException("invalid additive term pair");
            }
        }
    }

    private record SignedTerm(int sign, Expr expression) {
        private SignedTerm {
            if (sign != -1 && sign != 1) {
                throw new IllegalArgumentException("sign must be -1 or 1");
            }
            expression = Objects.requireNonNull(expression, "expression");
        }

        private boolean positive() {
            return sign > 0;
        }
    }
}
