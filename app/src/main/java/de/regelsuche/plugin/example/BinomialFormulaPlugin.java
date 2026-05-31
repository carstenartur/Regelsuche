package de.regelsuche.plugin.example;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.plugin.AstVisitorContext;
import de.regelsuche.plugin.AstVisitorPhase;
import de.regelsuche.plugin.AstVisitorPlugin;
import de.regelsuche.plugin.AstVisitorRegistry;
import de.regelsuche.plugin.MacroRegistry;
import de.regelsuche.plugin.PatternBasedTransformation;
import de.regelsuche.plugin.RegelsuchePlugin;
import de.regelsuche.plugin.RuleMacro;
import de.regelsuche.plugin.RuleRegistry;
import de.regelsuche.plugin.TransformationRegistry;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RewriteKind;
import java.util.List;

public final class BinomialFormulaPlugin implements RegelsuchePlugin {
    private static final PatternExpr A = PatternExpr.var("A");
    private static final PatternExpr B = PatternExpr.var("B");

    @Override
    public String id() {
        return "binomial-formulas";
    }

    @Override
    public String name() {
        return "Binomial Formulas";
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public void registerRules(RuleRegistry registry) {
        registry.register(new PatternRewriteRule(
            "binomial_difference_of_squares",
            PatternExpr.op(BinaryOperator.SUB, PatternExpr.op(BinaryOperator.POW, A, PatternExpr.num(2)),
                PatternExpr.op(BinaryOperator.POW, B, PatternExpr.num(2))),
            PatternExpr.op(BinaryOperator.MUL,
                PatternExpr.op(BinaryOperator.SUB, A, B),
                PatternExpr.op(BinaryOperator.ADD, A, B)),
            RewriteKind.FACTOR,
            false,
            -4,
            true
        ), id(), "Erkennt die Differenz zweier Quadrate.", List.of("binomial", "factorization"));
    }

    @Override
    public void registerTransformations(TransformationRegistry registry) {
        registry.register(new PatternBasedTransformation(
            "binomial_square_forward",
            PatternExpr.op(BinaryOperator.POW,
                PatternExpr.op(BinaryOperator.ADD, A, B),
                PatternExpr.num(2)),
            PatternExpr.op(BinaryOperator.ADD,
                PatternExpr.op(BinaryOperator.ADD,
                    PatternExpr.op(BinaryOperator.POW, A, PatternExpr.num(2)),
                    PatternExpr.op(BinaryOperator.MUL,
                        PatternExpr.op(BinaryOperator.MUL, PatternExpr.num(2), A),
                        B)),
                PatternExpr.op(BinaryOperator.POW, B, PatternExpr.num(2))),
            RewriteKind.EXPAND,
            true,
            4,
            true,
            "Erweitert die erste binomische Formel."
        ), id(), "Erweitert (A + B)^2.", List.of("binomial", "expansion"));
        registry.register(new PatternBasedTransformation(
            "binomial_square_backward",
            PatternExpr.op(BinaryOperator.ADD,
                PatternExpr.op(BinaryOperator.ADD,
                    PatternExpr.op(BinaryOperator.POW, A, PatternExpr.num(2)),
                    PatternExpr.op(BinaryOperator.MUL,
                        PatternExpr.op(BinaryOperator.MUL, PatternExpr.num(2), A),
                        B)),
                PatternExpr.op(BinaryOperator.POW, B, PatternExpr.num(2))),
            PatternExpr.op(BinaryOperator.POW,
                PatternExpr.op(BinaryOperator.ADD, A, B),
                PatternExpr.num(2)),
            RewriteKind.FACTOR,
            false,
            -4,
            true,
            "Faktorisiert die erste binomische Formel."
        ), id(), "Faktorisiert A^2 + 2AB + B^2.", List.of("binomial", "factorization"));
        registry.register(new PatternBasedTransformation(
            "binomial_square_minus_forward",
            PatternExpr.op(BinaryOperator.POW,
                PatternExpr.op(BinaryOperator.SUB, A, B),
                PatternExpr.num(2)),
            PatternExpr.op(BinaryOperator.ADD,
                PatternExpr.op(BinaryOperator.SUB,
                    PatternExpr.op(BinaryOperator.POW, A, PatternExpr.num(2)),
                    PatternExpr.op(BinaryOperator.MUL,
                        PatternExpr.op(BinaryOperator.MUL, PatternExpr.num(2), A),
                        B)),
                PatternExpr.op(BinaryOperator.POW, B, PatternExpr.num(2))),
            RewriteKind.EXPAND,
            true,
            4,
            true,
            "Erweitert die zweite binomische Formel."
        ), id(), "Erweitert (A - B)^2.", List.of("binomial", "expansion"));
    }

    @Override
    public void registerVisitors(AstVisitorRegistry registry) {
        registry.register(new BinomialPatternVisitor(), id());
    }

    @Override
    public void registerMacros(MacroRegistry registry) {
        registry.register(new RuleMacro(
            "expand_square",
            "(A + B)^2",
            "A^2 + 2*A*B + B^2",
            "Makro für die erste binomische Formel.",
            List.of("binomial", "macro")
        ), id());
    }

    private static final class BinomialPatternVisitor implements AstVisitorPlugin {
        @Override
        public String id() {
            return "binomial-pattern-visitor";
        }

        @Override
        public AstVisitorPhase phase() {
            return AstVisitorPhase.BEFORE_SEARCH;
        }

        @Override
        public void visit(Expr root, AstVisitorContext context) {
            inspect(root, context);
        }

        private void inspect(Expr node, AstVisitorContext context) {
            if (isBinomialSquare(node)) {
                context.mark("binomial-square");
                context.putMetadata(node, "binomial", true);
                context.report(id(), "Detected a binomial square candidate.");
            }
            if (node instanceof BinaryExpr binaryExpr) {
                inspect(binaryExpr.left(), context);
                inspect(binaryExpr.right(), context);
            }
        }

        private boolean isBinomialSquare(Expr node) {
            if (!(node instanceof BinaryExpr outer) || outer.operator() != BinaryOperator.POW) {
                return false;
            }
            if (!(outer.right() instanceof de.regelsuche.ast.NumberExpr exponent) || exponent.value() != 2) {
                return false;
            }
            return outer.left() instanceof BinaryExpr inner
                && (inner.operator() == BinaryOperator.ADD || inner.operator() == BinaryOperator.SUB);
        }
    }
}
