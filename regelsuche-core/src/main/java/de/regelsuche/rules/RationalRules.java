package de.regelsuche.rules;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RewriteRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Curated rational-expression rewrite rules.
 *
 * <p>Only atomic, structurally local rules are exposed; no full common
 * denominator algorithm is offered as a single rule. Division by an explicit
 * zero literal is filtered out at match time to satisfy "Division durch 0
 * verhindern".</p>
 */
public final class RationalRules {
    private static final Set<String> CORE_IDS = Set.of(
        "ast_divide_one",
        "ast_multiply_one_right",
        "ast_multiply_one_left",
        "ast_multiply_zero_right",
        "ast_multiply_zero_left"
    );

    private RationalRules() {
    }

    public static List<RewriteRule> rules() {
        List<RewriteRule> result = new ArrayList<>();
        for (RewriteRule rule : AstRewriteTransformationEngine.defaultRules()) {
            if (CORE_IDS.contains(rule.id())) {
                result.add(rule);
            }
        }
        result.add(new CancelCommonFactorRule());
        result.add(new MultiplyFractionsRule());
        result.add(new DivideByFractionRule());
        return List.copyOf(result);
    }

    /**
     * {@code (A*B)/(A*C) -> B/C} when {@code A} is structurally identical on
     * both sides. Refuses to apply when the divisor would become a literal
     * zero. The retained assumptions include both the cancelled factor
     * {@code A != 0} and the remaining denominator {@code C != 0}.
     */
    public static final class CancelCommonFactorRule implements RewriteRule {
        @Override
        public String id() {
            return "rational_cancel_common_factor";
        }

        @Override
        public RewriteKind kind() {
            return RewriteKind.SIMPLIFY;
        }

        @Override
        public boolean mayIncreaseComplexity() {
            return false;
        }

        @Override
        public int estimatedCostDelta() {
            return -3;
        }

        @Override
        public boolean isEquivalencePreservingByConstruction() {
            return true;
        }

        @Override
        public boolean mayEmitAssumptions() {
            return true;
        }

        @Override
        public java.util.List<de.regelsuche.assumption.Assumption> assumptions(Expr subtree) {
            Cancellable terms = extract(subtree);
            if (terms == null) {
                return java.util.List.of();
            }
            String cancelledFactor = de.regelsuche.parse.ExpressionFormatter.format(
                terms.cancelledFactor());
            String remainingDenominator = de.regelsuche.parse.ExpressionFormatter.format(
                terms.remainingDenominator());
            de.regelsuche.assumption.Assumption cancelledFactorNonZero =
                de.regelsuche.assumption.Assumption.nonZero(cancelledFactor);
            if (cancelledFactor.equals(remainingDenominator)) {
                return java.util.List.of(cancelledFactorNonZero);
            }
            return java.util.List.of(
                cancelledFactorNonZero,
                de.regelsuche.assumption.Assumption.nonZero(remainingDenominator));
        }

        @Override
        public boolean matches(Expr subtree) {
            return extract(subtree) != null;
        }

        @Override
        public Expr apply(Expr subtree) {
            Cancellable terms = extract(subtree);
            if (terms == null) {
                throw new IllegalArgumentException("Rule does not match subtree");
            }
            return new BinaryExpr(terms.remainingNumerator(), BinaryOperator.DIV, terms.remainingDenominator());
        }

        private Cancellable extract(Expr subtree) {
            if (!(subtree instanceof BinaryExpr division) || division.operator() != BinaryOperator.DIV) {
                return null;
            }
            if (!(division.left() instanceof BinaryExpr numerator)
                || numerator.operator() != BinaryOperator.MUL) {
                return null;
            }
            if (!(division.right() instanceof BinaryExpr denominator)
                || denominator.operator() != BinaryOperator.MUL) {
                return null;
            }
            if (numerator.left().equals(denominator.left())) {
                if (isZero(denominator.right())) {
                    return null;
                }
                return new Cancellable(
                    numerator.left(), numerator.right(), denominator.right());
            }
            if (numerator.right().equals(denominator.right())) {
                if (isZero(denominator.left())) {
                    return null;
                }
                return new Cancellable(
                    numerator.right(), numerator.left(), denominator.left());
            }
            return null;
        }

        private boolean isZero(Expr expr) {
            return expr instanceof NumberExpr number && number.value() == 0;
        }

        private record Cancellable(
            Expr cancelledFactor,
            Expr remainingNumerator,
            Expr remainingDenominator
        ) {
        }
    }

    /**
     * {@code (A/B) * (C/D) -> (A*C)/(B*D)}. Refuses when {@code B} or
     * {@code D} are explicit zero literals.
     */
    public static final class MultiplyFractionsRule implements RewriteRule {
        @Override
        public String id() {
            return "rational_multiply_fractions";
        }

