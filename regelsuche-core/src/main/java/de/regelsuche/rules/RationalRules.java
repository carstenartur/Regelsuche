package de.regelsuche.rules;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.RequiredAssumptionTemplate;
import de.regelsuche.transform.RewriteApplicabilitySchema;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.RewriteRule.RewriteApplicabilitySchemaProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Curated atomic rewrite rules for rational expressions. */
public final class RationalRules {
    private static final Set<String> CORE_IDS = Set.of(
        "ast_divide_one", "ast_multiply_one_right", "ast_multiply_one_left",
        "ast_multiply_zero_right", "ast_multiply_zero_left");

    private RationalRules() {
    }

    public static List<RewriteRule> rules() {
        List<RewriteRule> result = new ArrayList<>();
        for (RewriteRule rule : AstRewriteTransformationEngine.defaultRules()) {
            if (CORE_IDS.contains(rule.id())) result.add(rule);
        }
        result.add(new CancelCommonFactorRule());
        result.add(new MultiplyFractionsRule());
        result.add(new DivideByFractionRule());
        return List.copyOf(result);
    }

    /**
     * {@code (A*B)/(A*C) -> B/C}. Two source orientations are supported by the
     * executor, so this stays outside one-pattern schema-directed preparation.
     */
    public static final class CancelCommonFactorRule implements RewriteRule {
        @Override
        public String id() { return "rational_cancel_common_factor"; }
        @Override
        public RewriteKind kind() { return RewriteKind.SIMPLIFY; }
        @Override
        public boolean mayIncreaseComplexity() { return false; }
        @Override
        public int estimatedCostDelta() { return -3; }
        @Override
        public boolean isEquivalencePreservingByConstruction() { return true; }
        @Override
        public boolean mayEmitAssumptions() { return true; }
        @Override
        public List<Assumption> assumptions(Expr subtree) {
            Cancellable terms = extract(subtree);
            if (terms == null) return List.of();
            String cancelled = ExpressionFormatter.format(terms.cancelledFactor());
            String denominator = ExpressionFormatter.format(terms.remainingDenominator());
            Assumption cancelledNonZero = Assumption.nonZero(cancelled);
            return cancelled.equals(denominator)
                ? List.of(cancelledNonZero)
                : List.of(cancelledNonZero, Assumption.nonZero(denominator));
        }
        @Override
        public boolean matches(Expr subtree) { return extract(subtree) != null; }
        @Override
        public Expr apply(Expr subtree) {
            Cancellable terms = extract(subtree);
            if (terms == null) throw new IllegalArgumentException("Rule does not match subtree");
            return new BinaryExpr(terms.remainingNumerator(), BinaryOperator.DIV,
                terms.remainingDenominator());
        }
        private Cancellable extract(Expr subtree) {
            if (!(subtree instanceof BinaryExpr division) || division.operator() != BinaryOperator.DIV
                    || !(division.left() instanceof BinaryExpr numerator) || numerator.operator() != BinaryOperator.MUL
                    || !(division.right() instanceof BinaryExpr denominator) || denominator.operator() != BinaryOperator.MUL) {
                return null;
            }
            if (isZero(denominator.left()) || isZero(denominator.right())) {
                return null;
            }
            if (numerator.left().equals(denominator.left())) {
                return new Cancellable(numerator.left(), numerator.right(), denominator.right());
            }
            if (numerator.right().equals(denominator.right())) {
                return new Cancellable(numerator.right(), numerator.left(), denominator.left());
            }
            return null;
        }
        private static boolean isZero(Expr expr) {
            return expr instanceof NumberExpr number && number.value().equalsInteger(0);
        }
        private record Cancellable(Expr cancelledFactor, Expr remainingNumerator, Expr remainingDenominator) {}
    }

