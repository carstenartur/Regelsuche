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

/**
 * Bounded conservative square-completion operator.
 *
 * <p>It only emits candidates with zero or negative perfect-square remainder;
 * it deliberately does not emit positive-remainder completions such as
 * {@code x^2 + 6*x + 6 -> (x + 3)^2 - 3} until that scope is validated.</p>
 */
public class ConservativeCompleteSquareHypothesisOperator implements HypothesisOperator {
    public static final String RULE_ID = "hypothesis_complete_square_preparation";
    private static final int DEFAULT_MAX_CANDIDATES = 6;

    private final ExpressionParser parser = new ExpressionParser();
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
    private final int maxCandidates;
    private final boolean matchProductFactors;
    private final boolean matchRationalFactors;
    private final java.util.function.LongConsumer arithmeticWork;

    public ConservativeCompleteSquareHypothesisOperator() {
        this(DEFAULT_MAX_CANDIDATES);
    }

    public ConservativeCompleteSquareHypothesisOperator(int maxCandidates) {
        this(maxCandidates, false);
    }

    /** Opt in to scalar product-factor matching while preserving the original matching profile. */
    public ConservativeCompleteSquareHypothesisOperator(int maxCandidates, boolean matchProductFactors) {
        this(maxCandidates, matchProductFactors, false, null);
    }

