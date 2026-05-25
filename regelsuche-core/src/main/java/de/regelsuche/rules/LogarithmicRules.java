package de.regelsuche.rules;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RewriteRule;
import java.util.List;

/**
 * Curated logarithm rewrite rules.
 *
 * <p>Identities like {@code log(a*b) -> log(a) + log(b)} only hold when
 * arguments are positive. Each rule surfaces those side conditions via
 * {@link RewriteRule#assumptions(Expr)}.</p>
 *
 * <p>{@code log} and {@code ln} are treated as the same function family —
 * the same identities apply structurally regardless of base, and both
 * names are supported.</p>
 */
public final class LogarithmicRules {
    private LogarithmicRules() {
    }

    public static List<RewriteRule> rules() {
        return List.of(
            new LogProductRule("log"),
            new LogProductRule("ln"),
            new LogQuotientRule("log"),
            new LogQuotientRule("ln"),
            new LogPowerRule("log"),
            new LogPowerRule("ln"),
            new LogOfOneRule("log"),
            new LogOfOneRule("ln")
        );
    }

    /** {@code log(a*b) -> log(a) + log(b)} with {@code a > 0} and {@code b > 0}. */
    static final class LogProductRule implements RewriteRule {
        private final String name;

        LogProductRule(String name) {
            this.name = name;
        }

        @Override
        public String id() {
            return name + "_product_split";
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
                new FunctionExpr(name, product.left()),
                BinaryOperator.ADD,
                new FunctionExpr(name, product.right())
            );
        }

        @Override
        public List<Assumption> assumptions(Expr subtree) {
            BinaryExpr product = extract(subtree);
            if (product == null) {
                return List.of();
            }
            return List.of(
                Assumption.positive(ExpressionFormatter.format(product.left())),
                Assumption.positive(ExpressionFormatter.format(product.right()))
            );
        }

        private BinaryExpr extract(Expr subtree) {
            if (!(subtree instanceof FunctionExpr functionExpr) || !functionExpr.name().equals(name)) {
                return null;
            }
            if (functionExpr.arguments().size() != 1) {
                return null;
            }
            if (functionExpr.arguments().get(0) instanceof BinaryExpr inner
                && inner.operator() == BinaryOperator.MUL) {
                return inner;
            }
            return null;
        }
    }

    /** {@code log(a/b) -> log(a) - log(b)} with {@code a > 0} and {@code b > 0}. */
    static final class LogQuotientRule implements RewriteRule {
        private final String name;

        LogQuotientRule(String name) {
            this.name = name;
        }

        @Override
        public String id() {
            return name + "_quotient_split";
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
            BinaryExpr quotient = extract(subtree);
            if (quotient == null) {
                throw new IllegalArgumentException("Rule does not match subtree");
            }
            return new BinaryExpr(
                new FunctionExpr(name, quotient.left()),
                BinaryOperator.SUB,
                new FunctionExpr(name, quotient.right())
            );
        }

        @Override
        public List<Assumption> assumptions(Expr subtree) {
            BinaryExpr quotient = extract(subtree);
            if (quotient == null) {
                return List.of();
            }
            return List.of(
                Assumption.positive(ExpressionFormatter.format(quotient.left())),
                Assumption.positive(ExpressionFormatter.format(quotient.right()))
            );
        }

        private BinaryExpr extract(Expr subtree) {
            if (!(subtree instanceof FunctionExpr functionExpr) || !functionExpr.name().equals(name)) {
                return null;
            }
            if (functionExpr.arguments().size() != 1) {
                return null;
            }
            if (functionExpr.arguments().get(0) instanceof BinaryExpr inner
                && inner.operator() == BinaryOperator.DIV) {
                return inner;
            }
            return null;
        }
    }

    /** {@code log(a^k) -> k*log(a)} with {@code a > 0}. */
    static final class LogPowerRule implements RewriteRule {
        private final String name;

        LogPowerRule(String name) {
            this.name = name;
        }

        @Override
        public String id() {
            return name + "_power_to_factor";
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
            BinaryExpr power = extract(subtree);
            if (power == null) {
                throw new IllegalArgumentException("Rule does not match subtree");
            }
            return new BinaryExpr(
                power.right(),
                BinaryOperator.MUL,
                new FunctionExpr(name, power.left())
            );
        }

        @Override
        public List<Assumption> assumptions(Expr subtree) {
            BinaryExpr power = extract(subtree);
            if (power == null) {
                return List.of();
            }
            return List.of(Assumption.positive(ExpressionFormatter.format(power.left())));
        }

        private BinaryExpr extract(Expr subtree) {
            if (!(subtree instanceof FunctionExpr functionExpr) || !functionExpr.name().equals(name)) {
                return null;
            }
            if (functionExpr.arguments().size() != 1) {
                return null;
            }
            if (functionExpr.arguments().get(0) instanceof BinaryExpr inner
                && inner.operator() == BinaryOperator.POW) {
                return inner;
            }
            return null;
        }
    }

    /** {@code log(1) -> 0}. */
    static final class LogOfOneRule implements RewriteRule {
        private final String name;

        LogOfOneRule(String name) {
            this.name = name;
        }

        @Override
        public String id() {
            return name + "_of_one_is_zero";
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
            return subtree instanceof FunctionExpr fn
                && fn.name().equals(name)
                && fn.arguments().size() == 1
                && fn.arguments().get(0) instanceof de.regelsuche.ast.NumberExpr n
                && n.value() == 1.0;
        }

        @Override
        public Expr apply(Expr subtree) {
            if (!matches(subtree)) {
                throw new IllegalArgumentException("Rule does not match subtree");
            }
            return new de.regelsuche.ast.NumberExpr(0);
        }
    }
}