    /** {@code (A/B)*(C/D) -> (A*C)/(B*D)} with non-zero denominators. */
    public static final class MultiplyFractionsRule implements RewriteApplicabilitySchemaProvider {
        @Override
        public String id() { return "rational_multiply_fractions"; }
        @Override
        public RewriteKind kind() { return RewriteKind.NORMALIZE; }
        @Override
        public boolean mayIncreaseComplexity() { return false; }
        @Override
        public int estimatedCostDelta() { return -1; }
        @Override
        public boolean isEquivalencePreservingByConstruction() { return true; }
        @Override
        public boolean matches(Expr subtree) { return parts(subtree) != null; }
        @Override
        public boolean mayEmitAssumptions() { return true; }
        @Override
        public List<Assumption> assumptions(Expr subtree) {
            Pair pair = parts(subtree);
            return pair == null ? List.of() : List.of(
                Assumption.nonZero(ExpressionFormatter.format(pair.leftDenominator())),
                Assumption.nonZero(ExpressionFormatter.format(pair.rightDenominator())));
        }
        @Override
        public RewriteApplicabilitySchema applicabilitySchema() {
            PatternExpr a = PatternExpr.var("A");
            PatternExpr b = PatternExpr.var("B");
            PatternExpr c = PatternExpr.var("C");
            PatternExpr d = PatternExpr.var("D");
            return exactSource(
                PatternExpr.op(BinaryOperator.MUL,
                    PatternExpr.op(BinaryOperator.DIV, a, b),
                    PatternExpr.op(BinaryOperator.DIV, c, d)),
                RequiredAssumptionTemplate.nonZero(b),
                RequiredAssumptionTemplate.nonZero(d));
        }
        @Override
        public Expr apply(Expr subtree) {
            Pair pair = parts(subtree);
            if (pair == null) throw new IllegalArgumentException("Rule does not match subtree");
            return new BinaryExpr(
                new BinaryExpr(pair.leftNumerator(), BinaryOperator.MUL, pair.rightNumerator()),
                BinaryOperator.DIV,
                new BinaryExpr(pair.leftDenominator(), BinaryOperator.MUL, pair.rightDenominator()));
        }
        private Pair parts(Expr subtree) {
            if (!(subtree instanceof BinaryExpr product) || product.operator() != BinaryOperator.MUL
                    || !(product.left() instanceof BinaryExpr left) || left.operator() != BinaryOperator.DIV
                    || !(product.right() instanceof BinaryExpr right) || right.operator() != BinaryOperator.DIV
                    || isExplicitZero(left.right()) || isExplicitZero(right.right())) return null;
            return new Pair(left.left(), left.right(), right.left(), right.right());
        }
        private record Pair(Expr leftNumerator, Expr leftDenominator, Expr rightNumerator, Expr rightDenominator) {}
    }

    /** {@code A/(B/C) -> (A*C)/B} with {@code B != 0, C != 0}. */
    public static final class DivideByFractionRule implements RewriteApplicabilitySchemaProvider {
        @Override
        public String id() { return "rational_divide_by_fraction"; }
        @Override
        public RewriteKind kind() { return RewriteKind.NORMALIZE; }
        @Override
        public boolean mayIncreaseComplexity() { return false; }
        @Override
        public int estimatedCostDelta() { return 0; }
        @Override
        public boolean isEquivalencePreservingByConstruction() { return true; }
        @Override
        public boolean matches(Expr subtree) { return extract(subtree) != null; }
        @Override
        public boolean mayEmitAssumptions() { return true; }
        @Override
        public List<Assumption> assumptions(Expr subtree) {
            Parts parts = extract(subtree);
            return parts == null ? List.of() : List.of(
                Assumption.nonZero(ExpressionFormatter.format(parts.b())),
                Assumption.nonZero(ExpressionFormatter.format(parts.c())));
        }
        @Override
        public RewriteApplicabilitySchema applicabilitySchema() {
            PatternExpr a = PatternExpr.var("A");
            PatternExpr b = PatternExpr.var("B");
            PatternExpr c = PatternExpr.var("C");
            return exactSource(
                PatternExpr.op(BinaryOperator.DIV, a,
                    PatternExpr.op(BinaryOperator.DIV, b, c)),
                RequiredAssumptionTemplate.nonZero(b),
                RequiredAssumptionTemplate.nonZero(c));
        }
        @Override
        public Expr apply(Expr subtree) {
            Parts parts = extract(subtree);
            if (parts == null) throw new IllegalArgumentException("Rule does not match subtree");
            return new BinaryExpr(new BinaryExpr(parts.a(), BinaryOperator.MUL, parts.c()),
                BinaryOperator.DIV, parts.b());
        }
        private Parts extract(Expr subtree) {
            if (!(subtree instanceof BinaryExpr outer) || outer.operator() != BinaryOperator.DIV
                    || !(outer.right() instanceof BinaryExpr inner) || inner.operator() != BinaryOperator.DIV
                    || isExplicitZero(inner.right()) || isExplicitZero(inner.left())) return null;
            return new Parts(outer.left(), inner.left(), inner.right());
        }
        private record Parts(Expr a, Expr b, Expr c) {}
    }

    private static boolean isExplicitZero(Expr expr) {
        return expr instanceof NumberExpr number && number.value().equalsInteger(0);
    }
}
