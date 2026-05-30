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

/** Bounded AST-based hypothesis operator for conservative square completion. */
public class CompleteSquareHypothesisOperator implements HypothesisOperator {
    public static final String RULE_ID = "hypothesis_complete_square_preparation";
    private static final int DEFAULT_MAX_CANDIDATES = 6;

    private final ExpressionParser parser = new ExpressionParser();
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
    private final int maxCandidates;

    public CompleteSquareHypothesisOperator() {
        this(DEFAULT_MAX_CANDIDATES);
    }

    public CompleteSquareHypothesisOperator(int maxCandidates) {
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
        if (terms.size() < 2 || terms.size() > 3) {
            return List.of();
        }

        Map<String, ScoredCandidate> candidates = new LinkedHashMap<>();
        addUnivariateCandidate(terms, formattedInput, originalSize, candidates);
        addBivariatePerfectSquareCandidate(terms, formattedInput, originalSize, candidates);
        return candidates.values().stream()
            .sorted(Comparator.comparingInt(ScoredCandidate::score)
                .thenComparing(candidate -> candidate.transformation().transformedExpression()))
            .limit(maxCandidates)
            .map(ScoredCandidate::transformation)
            .toList();
    }

    private void addUnivariateCandidate(
        List<Expr> terms,
        String formattedInput,
        int originalSize,
        Map<String, ScoredCandidate> candidates
    ) {
        if (terms.size() != 3) {
            return;
        }
        for (int squareIndex = 0; squareIndex < terms.size(); squareIndex++) {
            Expr base = squareBase(terms.get(squareIndex));
            if (base == null) {
                continue;
            }
            LinearTerm linear = null;
            Double constant = null;
            for (int index = 0; index < terms.size(); index++) {
                if (index == squareIndex) {
                    continue;
                }
                Expr term = terms.get(index);
                if (term instanceof NumberExpr numberExpr) {
                    constant = numberExpr.value();
                } else {
                    linear = linearTermForBase(term, base);
                }
            }
            if (linear == null || constant == null || !isEvenInteger(linear.coefficient())) {
                continue;
            }
            double offset = linear.coefficient() / 2.0;
            double remainder = constant - offset * offset;
            if (remainder > 0 || !isPerfectSquare(Math.abs(remainder))) {
                continue;
            }
            Expr completed = squared(new BinaryExpr(base, BinaryOperator.ADD, new NumberExpr(offset)));
            Expr candidate = remainder == 0
                ? completed
                : new BinaryExpr(completed, BinaryOperator.SUB, squared(new NumberExpr(Math.sqrt(Math.abs(remainder)))));
            addCandidate(candidate, formattedInput, originalSize, candidates);
        }
    }

    private void addBivariatePerfectSquareCandidate(
        List<Expr> terms,
        String formattedInput,
        int originalSize,
        Map<String, ScoredCandidate> candidates
    ) {
        if (terms.size() != 3) {
            return;
        }
        for (int leftIndex = 0; leftIndex < terms.size(); leftIndex++) {
            Expr left = squareBase(terms.get(leftIndex));
            if (left == null) {
                continue;
            }
            for (int rightIndex = leftIndex + 1; rightIndex < terms.size(); rightIndex++) {
                Expr right = squareBase(terms.get(rightIndex));
                if (right == null) {
                    continue;
                }
                int crossIndex = 3 - leftIndex - rightIndex;
                if (crossTermMatches(terms.get(crossIndex), left, right)) {
                    addCandidate(squared(new BinaryExpr(left, BinaryOperator.ADD, right)), formattedInput, originalSize, candidates);
                }
            }
        }
    }

    private void addCandidate(
        Expr candidate,
        String formattedInput,
        int originalSize,
        Map<String, ScoredCandidate> candidates
    ) {
        String formatted = ExpressionFormatter.format(candidate);
        if (formatted.equals(formattedInput)) {
            return;
        }
        int candidateSize = canonicalizer.astNodeCount(formatted);
        int growth = candidateSize - originalSize;
        String key = canonicalizer.stableHash(formatted);
        candidates.putIfAbsent(key, new ScoredCandidate(growth * 2 + candidateSize, new Transformation(
            RULE_ID,
            formatted,
            RewriteKind.NORMALIZE,
            true,
            Math.max(1, growth),
            true,
            RULE_ID + ":" + canonicalizer.stableHash(formattedInput) + "->" + key
        )));
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

    private Expr squareBase(Expr expression) {
        return expression instanceof BinaryExpr binary
            && binary.operator() == BinaryOperator.POW
            && binary.right() instanceof NumberExpr exponent
            && Double.compare(exponent.value(), 2.0) == 0
            ? binary.left()
            : null;
    }

    private LinearTerm linearTermForBase(Expr expression, Expr base) {
        List<Expr> factors = flattenMultiplication(expression);
        double coefficient = 1.0;
        List<Expr> symbolic = new ArrayList<>();
        for (Expr factor : factors) {
            if (factor instanceof NumberExpr numberExpr) {
                coefficient *= numberExpr.value();
            } else {
                symbolic.add(factor);
            }
        }
        if (symbolic.size() == 1 && sameExpression(symbolic.getFirst(), base)) {
            return new LinearTerm(coefficient);
        }
        return null;
    }

    private boolean crossTermMatches(Expr expression, Expr left, Expr right) {
        List<Expr> factors = flattenMultiplication(expression);
        double coefficient = 1.0;
        List<Expr> symbolic = new ArrayList<>();
        for (Expr factor : factors) {
            if (factor instanceof NumberExpr numberExpr) {
                coefficient *= numberExpr.value();
            } else {
                symbolic.add(factor);
            }
        }
        return Double.compare(coefficient, 2.0) == 0
            && symbolic.size() == 2
            && ((sameExpression(symbolic.get(0), left) && sameExpression(symbolic.get(1), right))
            || (sameExpression(symbolic.get(0), right) && sameExpression(symbolic.get(1), left)));
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

    private boolean sameExpression(Expr left, Expr right) {
        return canonicalizer.stableHash(ExpressionFormatter.format(left))
            .equals(canonicalizer.stableHash(ExpressionFormatter.format(right)));
    }

    private Expr squared(Expr expression) {
        return new BinaryExpr(expression, BinaryOperator.POW, new NumberExpr(2));
    }

    private boolean isEvenInteger(double value) {
        return Math.rint(value) == value && ((long) value) % 2 == 0;
    }

    private boolean isPerfectSquare(double value) {
        if (value < 0 || Math.rint(value) != value) {
            return false;
        }
        long rounded = (long) value;
        long root = Math.round(Math.sqrt(rounded));
        return root * root == rounded;
    }

    private record LinearTerm(double coefficient) {
    }

    private record ScoredCandidate(int score, Transformation transformation) {
    }
}