        @Override
        public RewriteKind kind() {
            return RewriteKind.NORMALIZE;
        }

        @Override
        public boolean mayIncreaseComplexity() {
            return false;
        }

        @Override
        public int estimatedCostDelta() {
            return -1;
        }

        @Override
        public boolean isEquivalencePreservingByConstruction() {
            return true;
        }

        @Override
        public boolean matches(Expr subtree) {
            return parts(subtree) != null;
        }

        @Override
        public boolean mayEmitAssumptions() {
            return true;
        }

        @Override
        public java.util.List<de.regelsuche.assumption.Assumption> assumptions(Expr subtree) {
            Pair pair = parts(subtree);
            if (pair == null) {
                return java.util.List.of();
            }
            return java.util.List.of(
                de.regelsuche.assumption.Assumption.nonZero(
                    de.regelsuche.parse.ExpressionFormatter.format(pair.leftDenominator)),
                de.regelsuche.assumption.Assumption.nonZero(
                    de.regelsuche.parse.ExpressionFormatter.format(pair.rightDenominator)));
        }

        @Override
        public Expr apply(Expr subtree) {
            Pair pair = parts(subtree);
            if (pair == null) {
                throw new IllegalArgumentException("Rule does not match subtree");
            }
            Expr numerator = new BinaryExpr(pair.leftNumerator, BinaryOperator.MUL, pair.rightNumerator);
            Expr denominator = new BinaryExpr(pair.leftDenominator, BinaryOperator.MUL, pair.rightDenominator);
            return new BinaryExpr(numerator, BinaryOperator.DIV, denominator);
        }

        private Pair parts(Expr subtree) {
            if (!(subtree instanceof BinaryExpr product) || product.operator() != BinaryOperator.MUL) {
                return null;
            }
            if (!(product.left() instanceof BinaryExpr left) || left.operator() != BinaryOperator.DIV) {
                return null;
            }
            if (!(product.right() instanceof BinaryExpr right) || right.operator() != BinaryOperator.DIV) {
                return null;
            }
            if (isExplicitZero(left.right()) || isExplicitZero(right.right())) {
                return null;
            }
            return new Pair(left.left(), left.right(), right.left(), right.right());
        }

        private boolean isExplicitZero(Expr expr) {
            return expr instanceof NumberExpr number && number.value() == 0;
        }

        private record Pair(Expr leftNumerator, Expr leftDenominator, Expr rightNumerator, Expr rightDenominator) {
        }
    }

    /**
     * {@code A / (B/C) -> (A*C)/B}. Refuses when {@code C} would be a literal
     * zero.
     */
    public static final class DivideByFractionRule implements RewriteRule {
        @Override
        public String id() {
            return "rational_divide_by_fraction";
        }

        @Override
        public RewriteKind kind() {
            return RewriteKind.NORMALIZE;
        }

        @Override
        public boolean mayIncreaseComplexity() {
            return false;
        }

        @Override
        public int estimatedCostDelta() {
            return 0;
        }

        @Override
        public boolean isEquivalencePreservingByConstruction() {
            return true;
        }

        @Override
        public boolean matches(Expr subtree) {
            return extract(subtree) != null;
        }

        @Override
        public boolean mayEmitAssumptions() {
            return true;
        }

        @Override
        public java.util.List<de.regelsuche.assumption.Assumption> assumptions(Expr subtree) {
            Parts parts = extract(subtree);
            if (parts == null) {
                return java.util.List.of();
            }
            return java.util.List.of(
                de.regelsuche.assumption.Assumption.nonZero(
                    de.regelsuche.parse.ExpressionFormatter.format(parts.b)),
                de.regelsuche.assumption.Assumption.nonZero(
                    de.regelsuche.parse.ExpressionFormatter.format(parts.c)));
        }

        @Override
        public Expr apply(Expr subtree) {
            Parts parts = extract(subtree);
            if (parts == null) {
                throw new IllegalArgumentException("Rule does not match subtree");
            }
            return new BinaryExpr(
                new BinaryExpr(parts.a, BinaryOperator.MUL, parts.c),
                BinaryOperator.DIV,
                parts.b
            );
        }

        private Parts extract(Expr subtree) {
            if (!(subtree instanceof BinaryExpr outer) || outer.operator() != BinaryOperator.DIV) {
                return null;
            }
            if (!(outer.right() instanceof BinaryExpr inner) || inner.operator() != BinaryOperator.DIV) {
                return null;
            }
            if (inner.right() instanceof NumberExpr n && n.value() == 0) {
                return null;
            }
            if (inner.left() instanceof NumberExpr leftNumber && leftNumber.value() == 0) {
                return null;
            }
            return new Parts(outer.left(), inner.left(), inner.right());
        }

        private record Parts(Expr a, Expr b, Expr c) {
        }
    }
}
