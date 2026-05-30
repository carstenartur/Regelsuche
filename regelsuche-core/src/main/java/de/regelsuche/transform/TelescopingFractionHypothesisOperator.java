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
import java.util.List;

/** Conservative operator for {@code 1 / (u * (u + 1)) -> 1/u - 1/(u + 1)}. */
public class TelescopingFractionHypothesisOperator implements HypothesisOperator {
    public static final String RULE_ID = "hypothesis_telescoping_fraction";
    private static final int DEFAULT_MAX_CANDIDATES = 4;

    private final ExpressionParser parser = new ExpressionParser();
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
    private final int maxCandidates;

    public TelescopingFractionHypothesisOperator() {
        this(DEFAULT_MAX_CANDIDATES);
    }

    public TelescopingFractionHypothesisOperator(int maxCandidates) {
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
        if (!(root instanceof BinaryExpr division) || division.operator() != BinaryOperator.DIV || !isOne(division.left())) {
            return List.of();
        }
        List<Expr> factors = flattenMultiplication(division.right());
        if (factors.size() != 2) {
            return List.of();
        }
        UnitStepPair pair = unitStepPair(factors.get(0), factors.get(1));
        if (pair == null) {
            pair = unitStepPair(factors.get(1), factors.get(0));
        }
        if (pair == null) {
            return List.of();
        }
        String formattedInput = ExpressionFormatter.format(root);
        Expr transformed = new BinaryExpr(
            new BinaryExpr(new NumberExpr(1), BinaryOperator.DIV, pair.lower()),
            BinaryOperator.SUB,
            new BinaryExpr(new NumberExpr(1), BinaryOperator.DIV, pair.upper())
        );
        String formatted = ExpressionFormatter.format(transformed);
        if (formatted.equals(formattedInput)) {
            return List.of();
        }
        String key = canonicalizer.stableHash(formatted);
        Transformation transformation = new Transformation(
            RULE_ID,
            formatted,
            RewriteKind.NORMALIZE,
            false,
            -1,
            true,
            RULE_ID + ":" + canonicalizer.stableHash(formattedInput) + "->" + key
        );
        return List.of(transformation).stream()
            .sorted(Comparator.comparing(Transformation::transformedExpression))
            .limit(maxCandidates)
            .toList();
    }

    private UnitStepPair unitStepPair(Expr lower, Expr upper) {
        if (isPlusOne(upper, lower)) {
            return new UnitStepPair(lower, upper);
        }
        AdditiveOffset lowerOffset = additiveOffset(lower);
        AdditiveOffset upperOffset = additiveOffset(upper);
        if (lowerOffset != null
            && upperOffset != null
            && same(lowerOffset.symbolicPart(), upperOffset.symbolicPart())
            && Double.compare(upperOffset.offset() - lowerOffset.offset(), 1.0) == 0) {
            return new UnitStepPair(lower, upper);
        }
        return null;
    }

    private boolean isPlusOne(Expr candidate, Expr base) {
        if (!(candidate instanceof BinaryExpr binary) || binary.operator() != BinaryOperator.ADD) {
            return false;
        }
        return (isOne(binary.right()) && same(binary.left(), base))
            || (isOne(binary.left()) && same(binary.right(), base));
    }

    private AdditiveOffset additiveOffset(Expr expression) {
        if (expression instanceof NumberExpr number) {
            return new AdditiveOffset(new NumberExpr(0), number.value());
        }
        if (expression instanceof BinaryExpr binary && binary.operator() == BinaryOperator.ADD) {
            if (binary.right() instanceof NumberExpr right) {
                return new AdditiveOffset(binary.left(), right.value());
            }
            if (binary.left() instanceof NumberExpr left) {
                return new AdditiveOffset(binary.right(), left.value());
            }
        }
        return new AdditiveOffset(expression, 0.0);
    }

    private List<Expr> flattenMultiplication(Expr expression) {
        if (expression instanceof BinaryExpr binary && binary.operator() == BinaryOperator.MUL) {
            List<Expr> result = new ArrayList<>();
            result.addAll(flattenMultiplication(binary.left()));
            result.addAll(flattenMultiplication(binary.right()));
            return result;
        }
        return List.of(expression);
    }

    private boolean isOne(Expr expression) {
        return expression instanceof NumberExpr number && Double.compare(number.value(), 1.0) == 0;
    }

    private boolean same(Expr left, Expr right) {
        return canonicalizer.stableHash(ExpressionFormatter.format(left))
            .equals(canonicalizer.stableHash(ExpressionFormatter.format(right)));
    }

    private record UnitStepPair(Expr lower, Expr upper) {
    }

    private record AdditiveOffset(Expr symbolicPart, double offset) {
    }
}
