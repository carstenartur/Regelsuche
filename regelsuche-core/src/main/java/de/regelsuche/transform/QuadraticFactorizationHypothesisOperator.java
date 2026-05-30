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
import java.util.List;
import java.util.Optional;

/** Bounded integer-root factorization for monic quadratics such as {@code x^2 + 6*x + 5}. */
public final class QuadraticFactorizationHypothesisOperator implements HypothesisOperator {
    public static final String RULE_ID = "hypothesis_quadratic_factorization";

    private final ExpressionParser parser = new ExpressionParser();
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();

    @Override
    public List<Transformation> generateCandidates(String expression) {
        Expr parsed;
        try {
            parsed = parser.parse(new InputRequest(InputType.TERM, expression)).terms().getFirst();
        } catch (IllegalArgumentException exception) {
            return List.of();
        }
        Optional<Quadratic> quadratic = monicQuadratic(parsed);
        if (quadratic.isEmpty()) {
            return List.of();
        }
        Quadratic q = quadratic.get();
        for (int left = -Math.abs(q.constant()) - 1; left <= Math.abs(q.constant()) + 1; left++) {
            int right = q.sum() - left;
            if (left * right == q.constant()) {
                Expr factored = new BinaryExpr(
                    factor(q.variable(), left),
                    BinaryOperator.MUL,
                    factor(q.variable(), right)
                );
                String output = ExpressionFormatter.format(factored);
                String input = ExpressionFormatter.format(parsed);
                return List.of(new Transformation(
                    RULE_ID,
                    output,
                    RewriteKind.FACTOR,
                    false,
                    -4,
                    true,
                    RULE_ID + ":" + canonicalizer.stableHash(input) + "->" + canonicalizer.stableHash(output)
                ));
            }
        }
        return List.of();
    }

    private Optional<Quadratic> monicQuadratic(Expr expression) {
        List<Expr> terms = flattenAddends(expression);
        if (terms.size() != 3) {
            return Optional.empty();
        }
        Expr variable = null;
        Integer sum = null;
        Integer constant = null;
        for (Expr term : terms) {
            Expr square = squareBase(term);
            if (square != null) {
                variable = square;
                continue;
            }
            if (term instanceof NumberExpr number && isInteger(number.value())) {
                constant = (int) number.value();
                continue;
            }
        }
        if (variable == null || constant == null) {
            return Optional.empty();
        }
        for (Expr term : terms) {
            if (squareBase(term) == null && !(term instanceof NumberExpr)) {
                Integer coefficient = linearCoefficient(term, variable);
                if (coefficient != null) {
                    sum = coefficient;
                }
            }
        }
        return sum == null ? Optional.empty() : Optional.of(new Quadratic(variable, sum, constant));
    }

    private Expr factor(Expr variable, int constant) {
        if (constant == 0) {
            return variable;
        }
        return constant > 0
            ? new BinaryExpr(variable, BinaryOperator.ADD, new NumberExpr(constant))
            : new BinaryExpr(variable, BinaryOperator.SUB, new NumberExpr(-constant));
    }

    private Expr squareBase(Expr expression) {
        return expression instanceof BinaryExpr binary
            && binary.operator() == BinaryOperator.POW
            && binary.right() instanceof NumberExpr exponent
            && Double.compare(exponent.value(), 2.0) == 0
            ? binary.left()
            : null;
    }

    private Integer linearCoefficient(Expr expression, Expr variable) {
        List<Expr> factors = flattenFactors(expression);
        int coefficient = 1;
        int variableCount = 0;
        for (Expr factor : factors) {
            if (factor instanceof NumberExpr number && isInteger(number.value())) {
                coefficient *= (int) number.value();
            } else if (same(factor, variable)) {
                variableCount++;
            } else {
                return null;
            }
        }
        return variableCount == 1 ? coefficient : null;
    }

    private List<Expr> flattenAddends(Expr expression) {
        if (expression instanceof BinaryExpr binary && binary.operator() == BinaryOperator.ADD) {
            List<Expr> result = new ArrayList<>();
            result.addAll(flattenAddends(binary.left()));
            result.addAll(flattenAddends(binary.right()));
            return result;
        }
        return List.of(expression);
    }

    private List<Expr> flattenFactors(Expr expression) {
        if (expression instanceof BinaryExpr binary && binary.operator() == BinaryOperator.MUL) {
            List<Expr> result = new ArrayList<>();
            result.addAll(flattenFactors(binary.left()));
            result.addAll(flattenFactors(binary.right()));
            return result;
        }
        return List.of(expression);
    }

    private boolean same(Expr left, Expr right) {
        return canonicalizer.stableHash(ExpressionFormatter.format(left))
            .equals(canonicalizer.stableHash(ExpressionFormatter.format(right)));
    }

    private boolean isInteger(double value) {
        return Math.rint(value) == value;
    }

    private record Quadratic(Expr variable, int sum, int constant) {
    }
}
