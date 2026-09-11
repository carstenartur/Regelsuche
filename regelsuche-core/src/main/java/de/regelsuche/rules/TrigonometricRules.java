package de.regelsuche.rules;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RequiredAssumptionTemplate;
import de.regelsuche.transform.RewriteApplicabilitySchema;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.RewriteRule.RewriteApplicabilitySchemaProvider;
import java.util.List;

/** Curated trigonometric rewrite rules. */
public final class TrigonometricRules {
    private TrigonometricRules() {}

    public static List<RewriteRule> rules() {
        PatternExpr a = PatternExpr.var("A");
        PatternExpr sinA = PatternExpr.fn("sin", a);
        PatternExpr cosA = PatternExpr.fn("cos", a);
        PatternExpr sinSquared = PatternExpr.op(BinaryOperator.POW, sinA, PatternExpr.num(2));
        PatternExpr cosSquared = PatternExpr.op(BinaryOperator.POW, cosA, PatternExpr.num(2));
        return List.of(
            new PatternRewriteRule("trig_pythagorean_sin_cos",
                PatternExpr.op(BinaryOperator.ADD, sinSquared, cosSquared),
                PatternExpr.num(1), RewriteKind.SIMPLIFY, false, -4, true),
            new PatternRewriteRule("trig_pythagorean_cos_sin",
                PatternExpr.op(BinaryOperator.ADD, cosSquared, sinSquared),
                PatternExpr.num(1), RewriteKind.SIMPLIFY, false, -4, true),
            new PatternRewriteRule("trig_one_minus_sin_squared",
                PatternExpr.op(BinaryOperator.SUB, PatternExpr.num(1), sinSquared),
                cosSquared, RewriteKind.NORMALIZE, false, 0, true),
            new PatternRewriteRule("trig_one_minus_cos_squared",
                PatternExpr.op(BinaryOperator.SUB, PatternExpr.num(1), cosSquared),
                sinSquared, RewriteKind.NORMALIZE, false, 0, true),
            new TanToSinOverCosRule(),
            new PatternRewriteRule("trig_double_angle_sin",
                PatternExpr.fn("sin", PatternExpr.op(BinaryOperator.MUL, PatternExpr.num(2), a)),
                PatternExpr.op(BinaryOperator.MUL,
                    PatternExpr.op(BinaryOperator.MUL, PatternExpr.num(2), sinA), cosA),
                RewriteKind.EXPAND, true, 4, true),
            new PatternRewriteRule("trig_double_angle_cos",
                PatternExpr.fn("cos", PatternExpr.op(BinaryOperator.MUL, PatternExpr.num(2), a)),
                PatternExpr.op(BinaryOperator.SUB, cosSquared, sinSquared),
                RewriteKind.EXPAND, true, 4, true));
    }

    /** {@code tan(A) -> sin(A) / cos(A)} with {@code cos(A) != 0}. */
    static final class TanToSinOverCosRule implements RewriteApplicabilitySchemaProvider {
        @Override
        public String id() { return "trig_tan_to_sin_over_cos"; }
        @Override
        public RewriteKind kind() { return RewriteKind.NORMALIZE; }
        @Override
        public boolean mayIncreaseComplexity() { return true; }
        @Override
        public int estimatedCostDelta() { return 2; }
        @Override
        public boolean isEquivalencePreservingByConstruction() { return true; }
        @Override
        public boolean matches(Expr subtree) {
            return subtree instanceof FunctionExpr function
                && "tan".equals(function.name()) && function.arguments().size() == 1;
        }
        @Override
        public Expr apply(Expr subtree) {
            FunctionExpr tan = (FunctionExpr) subtree;
            Expr argument = tan.arguments().getFirst();
            return new BinaryExpr(new FunctionExpr("sin", argument), BinaryOperator.DIV,
                new FunctionExpr("cos", argument));
        }
        @Override
        public boolean mayEmitAssumptions() { return true; }
        @Override
        public List<Assumption> assumptions(Expr subtree) {
            if (!matches(subtree)) return List.of();
            Expr argument = ((FunctionExpr) subtree).arguments().getFirst();
            return List.of(Assumption.nonZero(
                "cos(" + ExpressionFormatter.format(argument) + ")"));
        }
        @Override
        public RewriteApplicabilitySchema applicabilitySchema() {
            PatternExpr argument = PatternExpr.var("A");
            return exactSource(PatternExpr.fn("tan", argument),
                RequiredAssumptionTemplate.nonZero(PatternExpr.fn("cos", argument)));
        }
    }
}
