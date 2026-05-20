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
 * Curated polynomial rewrite rules.
 *
 * <p>These rules are <strong>atomic</strong> by construction; they reuse the
 * structural patterns from {@link AstRewriteTransformationEngine#defaultRules()}
 * and add a small set of polynomial-specific atomic helpers (e.g. combining
 * like terms with numeric coefficients) without ever encoding a textbook
 * identity (binomial formula, difference of squares, ...) directly.</p>
 */
public final class PolynomialRules {
    private static final Set<String> IDS = Set.of(
        "ast_add_zero_right",
        "ast_add_zero_left",
        "ast_multiply_one_right",
        "ast_multiply_one_left",
        "ast_multiply_zero_right",
        "ast_multiply_zero_left",
        "ast_subtract_zero",
        "ast_double_term",
        "ast_product_to_power_two",
        "ast_power_two_to_product",
        "ast_combine_powers",
        "ast_power_of_power",
        "ast_distribute_left_add",
        "ast_distribute_right_add",
        "ast_distribute_left_subtract",
        "ast_distribute_right_subtract",
        "ast_factor_common_left",
        "ast_factor_common_right",
        "ast_canonical_normalize"
    );

    private PolynomialRules() {
    }

    public static List<RewriteRule> rules() {
        List<RewriteRule> result = new ArrayList<>();
        for (RewriteRule rule : AstRewriteTransformationEngine.defaultRules()) {
            if (IDS.contains(rule.id())) {
                result.add(rule);
            }
        }
        result.add(new CombineLikeTermsRule());
        return List.copyOf(result);
    }

    /**
     * Atomic rule combining {@code n*A + m*A -> (n+m)*A} where {@code n} and
     * {@code m} are integer literals and {@code A} is any expression.
     *
     * <p>This is a polynomial-specific atomic generalisation of the
     * {@code ast_double_term} rule (which handles the {@code A + A} case).</p>
     */
    public static final class CombineLikeTermsRule implements RewriteRule {
        @Override
        public String id() {
            return "polynomial_combine_like_terms";
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
            return -2;
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
            LikeTerms terms = extract(subtree);
            if (terms == null) {
                throw new IllegalArgumentException("Rule does not match subtree");
            }
            double combined = terms.leftCoefficient() + terms.rightCoefficient();
            if (combined == 0) {
                return new NumberExpr(0);
            }
            if (combined == 1) {
                return terms.body();
            }
            return new BinaryExpr(new NumberExpr(combined), BinaryOperator.MUL, terms.body());
        }

        private LikeTerms extract(Expr subtree) {
            if (!(subtree instanceof BinaryExpr addition) || addition.operator() != BinaryOperator.ADD) {
                return null;
            }
            Coefficient left = asCoefficient(addition.left());
            Coefficient right = asCoefficient(addition.right());
            if (left == null || right == null || !left.body().equals(right.body())) {
                return null;
            }
            // Avoid overlap with ast_double_term (A + A) by requiring at least one explicit numeric coefficient.
            if (left.coefficient() == 1 && right.coefficient() == 1) {
                return null;
            }
            return new LikeTerms(left.coefficient(), right.coefficient(), left.body());
        }

        private Coefficient asCoefficient(Expr expression) {
            if (expression instanceof BinaryExpr binary && binary.operator() == BinaryOperator.MUL) {
                if (binary.left() instanceof NumberExpr number) {
                    return new Coefficient(number.value(), binary.right());
                }
                if (binary.right() instanceof NumberExpr number) {
                    return new Coefficient(number.value(), binary.left());
                }
            }
            return new Coefficient(1, expression);
        }

        private record Coefficient(double coefficient, Expr body) {
        }

        private record LikeTerms(double leftCoefficient, double rightCoefficient, Expr body) {
        }
    }
}
