package de.regelsuche.rules;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RewriteRule;
import java.util.List;

/**
 * Curated trigonometric rewrite rules.
 *
 * <p>All rules are atomic and pattern-based; identities like
 * {@code sin(A)^2 + cos(A)^2 -> 1} are encoded directly so the search engine
 * can apply them. None of the rules introduce side conditions beyond what
 * trigonometric functions are defined for on the reals.</p>
 */
public final class TrigonometricRules {
    private TrigonometricRules() {
    }

    public static List<RewriteRule> rules() {
        PatternExpr a = PatternExpr.var("A");
        PatternExpr sinA = PatternExpr.fn("sin", a);
        PatternExpr cosA = PatternExpr.fn("cos", a);
        PatternExpr sinSquared = PatternExpr.op(BinaryOperator.POW, sinA, PatternExpr.num(2));
        PatternExpr cosSquared = PatternExpr.op(BinaryOperator.POW, cosA, PatternExpr.num(2));
        return List.of(
            // sin(A)^2 + cos(A)^2 -> 1
            new PatternRewriteRule(
                "trig_pythagorean_sin_cos",
                PatternExpr.op(BinaryOperator.ADD, sinSquared, cosSquared),
                PatternExpr.num(1),
                RewriteKind.SIMPLIFY,
                false,
                -4,
                true
            ),
            // cos(A)^2 + sin(A)^2 -> 1 (different argument order)
            new PatternRewriteRule(
                "trig_pythagorean_cos_sin",
                PatternExpr.op(BinaryOperator.ADD, cosSquared, sinSquared),
                PatternExpr.num(1),
                RewriteKind.SIMPLIFY,
                false,
                -4,
                true
            ),
            // 1 - sin(A)^2 -> cos(A)^2
            new PatternRewriteRule(
                "trig_one_minus_sin_squared",
                PatternExpr.op(BinaryOperator.SUB, PatternExpr.num(1), sinSquared),
                cosSquared,
                RewriteKind.NORMALIZE,
                false,
                0,
                true
            ),
            // 1 - cos(A)^2 -> sin(A)^2
            new PatternRewriteRule(
                "trig_one_minus_cos_squared",
                PatternExpr.op(BinaryOperator.SUB, PatternExpr.num(1), cosSquared),
                sinSquared,
                RewriteKind.NORMALIZE,
                false,
                0,
                true
            ),
            // tan(A) -> sin(A) / cos(A) — only useful for derivations, surfaces an assumption
            new TanToSinOverCosRule(),
            // sin(2*A) -> 2*sin(A)*cos(A)
            new PatternRewriteRule(
                "trig_double_angle_sin",
                PatternExpr.fn("sin", PatternExpr.op(BinaryOperator.MUL, PatternExpr.num(2), a)),
                PatternExpr.op(BinaryOperator.MUL,
                    PatternExpr.op(BinaryOperator.MUL, PatternExpr.num(2), sinA),
                    cosA),
                RewriteKind.EXPAND,
                true,
                4,
                true
            ),
            // cos(2*A) -> cos(A)^2 - sin(A)^2
            new PatternRewriteRule(
                "trig_double_angle_cos",
                PatternExpr.fn("cos", PatternExpr.op(BinaryOperator.MUL, PatternExpr.num(2), a)),
                PatternExpr.op(BinaryOperator.SUB, cosSquared, sinSquared),
                RewriteKind.EXPAND,
                true,
                4,
                true
            )
        );
    }

    /**
     * {@code tan(A) -> sin(A) / cos(A)} with the assumption {@code cos(A) != 0}.
     */
    static final class TanToSinOverCosRule implements RewriteRule {
        @Override
        public String id() {
            return "trig_tan_to_sin_over_cos";
        }

        @Override
        public RewriteKind kind() {
            return RewriteKind.NORMALIZE;
        }

        @Override
        public boolean mayIncreaseComplexity() {
            return true;
        }

        @Override
        public int estimatedCostDelta() {
            return 2;
        }

        @Override
        public boolean isEquivalencePreservingByConstruction() {
            return true;
        }

        @Override
        public boolean matches(Expr subtree) {
            return subtree instanceof FunctionExpr functionExpr
                && "tan".equals(functionExpr.name())
                && functionExpr.arguments().size() == 1;
        }

        @Override
        public Expr apply(Expr subtree) {
            FunctionExpr tan = (FunctionExpr) subtree;
            Expr argument = tan.arguments().get(0);
            return new BinaryExpr(
                new FunctionExpr("sin", argument),
                BinaryOperator.DIV,
                new FunctionExpr("cos", argument)
            );
        }

        @Override
        public List<Assumption> assumptions(Expr subtree) {
            if (!(subtree instanceof FunctionExpr tan) || !"tan".equals(tan.name()) || tan.arguments().isEmpty()) {
                return List.of();
            }
            Expr argument = tan.arguments().get(0);
            // Symbol payload contains the function-call form `cos(arg)`. The SMT
            // bridge parses function calls via `toSmtExpr`, the Lean bridge
            // emits the expression text — both targets render this correctly.
            return List.of(Assumption.nonZero("cos(" + ExpressionFormatter.format(argument) + ")"));
        }
    }

    /**
     * Helper used by {@link RuleDomainRegistry} for the simple
     * pattern rules above. Not used directly outside this package.
     */
    @SuppressWarnings("unused")
    private static NumberExpr numberHelperPlaceholder() {
        return new NumberExpr(0);
    }
}
