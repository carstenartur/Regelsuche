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
import de.regelsuche.scalar.ExactRational;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parametric bridge for completing the square in {@code A^2 + 2*k*A + c}. */
public final class CompleteSquareBridgeOperator implements HypothesisOperator {
    public static final String RULE_ID = "complete_square_bridge";
    private static final int DEFAULT_MAX_CANDIDATES = 4;

    private final ExpressionParser parser = new ExpressionParser();
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
    private final int maxCandidates;

    public CompleteSquareBridgeOperator() {
        this(DEFAULT_MAX_CANDIDATES);
    }

    public CompleteSquareBridgeOperator(int maxCandidates) {
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
        List<SignedTerm> terms = flattenAdditiveTerms(root, 1);
        if (terms.size() != 3) {
            return List.of();
        }

        Map<String, ScoredCandidate> candidates = new LinkedHashMap<>();
        for (int squareIndex = 0; squareIndex < terms.size(); squareIndex++) {
            if (terms.get(squareIndex).sign() != 1) {
                continue;
            }
            Expr base = squareBase(terms.get(squareIndex).expression());
            if (base == null) {
                continue;
            }
            LinearTerm linear = null;
            ExactRational constant = null;
            for (int index = 0; index < terms.size(); index++) {
                if (index == squareIndex) {
                    continue;
                }
                SignedTerm term = terms.get(index);
                if (term.expression() instanceof NumberExpr numberExpr) {
                    constant = numberExpr.value().multiply(ExactRational.integer(term.sign()));
                } else {
                    LinearTerm candidate = linearTermForBase(term.expression(), base, term.sign());
                    if (candidate != null) {
                        linear = candidate;
                    }
                }
            }
            if (linear == null || constant == null || !linear.coefficient().isInteger()) {
                continue;
            }
            ExactRational offset = linear.coefficient().divide(ExactRational.integer(2));
            ExactRational remainder = constant.subtract(offset.multiply(offset));
            Expr completed = squared(offsetExpression(base, offset));
            addCandidate(withRemainder(completed, remainder), formattedInput, originalSize, candidates);
            if (remainder.signum() < 0) {
                ExactRational squareRoot = perfectSquareRoot(remainder.negate());
                if (squareRoot != null) {
                    addCandidate(
                        new BinaryExpr(completed, BinaryOperator.SUB, squared(new NumberExpr(squareRoot))),
                        formattedInput,
                        originalSize,
                        candidates
                    );
                }
            }
        }
        return candidates.values().stream()
            .sorted(Comparator.comparingInt(ScoredCandidate::score)
                .thenComparing(candidate -> candidate.transformation().transformedExpression()))
            .limit(maxCandidates)
            .map(ScoredCandidate::transformation)
            .toList();
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
        String key = formatted;
        candidates.putIfAbsent(key, new ScoredCandidate(growth + candidateSize, new Transformation(
            RULE_ID,
            formatted,
            RewriteKind.NORMALIZE,
            true,
            Math.min(0, growth - 2),
            true,
            RULE_ID + ":" + canonicalizer.stableHash(formattedInput) + "->" + key
        )));
    }

    private List<SignedTerm> flattenAdditiveTerms(Expr expression, int sign) {
        if (expression instanceof BinaryExpr binaryExpr) {
            if (binaryExpr.operator() == BinaryOperator.ADD) {
                List<SignedTerm> terms = new ArrayList<>();
                terms.addAll(flattenAdditiveTerms(binaryExpr.left(), sign));
                terms.addAll(flattenAdditiveTerms(binaryExpr.right(), sign));
                return terms;
            }
            if (binaryExpr.operator() == BinaryOperator.SUB) {
                List<SignedTerm> terms = new ArrayList<>();
                terms.addAll(flattenAdditiveTerms(binaryExpr.left(), sign));
                terms.addAll(flattenAdditiveTerms(binaryExpr.right(), -sign));
                return terms;
            }
        }
        return List.of(new SignedTerm(sign, expression));
    }

    private Expr squareBase(Expr expression) {
        if (expression instanceof BinaryExpr binary
            && binary.operator() == BinaryOperator.POW
            && binary.right() instanceof NumberExpr exponent
            && exponent.value().equalsInteger(2)
        ) {
            return binary.left();
        }
        if (expression instanceof BinaryExpr binary && binary.operator() == BinaryOperator.MUL) {
            List<Expr> factors = flattenMultiplication(expression);
            if (factors.size() == 2 && sameExpression(factors.get(0), factors.get(1))) {
                return factors.get(0);
            }
        }
        return null;
    }

    private LinearTerm linearTermForBase(Expr expression, Expr base, int sign) {
        List<Expr> factors = flattenMultiplication(expression);
        ExactRational coefficient = ExactRational.integer(sign);
        List<Expr> symbolic = new ArrayList<>();
        for (Expr factor : factors) {
            if (factor instanceof NumberExpr numberExpr) {
                coefficient = coefficient.multiply(numberExpr.value());
            } else {
                symbolic.add(factor);
            }
        }
        if (symbolic.size() == 1 && sameExpression(symbolic.getFirst(), base)) {
            return new LinearTerm(coefficient);
        }
        return null;
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

    private Expr offsetExpression(Expr base, ExactRational offset) {
        if (offset.isZero()) {
            return base;
        }
        return offset.signum() > 0
            ? new BinaryExpr(base, BinaryOperator.ADD, new NumberExpr(offset))
            : new BinaryExpr(base, BinaryOperator.SUB, new NumberExpr(offset.negate()));
    }

    private Expr withRemainder(Expr completed, ExactRational remainder) {
        if (remainder.isZero()) {
            return completed;
        }
        return remainder.signum() > 0
            ? new BinaryExpr(completed, BinaryOperator.ADD, new NumberExpr(remainder))
            : new BinaryExpr(completed, BinaryOperator.SUB, new NumberExpr(remainder.negate()));
    }

    private Expr squared(Expr expression) {
        return new BinaryExpr(expression, BinaryOperator.POW, new NumberExpr(2));
    }

    private boolean sameExpression(Expr left, Expr right) {
        return canonicalizer.stableHash(ExpressionFormatter.format(left))
            .equals(canonicalizer.stableHash(ExpressionFormatter.format(right)));
    }

    private ExactRational perfectSquareRoot(ExactRational value) {
        return value.sqrtExact().orElse(null);
    }

    private record SignedTerm(int sign, Expr expression) {
    }

    private record LinearTerm(ExactRational coefficient) {
    }

    private record ScoredCandidate(int score, Transformation transformation) {
    }
}
