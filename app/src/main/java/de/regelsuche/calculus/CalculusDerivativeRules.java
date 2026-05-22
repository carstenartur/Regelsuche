package de.regelsuche.calculus;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RewriteRule;
import java.util.List;

/**
 * Calculus derivative rules expressed as ordinary
 * {@link de.regelsuche.transform.RewriteRule} implementations, so that
 * derivatives become first-class citizens of the search graph, the replay
 * overlay, equality saturation and the macro-rule learning loop — exactly
 * the integration the next development stage calls for.
 *
 * <p>The derivative operator is modelled as a binary function expression
 * {@code diff(<body>, <variableName>)}, with the variable always given as
 * a {@link VariableExpr}. The rewrite rules then implement the textbook
 * identities:</p>
 * <ul>
 *   <li>{@code diff(f + g, x) -> diff(f, x) + diff(g, x)} (and {@code -})</li>
 *   <li>{@code diff(f * g, x) -> diff(f, x)*g + f*diff(g, x)} (product rule)</li>
 *   <li>{@code diff(x^n, x) -> n * x^(n-1)} for {@link NumberExpr}
 *       exponents (power rule, the textbook example)</li>
 *   <li>{@code diff(c, x) -> 0} for constants</li>
 *   <li>{@code diff(x, x) -> 1} for the variable itself</li>
 *   <li>{@code diff(sin(x), x) -> cos(x)}, {@code diff(cos(x), x) -> -sin(x)}
 *       (and {@code -} as {@code 0 - sin(x)})</li>
 *   <li>{@code diff(exp(x), x) -> exp(x)}</li>
 *   <li>{@code diff(log(x), x) -> 1/x}, {@code diff(ln(x), x) -> 1/x}</li>
 * </ul>
 *
 * <p>The rules only fire when the derivation variable matches the symbol
 * being differentiated against — that keeps them sound without needing a
 * full chain-rule rewrite (which would introduce free metavariables not
 * yet supported by {@code PatternRewriteRule}).</p>
 */
public final class CalculusDerivativeRules {

    /** Name of the derivative operator function used by these rules. */
    public static final String DIFF = "diff";

    private CalculusDerivativeRules() {
    }

    public static List<RewriteRule> rules() {
        return List.of(
            new DiffOfSum(),
            new DiffOfDifference(),
            new DiffOfProduct(),
            new DiffOfPowerConstant(),
            new DiffOfConstant(),
            new DiffOfVariable(),
            new DiffOfStandardFunction("sin", (arg) -> new FunctionExpr("cos", arg)),
            new DiffOfStandardFunction("cos", (arg) ->
                new BinaryExpr(new NumberExpr(0), BinaryOperator.SUB, new FunctionExpr("sin", arg))),
            new DiffOfStandardFunction("exp", (arg) -> new FunctionExpr("exp", arg)),
            new DiffOfStandardFunction("log", (arg) -> new BinaryExpr(new NumberExpr(1), BinaryOperator.DIV, arg)),
            new DiffOfStandardFunction("ln", (arg) -> new BinaryExpr(new NumberExpr(1), BinaryOperator.DIV, arg))
        );
    }

    /**
     * Wrap {@code body} into a syntactic derivative operator that the
     * rewrite rules in this class match against:
     * {@code diff(body, <variable>)}.
     */
    public static Expr derivative(Expr body, String variable) {
        return new FunctionExpr(DIFF, List.of(body, new VariableExpr(variable)));
    }

    // ---------------------------------------------------------------- shared base

    private abstract static class DiffRuleBase implements RewriteRule {
        @Override
        public final RewriteKind kind() {
            return RewriteKind.SIMPLIFY;
        }

        @Override
        public final boolean mayIncreaseComplexity() {
            return true; // d/dx (f+g) expansion grows the AST
        }

        @Override
        public final int estimatedCostDelta() {
            return -1;
        }

        @Override
        public final boolean isEquivalencePreservingByConstruction() {
            return true;
        }

        /** Returns the body when {@code subtree} is {@code diff(body, var)}; otherwise null. */
        static DiffMatch matchDiff(Expr subtree) {
            if (!(subtree instanceof FunctionExpr fn)
                || !DIFF.equals(fn.name())
                || fn.arguments().size() != 2
                || !(fn.arguments().get(1) instanceof VariableExpr variable)) {
                return null;
            }
            return new DiffMatch(fn.arguments().get(0), variable);
        }
    }

    private record DiffMatch(Expr body, VariableExpr variable) {
    }

    // ---------------------------------------------------------------- rules

    /** {@code diff(f + g, x) -> diff(f, x) + diff(g, x)}. */
    public static final class DiffOfSum extends DiffRuleBase {
        @Override
        public String id() {
            return "calculus_diff_of_sum";
        }

        @Override
        public boolean matches(Expr subtree) {
            DiffMatch m = matchDiff(subtree);
            return m != null && m.body() instanceof BinaryExpr b && b.operator() == BinaryOperator.ADD;
        }

        @Override
        public Expr apply(Expr subtree) {
            DiffMatch m = matchDiff(subtree);
            BinaryExpr body = (BinaryExpr) m.body();
            return new BinaryExpr(
                derivative(body.left(), m.variable().name()),
                BinaryOperator.ADD,
                derivative(body.right(), m.variable().name())
            );
        }
    }

