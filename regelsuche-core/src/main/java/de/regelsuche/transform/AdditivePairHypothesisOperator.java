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

        Expr root;
        try {
            root = parser.parseTerm(expression);
        } catch (IllegalArgumentException exception) {
            return List.of();
        }

        List<SignedTerm> terms = signedTerms(root);
        if (terms.stream().filter(SignedTerm::positive).count() < 2) {
            return List.of();
        }

        String formattedSource = ExpressionFormatter.format(root);
        Map<String, Transformation> retained = new LinkedHashMap<>();
        pairs:
        for (int left = 0; left < terms.size(); left++) {
            if (!terms.get(left).positive()) {
                continue;
            }
            for (int right = left + 1; right < terms.size(); right++) {
                if (!terms.get(right).positive()) {
                    continue;
                }

                Expr selectedPair = new BinaryExpr(
                    terms.get(left).expression(),
                    BinaryOperator.ADD,
                    terms.get(right).expression());
                String pairText = ExpressionFormatter.format(selectedPair);
                List<Transformation> delegated =
                    delegate.generateCandidates(pairText);
                if (delegated == null) {
                    continue;
                }
                for (Transformation candidate : delegated) {
                    if (retained.size() >= maxCandidates) {
                        break pairs;
                    }
                    if (candidate == null) {
                        continue;
                    }

                    Expr replacement;
                    try {
                        replacement = parser.parseTerm(
                            candidate.transformedExpression());
                    } catch (IllegalArgumentException exception) {
                        continue;
                    }
                    if (replacement.equals(selectedPair)) {
                        continue;
                    }

                    Expr rewritten = rebuild(
                        terms,
                        left,
                        right,
                        replacement);
                    String transformed = ExpressionFormatter.format(rewritten);
                    if (transformed.equals(formattedSource)) {
                        continue;
                    }

                    String key = applicationKey(
                        candidate,
                        formattedSource,
                        left,
                        right,
                        transformed);
                    retained.putIfAbsent(
                        key,
                        new Transformation(
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
                            candidate.primitiveRuleIds()));
                }
            }
        }
        return List.copyOf(retained.values());
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
            if (isZero(binary.left())) {
                collect(binary.right(), -sign, terms);
                return;
            }
            collect(binary.left(), sign, terms);
            collect(binary.right(), -sign, terms);
            return;
        }
        terms.add(new SignedTerm(sign, expression));
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
        List<SignedTerm> rewritten = new ArrayList<>();
        for (int index = 0; index < source.size(); index++) {
            if (index == left) {
                rewritten.add(new SignedTerm(1, replacement));
            } else if (index != right) {
                rewritten.add(source.get(index));
            }
        }

        SignedTerm first = rewritten.getFirst();
        Expr result = first.positive()
            ? first.expression()
            : new BinaryExpr(
                new NumberExpr(0),
                BinaryOperator.SUB,
                first.expression());
        for (int index = 1; index < rewritten.size(); index++) {
            SignedTerm term = rewritten.get(index);
            result = new BinaryExpr(
                result,
                term.positive() ? BinaryOperator.ADD : BinaryOperator.SUB,
                term.expression());
        }
        return result;
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
