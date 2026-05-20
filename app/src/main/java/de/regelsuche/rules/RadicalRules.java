package de.regelsuche.rules;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RewriteRule;
import java.util.List;

/**
 * Curated radical (square-root) rewrite rules.
 *
 * <p>{@code sqrt(a^2) -> abs(a)} is the canonical identity; it does <em>not</em>
 * simplify to {@code a} because {@code a} may be negative. The rule emits no
 * assumption — it is valid for any real {@code a}.</p>
 *
 * <p>{@code sqrt(a*b) -> sqrt(a) * sqrt(b)} only holds for {@code a, b >= 0};
 * the corresponding assumption is surfaced.</p>
 */
public final class RadicalRules {
    private RadicalRules() {
    }

    public static List<RewriteRule> rules() {
        return List.of(
            new SqrtOfSquareRule(),
            new SqrtOfProductRule(),
            new SqrtOfZeroRule(),
            new SqrtOfOneRule()
        );
    }

    /** {@code sqrt(a^2) -> abs(a)} — unconditional. */
    static final class SqrtOfSquareRule implements RewriteRule {
        @Override
        public String id() {
            return "radical_sqrt_of_square_to_abs";
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
            return extract(subtree) != null;
        }

        @Override
        public Expr apply(Expr subtree) {
            Expr base = extract(subtree);
            if (base == null) {
                throw new IllegalArgumentException("Rule does not match subtree");
            }
            return new FunctionExpr("abs", base);
        }

        private Expr extract(Expr subtree) {
            if (!(subtree instanceof FunctionExpr functionExpr)
                || !"sqrt".equals(functionExpr.name())
                || functionExpr.arguments().size() != 1) {
                return null;
            }
            if (functionExpr.arguments().get(0) instanceof BinaryExpr power
                && power.operator() == BinaryOperator.POW
                && power.right() instanceof NumberExpr exponent
                && exponent.value() == 2.0) {
                return power.left();
            }
            return null;
        }
    }

    /** {@code sqrt(a*b) -> sqrt(a)*sqrt(b)} with {@code a >= 0, b >= 0}. */
    static final class SqrtOfProductRule implements RewriteRule {
        @Override
        public String id() {
            return "radical_sqrt_of_product";
        }

        @Override
        public RewriteKind kind() {
            return RewriteKind.EXPAND;
        }

        @Override
        public boolean mayIncreaseComplexity() {
            return true;
        }

        @Override
        public int estimatedCostDelta() {
            return 3;
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
        public Expr apply(Expr subtree) {
            BinaryExpr product = extract(subtree);
            if (product == null) {
                throw new IllegalArgumentException("Rule does not match subtree");
            }
            return new BinaryExpr(
                new FunctionExpr("sqrt", product.left()),
                BinaryOperator.MUL,
                new FunctionExpr("sqrt", product.right())
            );
        }

        @Override
        public List<Assumption> assumptions(Expr subtree) {
            BinaryExpr product = extract(subtree);
            if (product == null) {
                return List.of();
            }
            return List.of(
                Assumption.nonNegative(ExpressionFormatter.format(product.left())),
                Assumption.nonNegative(ExpressionFormatter.format(product.right()))
            );
        }

        private BinaryExpr extract(Expr subtree) {
            if (!(subtree instanceof FunctionExpr functionExpr)
                || !"sqrt".equals(functionExpr.name())
                || functionExpr.arguments().size() != 1) {
                return null;
            }
            if (functionExpr.arguments().get(0) instanceof BinaryExpr inner
                && inner.operator() == BinaryOperator.MUL) {
                return inner;
            }
            return null;
        }
    }

    /** {@code sqrt(0) -> 0}. */
    static final class SqrtOfZeroRule implements RewriteRule {
        @Override
        public String id() {
            return "radical_sqrt_of_zero";
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
            return -1;
        }

        @Override
        public boolean isEquivalencePreservingByConstruction() {
            return true;
        }

        @Override
        public boolean matches(Expr subtree) {
            return subtree instanceof FunctionExpr fn
                && "sqrt".equals(fn.name())
                && fn.arguments().size() == 1
                && fn.arguments().get(0) instanceof NumberExpr n && n.value() == 0.0;
        }

        @Override
        public Expr apply(Expr subtree) {
            if (!matches(subtree)) {
                throw new IllegalArgumentException("Rule does not match subtree");
            }
            return new NumberExpr(0);
        }
    }

    /** {@code sqrt(1) -> 1}. */
    static final class SqrtOfOneRule implements RewriteRule {
        @Override
        public String id() {
            return "radical_sqrt_of_one";
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
            return -1;
        }

        @Override
        public boolean isEquivalencePreservingByConstruction() {
            return true;
        }

        @Override
        public boolean matches(Expr subtree) {
            return subtree instanceof FunctionExpr fn
                && "sqrt".equals(fn.name())
                && fn.arguments().size() == 1
                && fn.arguments().get(0) instanceof NumberExpr n && n.value() == 1.0;
        }

        @Override
        public Expr apply(Expr subtree) {
            if (!matches(subtree)) {
                throw new IllegalArgumentException("Rule does not match subtree");
            }
            return new NumberExpr(1);
        }
    }
}
