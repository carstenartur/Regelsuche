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

/**
 * Bounded preparation heuristic for sums of two square terms.
 *
 * <p>The operator rewrites {@code U^2 + V^2} into the equivalent bridge form
 * {@code (U + V)^2 - (sqrt(2UV))^2} when the bridge root is representable by
 * the AST subset supported here. It is intentionally not a general hidden-structure
 * generator.</p>
 */
public class DifferenceOfSquaresPreparationOperator implements HypothesisOperator {
    public static final String RULE_ID = "hypothesis_difference_of_squares_preparation";
    private static final int DEFAULT_MAX_CANDIDATES = 5;

    private final ExpressionParser parser = new ExpressionParser();
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
    private final int maxCandidates;

    public DifferenceOfSquaresPreparationOperator() {
        this(DEFAULT_MAX_CANDIDATES);
    }

    public DifferenceOfSquaresPreparationOperator(int maxCandidates) {
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

        String formattedInput = ExpressionFormatter.format(root);
        int originalSize = canonicalizer.astNodeCount(formattedInput);
        List<Expr> terms = flattenPositiveAddends(root);
        if (terms.size() > 2) {
            return List.of();
        }
        Map<String, ScoredCandidate> candidates = new LinkedHashMap<>();
        for (int left = 0; left < terms.size(); left++) {
            SquareRoot leftRoot = squareRoot(terms.get(left));
            if (leftRoot == null) {
                continue;
            }
            for (int right = left + 1; right < terms.size(); right++) {
                SquareRoot rightRoot = squareRoot(terms.get(right));
                if (rightRoot == null) {
                    continue;
                }
                Expr bridge = squareRootOfProduct(List.of(new NumberExpr(2), leftRoot.root(), rightRoot.root()));
                if (bridge == null) {
                    continue;
                }
                Expr prepared = new BinaryExpr(
                    squared(new BinaryExpr(leftRoot.root(), BinaryOperator.ADD, rightRoot.root())),
                    BinaryOperator.SUB,
                    squared(bridge)
                );
                String formatted = ExpressionFormatter.format(prepared);
                if (formatted.equals(formattedInput)) {
                    continue;
                }
                int candidateSize = canonicalizer.astNodeCount(formatted);
                int growth = candidateSize - originalSize;
                int score = growth * 3 + candidateSize - repeatedStructureBonus(prepared);
                String key = canonicalizer.stableHash(formatted);
                candidates.putIfAbsent(key, new ScoredCandidate(score, new Transformation(
                    RULE_ID,
                    formatted,
                    RewriteKind.NORMALIZE,
                    true,
                    Math.max(1, growth),
                    true,
                    RULE_ID + ":" + canonicalizer.stableHash(formattedInput) + "->" + key
                )));
            }
        }
        return candidates.values().stream()
            .sorted(Comparator.comparingInt(ScoredCandidate::score)
                .thenComparing(candidate -> candidate.transformation().transformedExpression()))
            .limit(maxCandidates)
            .map(ScoredCandidate::transformation)
            .toList();
    }

    private List<Expr> flattenPositiveAddends(Expr expression) {
        if (expression instanceof BinaryExpr binaryExpr && binaryExpr.operator() == BinaryOperator.ADD) {
            List<Expr> result = new ArrayList<>();
            result.addAll(flattenPositiveAddends(binaryExpr.left()));
            result.addAll(flattenPositiveAddends(binaryExpr.right()));
            return result;
        }
        return List.of(expression);
    }

    private SquareRoot squareRoot(Expr expression) {
        if (expression instanceof NumberExpr numberExpr) {
            Double root = perfectSquareRoot(numberExpr.value());
            return root == null ? null : new SquareRoot(new NumberExpr(root));
        }
        if (expression instanceof BinaryExpr binaryExpr && binaryExpr.operator() == BinaryOperator.POW
            && binaryExpr.right() instanceof NumberExpr exponent) {
            double value = exponent.value();
            if (isPositiveInteger(value) && ((long) value) % 2 == 0) {
                long half = ((long) value) / 2;
                Expr root = half == 1
                    ? binaryExpr.left()
                    : new BinaryExpr(binaryExpr.left(), BinaryOperator.POW, new NumberExpr(half));
                return new SquareRoot(root);
            }
        }
        if (expression instanceof BinaryExpr binaryExpr && binaryExpr.operator() == BinaryOperator.MUL) {
            Expr root = squareRootOfProduct(flattenMultiplication(expression));
            return root == null ? null : new SquareRoot(root);
        }
        return null;
    }

    private Expr squareRootOfProduct(List<Expr> factors) {
        double coefficient = 1;
        List<Expr> symbolicFactors = new ArrayList<>();
        Map<String, Expr> pendingUnpairedFactors = new LinkedHashMap<>();
        for (Expr factor : flattenMultiplication(buildProduct(factors))) {
            if (factor instanceof NumberExpr numberExpr) {
                coefficient *= numberExpr.value();
                continue;
            }
            SquareRoot root = squareRoot(factor);
            if (root == null) {
                String key = ExpressionFormatter.format(factor);
                Expr pending = pendingUnpairedFactors.remove(key);
                if (pending == null) {
                    pendingUnpairedFactors.put(key, factor);
                } else {
                    symbolicFactors.add(pending);
                }
            } else {
                symbolicFactors.add(root.root());
            }
        }
        Double numericRoot = perfectSquareRoot(coefficient);
        if (numericRoot == null) {
            return null;
        }
        if (!pendingUnpairedFactors.isEmpty()) {
            return null;
        }
        List<Expr> rootedFactors = new ArrayList<>();
        if (numericRoot != 1 || symbolicFactors.isEmpty()) {
            rootedFactors.add(new NumberExpr(numericRoot));
        }
        rootedFactors.addAll(symbolicFactors);
        return buildProduct(rootedFactors);
    }

    Expr squareRootOfProduct(Expr product) {
        return squareRootOfProduct(flattenMultiplication(product));
    }

    private List<Expr> flattenMultiplication(Expr expression) {
        if (expression instanceof BinaryExpr binaryExpr && binaryExpr.operator() == BinaryOperator.MUL) {
            List<Expr> result = new ArrayList<>();
            result.addAll(flattenMultiplication(binaryExpr.left()));
            result.addAll(flattenMultiplication(binaryExpr.right()));
            return result;
        }
        return List.of(expression);
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

    private Expr squared(Expr expression) {
        return new BinaryExpr(expression, BinaryOperator.POW, new NumberExpr(2));
    }

    private Double perfectSquareRoot(double value) {
        if (value < 0 || Math.rint(value) != value) {
            return null;
        }
        long rounded = (long) value;
        long root = Math.round(Math.sqrt(rounded));
        return root * root == rounded ? (double) root : null;
    }

    private boolean isPositiveInteger(double value) {
        return value > 0 && Math.rint(value) == value;
    }

    private int repeatedStructureBonus(Expr expression) {
        String formatted = ExpressionFormatter.format(expression);
        int repeatedPowerMarkers = formatted.split("\\^ 2", -1).length - 1;
        return repeatedPowerMarkers > 1 ? 2 : 0;
    }

    private record SquareRoot(Expr root) {
    }

    private record ScoredCandidate(int score, Transformation transformation) {
    }
}