    /** {@code diff(f - g, x) -> diff(f, x) - diff(g, x)}. */
    public static final class DiffOfDifference extends DiffRuleBase {
        @Override
        public String id() {
            return "calculus_diff_of_difference";
        }

        @Override
        public boolean matches(Expr subtree) {
            DiffMatch m = matchDiff(subtree);
            return m != null && m.body() instanceof BinaryExpr b && b.operator() == BinaryOperator.SUB;
        }

        @Override
        public Expr apply(Expr subtree) {
            DiffMatch m = matchDiff(subtree);
            BinaryExpr body = (BinaryExpr) m.body();
            return new BinaryExpr(
                derivative(body.left(), m.variable().name()),
                BinaryOperator.SUB,
                derivative(body.right(), m.variable().name())
            );
        }
    }

    /** {@code diff(f * g, x) -> diff(f, x)*g + f*diff(g, x)} — Leibniz product rule. */
    public static final class DiffOfProduct extends DiffRuleBase {
        @Override
        public String id() {
            return "calculus_diff_of_product";
        }

        @Override
        public boolean matches(Expr subtree) {
            DiffMatch m = matchDiff(subtree);
            return m != null && m.body() instanceof BinaryExpr b && b.operator() == BinaryOperator.MUL;
        }

        @Override
        public Expr apply(Expr subtree) {
            DiffMatch m = matchDiff(subtree);
            BinaryExpr body = (BinaryExpr) m.body();
            String var = m.variable().name();
            Expr left = new BinaryExpr(derivative(body.left(), var), BinaryOperator.MUL, body.right());
            Expr right = new BinaryExpr(body.left(), BinaryOperator.MUL, derivative(body.right(), var));
            return new BinaryExpr(left, BinaryOperator.ADD, right);
        }
    }

    /**
     * Power rule for the textbook case {@code diff(x^n, x) -> n * x^(n-1)}
     * where {@code n} is a literal number and the base is exactly the
     * derivation variable. This is the rule the
     * {@code derivativePowerRuleWorks} test pins.
     */
    public static final class DiffOfPowerConstant extends DiffRuleBase {
        @Override
        public String id() {
            return "calculus_diff_power_rule";
        }

        @Override
        public boolean matches(Expr subtree) {
            DiffMatch m = matchDiff(subtree);
            if (m == null || !(m.body() instanceof BinaryExpr power)
                || power.operator() != BinaryOperator.POW) {
                return false;
            }
            return power.left() instanceof VariableExpr base
                && base.name().equals(m.variable().name())
                && power.right() instanceof NumberExpr;
        }

        @Override
        public Expr apply(Expr subtree) {
            DiffMatch m = matchDiff(subtree);
            BinaryExpr power = (BinaryExpr) m.body();
            NumberExpr exponent = (NumberExpr) power.right();
            double n = exponent.value();
            return new BinaryExpr(
                new NumberExpr(n),
                BinaryOperator.MUL,
                new BinaryExpr(power.left(), BinaryOperator.POW, new NumberExpr(n - 1))
            );
        }
    }

    /** {@code diff(c, x) -> 0} when {@code c} is a {@link NumberExpr}. */
    public static final class DiffOfConstant extends DiffRuleBase {
        @Override
        public String id() {
            return "calculus_diff_of_constant";
        }

        @Override
        public boolean matches(Expr subtree) {
            DiffMatch m = matchDiff(subtree);
            return m != null && m.body() instanceof NumberExpr;
        }

        @Override
        public Expr apply(Expr subtree) {
            return new NumberExpr(0);
        }
    }

    /** {@code diff(x, x) -> 1} (and {@code diff(y, x) -> 0} for foreign symbols). */
    public static final class DiffOfVariable extends DiffRuleBase {
        @Override
        public String id() {
            return "calculus_diff_of_variable";
        }

        @Override
        public boolean matches(Expr subtree) {
            DiffMatch m = matchDiff(subtree);
            return m != null && m.body() instanceof VariableExpr;
        }

        @Override
        public Expr apply(Expr subtree) {
            DiffMatch m = matchDiff(subtree);
            VariableExpr v = (VariableExpr) m.body();
            return new NumberExpr(v.name().equals(m.variable().name()) ? 1 : 0);
        }
    }

    /**
     * Derivative of a unary standard function applied directly to the
     * derivation variable: {@code diff(f(x), x) -> f'(x)} for
     * {@code f ∈ {sin, cos, exp, log, ln}}.
     */
    public static final class DiffOfStandardFunction extends DiffRuleBase {
        private final String functionName;
        private final java.util.function.Function<Expr, Expr> derivativeFactory;

        DiffOfStandardFunction(String functionName, java.util.function.Function<Expr, Expr> derivativeFactory) {
            this.functionName = functionName;
            this.derivativeFactory = derivativeFactory;
        }

        @Override
        public String id() {
            return "calculus_diff_of_" + functionName;
        }

        @Override
        public boolean matches(Expr subtree) {
            DiffMatch m = matchDiff(subtree);
            if (m == null || !(m.body() instanceof FunctionExpr fn)) {
                return false;
            }
            return functionName.equals(fn.name())
                && fn.arguments().size() == 1
                && fn.arguments().get(0) instanceof VariableExpr inner
                && inner.name().equals(m.variable().name());
        }

        @Override
        public Expr apply(Expr subtree) {
            DiffMatch m = matchDiff(subtree);
            FunctionExpr fn = (FunctionExpr) m.body();
            return derivativeFactory.apply(fn.arguments().get(0));
        }
    }
}
