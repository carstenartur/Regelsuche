package de.regelsuche.rules;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.canonical.PolynomialNormalizer;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RewriteRule;
import java.util.ArrayList;
import java.util.HashSet;
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
        "ast_canonical_normalize",
        "polynomial_collect_like_terms"
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
        result.add(new CollectLikeTermsRule());
        result.add(new CombineLikeTermsRule());
        return List.copyOf(result);
    }

    /**
     * Visible collection step for fully expanded polynomial sums.
     *
     * <p>The canonicalizer intentionally does not expand composite
     * polynomials, so this rule surfaces the final "collect like terms" move as
     * an explicit search edge after distribution has produced a flat sum.</p>
     */
    public static final class CollectLikeTermsRule implements RewriteRule {
        private final PolynomialNormalizer normalizer = new PolynomialNormalizer();

        @Override
        public String id() {
            return "polynomial_collect_like_terms";
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
            return -4;
        }

        @Override
        public boolean isEquivalencePreservingByConstruction() {
            return true;
        }

        @Override
        public boolean matches(Expr subtree) {
            return normalized(subtree) != null;
        }

        @Override
        public Expr apply(Expr subtree) {
            Expr normalized = normalized(subtree);
            if (normalized == null) {
                throw new IllegalArgumentException("Rule does not match subtree");
            }
            return normalized;
        }

        private Expr normalized(Expr subtree) {
            if (!hasAddOrSubtractWithLikeTerms(subtree)) {
                return null;
            }
            return normalizer.normalize(subtree)
                .filter(normalized -> !ExpressionFormatter.format(normalized).equals(ExpressionFormatter.format(subtree)))
                .filter(normalized -> nodeCount(normalized) <= nodeCount(subtree))
                .orElse(null);
        }

        private boolean hasAddOrSubtractWithLikeTerms(Expr subtree) {
            List<Expr> terms = new ArrayList<>();
            collectTerms(subtree, terms);
            if (terms.size() < 2) {
                return false;
            }
            Set<String> seen = new HashSet<>();
            for (Expr term : terms) {
                String key = monomialKey(term);
                if (!key.isBlank() && !seen.add(key)) {
                    return true;
                }
            }
            return false;
        }

        private void collectTerms(Expr expression, List<Expr> terms) {
            if (expression instanceof BinaryExpr binary) {
                if (binary.operator() == BinaryOperator.ADD) {
                    collectTerms(binary.left(), terms);
                    collectTerms(binary.right(), terms);
                    return;
                }
                if (binary.operator() == BinaryOperator.SUB) {
                    collectTerms(binary.left(), terms);
                    collectTerms(binary.right(), terms);
                    return;
                }
            }
            terms.add(expression);
        }

        private String monomialKey(Expr expression) {
            List<String> factors = new ArrayList<>();
            collectNonNumericFactors(expression, factors);
            if (factors.isEmpty()) {
                return "";
            }
            factors.sort(String::compareTo);
            return String.join("*", factors);
        }

        private void collectNonNumericFactors(Expr expression, List<String> factors) {
            if (expression instanceof NumberExpr) {
                return;
            }
            if (expression instanceof BinaryExpr binary && binary.operator() == BinaryOperator.MUL) {
                collectNonNumericFactors(binary.left(), factors);
                collectNonNumericFactors(binary.right(), factors);
                return;
            }
            factors.add(ExpressionFormatter.format(expression));
        }

        private int nodeCount(Expr expression) {
            if (expression instanceof BinaryExpr binary) {
                return 1 + nodeCount(binary.left()) + nodeCount(binary.right());
            }
            return 1;
        }
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
