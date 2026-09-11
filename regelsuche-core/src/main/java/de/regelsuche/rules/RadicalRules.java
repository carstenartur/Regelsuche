package de.regelsuche.rules;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.RequiredAssumptionTemplate;
import de.regelsuche.transform.RewriteApplicabilitySchema;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.RewriteRule.RewriteApplicabilitySchemaProvider;
import java.util.List;

/** Curated square-root rewrite rules with explicit domain assumptions. */
public final class RadicalRules {
    private RadicalRules() {
    }

    public static List<RewriteRule> rules() {
        return List.of(
            new SqrtOfSquareRule(),
            new SqrtOfProductRule(),
            new SqrtOfZeroRule(),
            new SqrtOfOneRule());
    }

    /** {@code sqrt(a^2) -> abs(a)} — unconditional. */
    static final class SqrtOfSquareRule implements RewriteApplicabilitySchemaProvider {
        @Override
        public String id() { return "radical_sqrt_of_square_to_abs"; }
        @Override
        public RewriteKind kind() { return RewriteKind.NORMALIZE; }
        @Override
        public boolean mayIncreaseComplexity() { return false; }
        @Override
        public int estimatedCostDelta() { return -1; }
        @Override
        public boolean isEquivalencePreservingByConstruction() { return true; }
        @Override
        public boolean matches(Expr subtree) { return extract(subtree) != null; }
        @Override
        public Expr apply(Expr subtree) {
            Expr base = extract(subtree);
            if (base == null) throw new IllegalArgumentException("Rule does not match subtree");
            return new FunctionExpr("abs", base);
        }
        @Override
        public RewriteApplicabilitySchema applicabilitySchema() {
            PatternExpr base = PatternExpr.var("A");
            return exactSource(PatternExpr.fn("sqrt",
                PatternExpr.op(BinaryOperator.POW, base, PatternExpr.num(2))));
        }
        private Expr extract(Expr subtree) {
            if (!(subtree instanceof FunctionExpr function)
                    || !"sqrt".equals(function.name()) || function.arguments().size() != 1) return null;
            if (function.arguments().getFirst() instanceof BinaryExpr power
                    && power.operator() == BinaryOperator.POW
                    && power.right() instanceof NumberExpr exponent
                    && exponent.value().equalsInteger(2)) return power.left();
            return null;
        }
    }

    /** {@code sqrt(a*b) -> sqrt(a)*sqrt(b)} with {@code a >= 0, b >= 0}. */
    static final class SqrtOfProductRule implements RewriteApplicabilitySchemaProvider {
        @Override
        public String id() { return "radical_sqrt_of_product"; }
        @Override
        public RewriteKind kind() { return RewriteKind.EXPAND; }
        @Override
        public boolean mayIncreaseComplexity() { return true; }
        @Override
        public int estimatedCostDelta() { return 3; }
        @Override
        public boolean isEquivalencePreservingByConstruction() { return true; }
        @Override
        public boolean matches(Expr subtree) { return extract(subtree) != null; }
        @Override
        public Expr apply(Expr subtree) {
            BinaryExpr product = extract(subtree);
            if (product == null) throw new IllegalArgumentException("Rule does not match subtree");
            return new BinaryExpr(new FunctionExpr("sqrt", product.left()), BinaryOperator.MUL,
                new FunctionExpr("sqrt", product.right()));
        }
        @Override
        public boolean mayEmitAssumptions() { return true; }
        @Override
        public List<Assumption> assumptions(Expr subtree) {
            BinaryExpr product = extract(subtree);
            return product == null ? List.of() : List.of(
                Assumption.nonNegative(ExpressionFormatter.format(product.left())),
                Assumption.nonNegative(ExpressionFormatter.format(product.right())));
        }
        @Override
        public RewriteApplicabilitySchema applicabilitySchema() {
            PatternExpr left = PatternExpr.var("A");
            PatternExpr right = PatternExpr.var("B");
            return exactSource(PatternExpr.fn("sqrt",
                    PatternExpr.op(BinaryOperator.MUL, left, right)),
                RequiredAssumptionTemplate.nonNegative(left),
                RequiredAssumptionTemplate.nonNegative(right));
        }
        private BinaryExpr extract(Expr subtree) {
            if (!(subtree instanceof FunctionExpr function)
                    || !"sqrt".equals(function.name()) || function.arguments().size() != 1) return null;
            return function.arguments().getFirst() instanceof BinaryExpr inner
                    && inner.operator() == BinaryOperator.MUL ? inner : null;
        }
    }

    static final class SqrtOfZeroRule implements RewriteApplicabilitySchemaProvider {
        @Override
        public String id() { return "radical_sqrt_of_zero"; }
        @Override
        public RewriteKind kind() { return RewriteKind.SIMPLIFY; }
        @Override
        public boolean mayIncreaseComplexity() { return false; }
        @Override
        public int estimatedCostDelta() { return -1; }
        @Override
        public boolean isEquivalencePreservingByConstruction() { return true; }
        @Override
        public boolean matches(Expr subtree) { return numericSqrt(subtree, 0); }
        @Override
        public Expr apply(Expr subtree) {
            if (!matches(subtree)) throw new IllegalArgumentException("Rule does not match subtree");
            return new NumberExpr(0);
        }
        @Override
        public RewriteApplicabilitySchema applicabilitySchema() {
            return exactSource(PatternExpr.fn("sqrt", PatternExpr.num(0)));
        }
    }

    static final class SqrtOfOneRule implements RewriteApplicabilitySchemaProvider {
        @Override
        public String id() { return "radical_sqrt_of_one"; }
        @Override
        public RewriteKind kind() { return RewriteKind.SIMPLIFY; }
        @Override
        public boolean mayIncreaseComplexity() { return false; }
        @Override
        public int estimatedCostDelta() { return -1; }
        @Override
        public boolean isEquivalencePreservingByConstruction() { return true; }
        @Override
        public boolean matches(Expr subtree) { return numericSqrt(subtree, 1); }
        @Override
        public Expr apply(Expr subtree) {
            if (!matches(subtree)) throw new IllegalArgumentException("Rule does not match subtree");
            return new NumberExpr(1);
        }
        @Override
        public RewriteApplicabilitySchema applicabilitySchema() {
            return exactSource(PatternExpr.fn("sqrt", PatternExpr.num(1)));
        }
    }

    private static boolean numericSqrt(Expr expression, long value) {
        return expression instanceof FunctionExpr fn && "sqrt".equals(fn.name())
            && fn.arguments().size() == 1 && fn.arguments().getFirst() instanceof NumberExpr number
            && number.value().equalsInteger(value);
    }
}
