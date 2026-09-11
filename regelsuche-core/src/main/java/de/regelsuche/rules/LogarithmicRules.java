package de.regelsuche.rules;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.RequiredAssumptionTemplate;
import de.regelsuche.transform.RewriteApplicabilitySchema;
import de.regelsuche.transform.RewriteApplicabilitySchemaProvider;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RewriteRule;
import java.util.List;

/** Curated logarithm rewrite rules with explicit domain assumptions. */
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
            new LogOfOneRule("ln"));
    }

    /** {@code log(a*b) -> log(a) + log(b)} with {@code a > 0, b > 0}. */
    static final class LogProductRule implements RewriteApplicabilitySchemaProvider {
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
                new FunctionExpr(name, product.right()));
        }

        @Override
        public boolean mayEmitAssumptions() {
            return true;
        }

        @Override
        public List<Assumption> assumptions(Expr subtree) {
            BinaryExpr product = extract(subtree);
            return product == null ? List.of() : List.of(
                Assumption.positive(ExpressionFormatter.format(product.left())),
                Assumption.positive(ExpressionFormatter.format(product.right())));
        }

        @Override
        public RewriteApplicabilitySchema applicabilitySchema() {
            PatternExpr left = PatternExpr.var("A");
            PatternExpr right = PatternExpr.var("B");
            return exactSource(
                PatternExpr.fn(name,
                    PatternExpr.op(BinaryOperator.MUL, left, right)),
                RequiredAssumptionTemplate.positive(left),
                RequiredAssumptionTemplate.positive(right));
        }

        private BinaryExpr extract(Expr subtree) {
            if (!(subtree instanceof FunctionExpr function)
                    || !function.name().equals(name)
                    || function.arguments().size() != 1) {
                return null;
            }
            return function.arguments().getFirst() instanceof BinaryExpr inner
                    && inner.operator() == BinaryOperator.MUL
                ? inner
                : null;
        }
    }

    /** {@code log(a/b) -> log(a) - log(b)} with {@code a > 0, b > 0}. */
    static final class LogQuotientRule implements RewriteApplicabilitySchemaProvider {
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
                new FunctionExpr(name, quotient.right()));
        }

        @Override
        public boolean mayEmitAssumptions() {
            return true;
        }

        @Override
        public List<Assumption> assumptions(Expr subtree) {
            BinaryExpr quotient = extract(subtree);
            return quotient == null ? List.of() : List.of(
                Assumption.positive(ExpressionFormatter.format(quotient.left())),
                Assumption.positive(ExpressionFormatter.format(quotient.right())));
        }

        @Override
        public RewriteApplicabilitySchema applicabilitySchema() {
            PatternExpr numerator = PatternExpr.var("A");
            PatternExpr denominator = PatternExpr.var("B");
            return exactSource(
                PatternExpr.fn(name,
                    PatternExpr.op(BinaryOperator.DIV, numerator, denominator)),
                RequiredAssumptionTemplate.positive(numerator),
                RequiredAssumptionTemplate.positive(denominator));
        }

        private BinaryExpr extract(Expr subtree) {
            if (!(subtree instanceof FunctionExpr function)
                    || !function.name().equals(name)
                    || function.arguments().size() != 1) {
                return null;
            }
            return function.arguments().getFirst() instanceof BinaryExpr inner
                    && inner.operator() == BinaryOperator.DIV
                ? inner
                : null;
        }
    }

    /** {@code log(a^k) -> k*log(a)} with {@code a > 0}. */
    static final class LogPowerRule implements RewriteApplicabilitySchemaProvider {
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
                new FunctionExpr(name, power.left()));
        }

        @Override
        public boolean mayEmitAssumptions() {
            return true;
        }

        @Override
        public List<Assumption> assumptions(Expr subtree) {
            BinaryExpr power = extract(subtree);
            return power == null
                ? List.of()
                : List.of(Assumption.positive(
                    ExpressionFormatter.format(power.left())));
        }

        @Override
        public RewriteApplicabilitySchema applicabilitySchema() {
            PatternExpr base = PatternExpr.var("A");
            return exactSource(
                PatternExpr.fn(name, PatternExpr.op(
                    BinaryOperator.POW, base, PatternExpr.var("K"))),
                RequiredAssumptionTemplate.positive(base));
        }

        private BinaryExpr extract(Expr subtree) {
            if (!(subtree instanceof FunctionExpr function)
                    || !function.name().equals(name)
                    || function.arguments().size() != 1) {
                return null;
            }
            return function.arguments().getFirst() instanceof BinaryExpr inner
                    && inner.operator() == BinaryOperator.POW
                ? inner
                : null;
        }
    }

    /** {@code log(1) -> 0}. */
    static final class LogOfOneRule implements RewriteApplicabilitySchemaProvider {
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
                && fn.arguments().getFirst() instanceof de.regelsuche.ast.NumberExpr n
                && n.value().equalsInteger(1);
        }

        @Override
        public Expr apply(Expr subtree) {
            if (!matches(subtree)) {
                throw new IllegalArgumentException("Rule does not match subtree");
            }
            return new de.regelsuche.ast.NumberExpr(0);
        }

        @Override
        public RewriteApplicabilitySchema applicabilitySchema() {
            return exactSource(PatternExpr.fn(name, PatternExpr.num(1)));
        }
    }
}
