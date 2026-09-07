package de.regelsuche.transform;

import de.regelsuche.scalar.ExactRational;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SymPy-derived factor candidate provider with explicit discovery provenance. */
public final class FactorCandidateOperator implements HypothesisOperator {
    public static final String RULE_ID = "hypothesis_sympy_factor_candidate";
    private static final String PACK_ID = "sympy-polynomial-basic";
    private static final String LICENSE = "BSD-3-Clause";
    private static final String KIND = "kind=factor-candidate";
    private static final String SOURCE = "source=sympy-derived";

    private final ExpressionParser parser = new ExpressionParser();
    private final QuadraticFactorizationHypothesisOperator quadratic = new QuadraticFactorizationHypothesisOperator();
    private final RepeatedSubexpressionFactorizationHypothesisOperator repeated =
        new RepeatedSubexpressionFactorizationHypothesisOperator();

    @Override
    public List<Transformation> generateCandidates(String expression) {
        LinkedHashMap<String, Transformation> candidates = new LinkedHashMap<>();
        quadratic.generateCandidates(expression).forEach(candidate -> addMappedCandidate(candidate, candidates, true));
        repeated.generateCandidates(expression).forEach(candidate -> addMappedCandidate(candidate, candidates, false));
        contentCandidate(expression).ifPresent(candidate -> candidates.putIfAbsent(candidate.transformedExpression(), candidate));
        return List.copyOf(candidates.values());
    }

    private void addMappedCandidate(Transformation candidate, Map<String, Transformation> out, boolean validated) {
        String applicationKey = RULE_ID + "|" + SOURCE + "|" + KIND + "|validated=" + validated + "|"
            + candidate.applicationKey();
        out.putIfAbsent(candidate.transformedExpression(), new Transformation(
            RULE_ID,
            candidate.transformedExpression(),
            RewriteKind.FACTOR,
            candidate.mayIncreaseComplexity(),
            candidate.estimatedCostDelta(),
            candidate.equivalencePreservingByConstruction(),
            applicationKey,
            candidate.assumptions(),
            PACK_ID,
            LICENSE
        ));
    }

    private java.util.Optional<Transformation> contentCandidate(String expression) {
        Expr parsed;
        try {
            parsed = parser.parse(new InputRequest(InputType.TERM, expression)).terms().getFirst();
        } catch (IllegalArgumentException exception) {
            return java.util.Optional.empty();
        }
        List<Expr> addends = flattenAddends(parsed);
        if (addends.size() < 2) {
            return java.util.Optional.empty();
        }
        int gcd = 0;
        for (Expr addend : addends) {
            int coefficient = integerCoefficient(addend);
            if (coefficient == 0) {
                return java.util.Optional.empty();
            }
            gcd = gcd == 0 ? Math.abs(coefficient) : gcd(gcd, Math.abs(coefficient));
            if (gcd == 1) {
                return java.util.Optional.empty();
            }
        }
        Expr primitive = divideBy(addends.getFirst(), gcd);
        for (int index = 1; index < addends.size(); index++) {
            primitive = new BinaryExpr(primitive, BinaryOperator.ADD, divideBy(addends.get(index), gcd));
        }
        Expr factored = new BinaryExpr(new NumberExpr(gcd), BinaryOperator.MUL, primitive);
        String transformed = ExpressionFormatter.format(factored);
        String formattedInput = ExpressionFormatter.format(parsed);
        if (transformed.equals(formattedInput)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new Transformation(
            RULE_ID,
            transformed,
            RewriteKind.FACTOR,
            false,
            -1,
            true,
            RULE_ID + "|" + SOURCE + "|" + KIND + "|validated=true|provider=content-primitive",
            List.of(),
            PACK_ID,
            LICENSE
        ));
    }

    private List<Expr> flattenAddends(Expr expression) {
        List<Expr> out = new ArrayList<>();
        collectAddends(expression, 1, out);
        return out;
    }

    private void collectAddends(Expr expression, int sign, List<Expr> out) {
        if (expression instanceof BinaryExpr binary) {
            if (binary.operator() == BinaryOperator.ADD) {
                collectAddends(binary.left(), sign, out);
                collectAddends(binary.right(), sign, out);
                return;
            }
            if (binary.operator() == BinaryOperator.SUB) {
                collectAddends(binary.left(), sign, out);
                collectAddends(binary.right(), -sign, out);
                return;
            }
        }
        out.add(sign >= 0 ? expression : new BinaryExpr(new NumberExpr(-1), BinaryOperator.MUL, expression));
    }

    private int integerCoefficient(Expr expression) {
        if (expression instanceof NumberExpr number && (number.value().isInteger() && number.value().numerator().abs().bitLength() < 31)) {
            return number.value().intValueExact();
        }
        if (expression instanceof BinaryExpr binary && binary.operator() == BinaryOperator.MUL) {
            if (binary.left() instanceof NumberExpr left && (left.value().isInteger() && left.value().numerator().abs().bitLength() < 31)) {
                return left.value().intValueExact();
            }
            if (binary.right() instanceof NumberExpr right && (right.value().isInteger() && right.value().numerator().abs().bitLength() < 31)) {
                return right.value().intValueExact();
            }
        }
        return 1;
    }

    private Expr divideBy(Expr expression, int divisor) {
        if (expression instanceof NumberExpr number && (number.value().isInteger() && number.value().numerator().abs().bitLength() < 31)) {
            return new NumberExpr(number.value().divide(ExactRational.integer(divisor)));
        }
        if (expression instanceof BinaryExpr binary && binary.operator() == BinaryOperator.MUL) {
            if (binary.left() instanceof NumberExpr left && (left.value().isInteger() && left.value().numerator().abs().bitLength() < 31)) {
                ExactRational scaled = left.value().divide(ExactRational.integer(divisor));
                if (scaled.isOne()) {
                    return binary.right();
                }
                return new BinaryExpr(new NumberExpr(scaled), BinaryOperator.MUL, binary.right());
            }
            if (binary.right() instanceof NumberExpr right && (right.value().isInteger() && right.value().numerator().abs().bitLength() < 31)) {
                ExactRational scaled = right.value().divide(ExactRational.integer(divisor));
                if (scaled.isOne()) {
                    return binary.left();
                }
                return new BinaryExpr(binary.left(), BinaryOperator.MUL, new NumberExpr(scaled));
            }
        }
        return new BinaryExpr(new NumberExpr(ExactRational.integer(divisor).reciprocal()), BinaryOperator.MUL, expression);
    }

    private int gcd(int left, int right) {
        int a = Math.abs(left);
        int b = Math.abs(right);
        while (b != 0) {
            int next = a % b;
            a = b;
            b = next;
        }
        return a;
    }
}
