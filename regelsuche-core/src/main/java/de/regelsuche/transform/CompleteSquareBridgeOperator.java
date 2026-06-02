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
        List<SignedTerm> terms = flattenAdditiveTerms(root, 1.0);
        if (terms.size() != 3) {
            return List.of();
        }

        Map<String, ScoredCandidate> candidates = new LinkedHashMap<>();
        for (int squareIndex = 0; squareIndex < terms.size(); squareIndex++) {
            if (Double.compare(terms.get(squareIndex).sign(), 1.0) != 0) {
                continue;
            }
            Expr base = squareBase(terms.get(squareIndex).expression());
            if (base == null) {
                continue;
            }
            LinearTerm linear = null;
            Double constant = null;
            for (int index = 0; index < terms.size(); index++) {
                if (index == squareIndex) {
                    continue;
                }
                SignedTerm term = terms.get(index);
                if (term.expression() instanceof NumberExpr numberExpr) {
                    constant = term.sign() * numberExpr.value();
                } else {
                    LinearTerm candidate = linearTermForBase(term.expression(), base, term.sign());
                    if (candidate != null) {
                        linear = candidate;
                    }
                }
            }
            if (linear == null || constant == null || !isInteger(linear.coefficient())) {
                continue;
            }
            double offset = linear.coefficient() / 2.0;
            double remainder = constant - offset * offset;
            Expr completed = squared(offsetExpression(base, offset));
            addCandidate(withRemainder(completed, remainder), formattedInput, originalSize, candidates);
            if (remainder < 0) {
                Double squareRoot = perfectSquareRoot(-remainder);
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

    private List<SignedTerm> flattenAdditiveTerms(Expr expression, double sign) {
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
            && Double.compare(exponent.value(), 2.0) == 0
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

    private LinearTerm linearTermForBase(Expr expression, Expr base, double sign) {
        List<Expr> factors = flattenMultiplication(expression);
        double coefficient = sign;
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

    private List<Expr> flattenMultiplication(Expr expression) {
        if (expression instanceof BinaryExpr binaryExpr && binaryExpr.operator() == BinaryOperator.MUL) {
            List<Expr> result = new ArrayList<>();
            result.addAll(flattenMultiplication(binaryExpr.left()));
            result.addAll(flattenMultiplication(binaryExpr.right()));
            return result;
        }
        return List.of(expression);
    }

    private Expr offsetExpression(Expr base, double offset) {
        if (offset == 0) {
            return base;
        }
        return offset > 0
            ? new BinaryExpr(base, BinaryOperator.ADD, new NumberExpr(offset))
            : new BinaryExpr(base, BinaryOperator.SUB, new NumberExpr(-offset));
    }

    private Expr withRemainder(Expr completed, double remainder) {
        if (remainder == 0) {
            return completed;
        }
        return remainder > 0
            ? new BinaryExpr(completed, BinaryOperator.ADD, new NumberExpr(remainder))
            : new BinaryExpr(completed, BinaryOperator.SUB, new NumberExpr(-remainder));
    }

    private Expr squared(Expr expression) {
        return new BinaryExpr(expression, BinaryOperator.POW, new NumberExpr(2));
    }

    private boolean sameExpression(Expr left, Expr right) {
        return canonicalizer.stableHash(ExpressionFormatter.format(left))
            .equals(canonicalizer.stableHash(ExpressionFormatter.format(right)));
    }

    private boolean isInteger(double value) {
        return Math.rint(value) == value;
    }

    private Double perfectSquareRoot(double value) {
        if (value < 0) {
            return null;
        }
        if (Math.rint(value) == value) {
            long rounded = (long) value;
            long root = Math.round(Math.sqrt(rounded));
            if (root * root == rounded) {
                return (double) root;
            }
        }
        double scaled = value * 4;
        if (Math.rint(scaled) == scaled && scaled > 0) {
            long rounded = (long) scaled;
            long root = Math.round(Math.sqrt(rounded));
            if (root * root == rounded) {
                return root / 2.0;
            }
        }
        return null;
    }

    private record SignedTerm(double sign, Expr expression) {
    }

    private record LinearTerm(double coefficient) {
    }

    private record ScoredCandidate(int score, Transformation transformation) {
    }
}