    /** Optionally match exact rational factors and meter scalar arithmetic. */
    public ConservativeCompleteSquareHypothesisOperator(int maxCandidates, boolean matchProductFactors,
            boolean matchRationalFactors, java.util.function.LongConsumer work) {
        if (matchRationalFactors && (!matchProductFactors || work == null))
            throw new IllegalArgumentException("rational factor matching needs product matching and a work observer");
        this.maxCandidates = Math.max(0, maxCandidates);
        this.matchProductFactors = matchProductFactors;
        this.matchRationalFactors = matchRationalFactors;
        this.arithmeticWork = work;
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
        int originalSize = matchProductFactors ? formattedInput.length() : canonicalizer.astNodeCount(formattedInput);
        List<Expr> terms = flattenPositiveAddends(root);
        if (terms.size() < 2 || terms.size() > 3) {
            return List.of();
        }

        Map<String, ScoredCandidate> candidates = new LinkedHashMap<>();
        try {
            addUnivariateCandidate(terms, formattedInput, originalSize, candidates);
            addBivariatePerfectSquareCandidate(terms, formattedInput, originalSize, candidates);
        } catch (RationalLimit excessive) {
            return List.of();
        }
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
            if (base == null) continue;
            UnivariateTerms selected = selectUnivariateTerms(terms, squareIndex, base);
            if (selected == null) continue;
            Expr candidate = completeUnivariateSquare(base, selected);
            if (candidate != null) addCandidate(candidate, formattedInput, originalSize, candidates);
        }
    }

    private UnivariateTerms selectUnivariateTerms(List<Expr> terms, int squareIndex, Expr base) {
        LinearTerm linear = null;
        ExactRational constant = null;
        for (int index = 0; index < terms.size(); index++) {
            if (index == squareIndex) continue;
            Expr term = terms.get(index);
            ExactRational scalar = matchRationalFactors ? scalarFactor(term)
                : term instanceof NumberExpr number ? number.value() : null;
            if (scalar != null) constant = scalar;
            else linear = linearTermForBase(term, base);
        }
        if (linear == null || constant == null || (!matchRationalFactors && !isEvenInteger(linear.coefficient()))) return null;
        return new UnivariateTerms(linear, constant);
    }

    private Expr completeUnivariateSquare(Expr base, UnivariateTerms selected) {
        ExactRational coefficient = selected.linear().coefficient();
        if (arithmeticWork != null) chargeScalarOperation(coefficient, ExactRational.integer(2));
        ExactRational offset = coefficient.divide(ExactRational.integer(2));
        if (arithmeticWork != null) chargeScalarOperation(offset, offset);
        ExactRational square = offset.multiply(offset);
        if (arithmeticWork != null) chargeScalarOperation(selected.constant(), square);
        ExactRational remainder = selected.constant().subtract(square);
        if (remainder.signum() > 0) return null;
        if (arithmeticWork != null) chargeSquareRoot(remainder.abs());
        if (!isPerfectSquare(remainder.abs())) return null;
        Expr completed = squared(new BinaryExpr(base, BinaryOperator.ADD, new NumberExpr(offset)));
        if (arithmeticWork != null && !remainder.isZero()) chargeSquareRoot(remainder.abs());
        return remainder.isZero() ? completed
            : new BinaryExpr(completed, BinaryOperator.SUB, squared(new NumberExpr(remainder.abs().sqrtExact().orElseThrow())));
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
        int candidateSize = matchProductFactors ? formatted.length() : canonicalizer.astNodeCount(formatted);
        int growth = candidateSize - originalSize;
        String key = matchProductFactors ? formatted : canonicalizer.stableHash(formatted);
        candidates.putIfAbsent(key, new ScoredCandidate(growth * 2 + candidateSize, new Transformation(
            RULE_ID,
            formatted,
            RewriteKind.NORMALIZE,
            true,
            Math.max(1, growth),
            true,
            RULE_ID + ":" + (matchProductFactors ? formattedInput : canonicalizer.stableHash(formattedInput)) + "->" + key
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
            && exponent.value().equalsInteger(2)
            ? binary.left()
            : null;
    }

    private LinearTerm linearTermForBase(Expr expression, Expr base) {
        List<Expr> factors = flattenMultiplication(expression);
        ExactRational coefficient = ExactRational.ONE;
        List<Expr> symbolic = new ArrayList<>();
        for (Expr factor : factors) {
            ExactRational scalar = matchRationalFactors ? scalarFactor(factor)
                : factor instanceof NumberExpr number ? number.value() : null;
            if (scalar != null) {
                if (arithmeticWork != null) chargeScalarOperation(coefficient, scalar);
                coefficient = coefficient.multiply(scalar);
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
        if (matchProductFactors) {
            List<Expr> expected = new ArrayList<>(flattenMultiplication(left));
            expected.addAll(flattenMultiplication(right));
            expected.add(new NumberExpr(2));
            return productSignature(flattenMultiplication(expression)).equals(productSignature(expected));
        }
        List<Expr> factors = flattenMultiplication(expression);
        ExactRational coefficient = ExactRational.ONE;
        List<Expr> symbolic = new ArrayList<>();
        for (Expr factor : factors) {
            if (factor instanceof NumberExpr numberExpr) {
                if (arithmeticWork != null) chargeScalarOperation(coefficient, numberExpr.value());
                coefficient = coefficient.multiply(numberExpr.value());
            } else {
                symbolic.add(factor);
            }
        }
        return coefficient.equalsInteger(2)
            && symbolic.size() == 2
            && ((sameExpression(symbolic.get(0), left) && sameExpression(symbolic.get(1), right))
            || (sameExpression(symbolic.get(0), right) && sameExpression(symbolic.get(1), left)));
    }

    private ProductSignature productSignature(List<Expr> factors) {
        ExactRational coefficient = ExactRational.ONE;
        List<String> symbols = new ArrayList<>();
        for (Expr factor : factors) {
            ExactRational scalar = scalarFactor(factor);
            if (scalar != null) {
                if (arithmeticWork != null) chargeScalarOperation(coefficient, scalar);
                coefficient = coefficient.multiply(scalar);
            } else {
                symbols.add(ExpressionFormatter.format(factor));
            }
        }
        symbols.sort(String::compareTo);
        return new ProductSignature(coefficient, List.copyOf(symbols));
    }

    private ExactRational scalarFactor(Expr factor) {
        if (factor instanceof NumberExpr number) return number.value();
        if (matchRationalFactors && factor instanceof BinaryExpr division && division.operator() == BinaryOperator.DIV
                && division.left() instanceof NumberExpr numerator && division.right() instanceof NumberExpr denominator
                && !denominator.value().isZero()) {
            chargeScalarOperation(numerator.value(), denominator.value());
            return numerator.value().divide(denominator.value());
        }
        return null;
    }

    private void chargeScalarOperation(ExactRational left, ExactRational right) {
        long leftBits = Math.max(left.numerator().abs().bitLength(), left.denominator().bitLength());
        long rightBits = Math.max(right.numerator().abs().bitLength(), right.denominator().bitLength());
        long resultBits = leftBits + rightBits + 1;
        arithmeticWork.accept(1 + (2 * resultBits + 63) / 64);
        if (matchRationalFactors && resultBits > 1_024) throw new RationalLimit();
    }

    private void chargeSquareRoot(ExactRational value) {
        long bits = Math.max(value.numerator().abs().bitLength(), value.denominator().bitLength());
        arithmeticWork.accept(1 + (bits + 63) / 64);
        if (matchRationalFactors && bits > 1_024) throw new RationalLimit();
    }

    private static final class RationalLimit extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private record ProductSignature(ExactRational coefficient, List<String> factors) { }

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
        if (matchProductFactors) return left.equals(right);
        return canonicalizer.stableHash(ExpressionFormatter.format(left))
            .equals(canonicalizer.stableHash(ExpressionFormatter.format(right)));
    }

    private Expr squared(Expr expression) {
        return new BinaryExpr(expression, BinaryOperator.POW, new NumberExpr(2));
    }

    private boolean isEvenInteger(ExactRational value) {
        return value.isInteger() && !value.numerator().testBit(0);
    }

    private boolean isPerfectSquare(ExactRational value) {
        return (matchRationalFactors || value.isInteger()) && value.sqrtExact().isPresent();
    }

    private record LinearTerm(ExactRational coefficient) {
    }

    private record UnivariateTerms(LinearTerm linear, ExactRational constant) { }

    private record ScoredCandidate(int score, Transformation transformation) {
    }
}
