package de.regelsuche.plugin.example;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.plugin.AstVisitorContext;
import de.regelsuche.plugin.AstVisitorPhase;
import de.regelsuche.plugin.AstVisitorPlugin;
import de.regelsuche.plugin.AstVisitorRegistry;
import de.regelsuche.plugin.CostFunction;
import de.regelsuche.plugin.CostFunctionRegistry;
import de.regelsuche.plugin.ExamplePackage;
import de.regelsuche.plugin.ExampleRegistry;
import de.regelsuche.plugin.ExplanationProvider;
import de.regelsuche.plugin.ExplanationRegistry;
import de.regelsuche.plugin.Heuristic;
import de.regelsuche.plugin.HeuristicRegistry;
import de.regelsuche.plugin.MacroRegistry;
import de.regelsuche.plugin.ParserExtension;
import de.regelsuche.plugin.ParserExtensionRegistry;
import de.regelsuche.plugin.PatternBasedTransformation;
import de.regelsuche.plugin.RegelsuchePlugin;
import de.regelsuche.plugin.Renderer;
import de.regelsuche.plugin.RendererRegistry;
import de.regelsuche.plugin.RuleMacro;
import de.regelsuche.plugin.RuleRegistry;
import de.regelsuche.plugin.SearchStrategy;
import de.regelsuche.plugin.SearchStrategyRegistry;
import de.regelsuche.plugin.TransformationRegistry;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
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

    @Override
    public void registerSearchStrategies(SearchStrategyRegistry registry) {
        registry.register(new SearchStrategy() {
            @Override
            public String id() {
                return "binomial-guided-search";
            }

            @Override
            public String name() {
                return "Binomial guided search";
            }

            @Override
            public String description() {
                return "Prioritises binomial expansion and factorisation examples.";
            }

            @Override
            public List<String> tags() {
                return List.of("binomial", "search");
            }
        }, id());
    }

    @Override
    public void registerHeuristics(HeuristicRegistry registry) {
        registry.register(new Heuristic() {
            @Override
            public String id() {
                return "binomial-pattern-heuristic";
            }

            @Override
            public int score(String expression) {
                return expression.contains("^2") ? 10 : 0;
            }

            @Override
            public List<String> tags() {
                return List.of("binomial", "heuristic");
            }
        }, id());
    }

    @Override
    public void registerCostFunctions(CostFunctionRegistry registry) {
        registry.register(new CostFunction() {
            @Override
            public String id() {
                return "binomial-cost-delta";
            }

            @Override
            public int cost(Transformation transformation) {
                return transformation.estimatedCostDelta();
            }

            @Override
            public List<String> tags() {
                return List.of("binomial", "cost");
            }
        }, id());
    }

    @Override
    public void registerRenderers(RendererRegistry registry) {
        registry.register(new Renderer() {
            @Override
            public String id() {
                return "binomial-text-renderer";
            }

            @Override
            public boolean supports(String format) {
                return "text".equalsIgnoreCase(format);
            }

            @Override
            public String render(String expression) {
                return expression;
            }

            @Override
            public List<String> tags() {
                return List.of("binomial", "renderer");
            }
        }, id());
    }

    @Override
    public void registerExplanations(ExplanationRegistry registry) {
        registry.register(new ExplanationProvider() {
            @Override
            public String id() {
                return "binomial-explanations";
            }

            @Override
            public boolean supportsRule(String ruleId) {
                return ruleId.startsWith("binomial_") || ruleId.startsWith("macro.expand_square");
            }

            @Override
            public String explain(String ruleId, String expression) {
                return "Binomial rule '" + ruleId + "' applies to " + expression + ".";
            }

            @Override
            public List<String> tags() {
                return List.of("binomial", "explanation");
            }
        }, id());
    }

    @Override
    public void registerParserExtensions(ParserExtensionRegistry registry) {
        registry.register(new ParserExtension() {
            @Override
            public String id() {
                return "unicode-square-parser";
            }

            @Override
            public boolean supports(String input) {
                return input.contains("²");
            }

            @Override
            public String normalize(String input) {
                return input.replace("²", "^2");
            }

            @Override
            public List<String> tags() {
                return List.of("binomial", "parser");
            }
        }, id());
    }

    @Override
    public void registerExamples(ExampleRegistry registry) {
        registry.register(new ExamplePackage(
            "binomial-examples",
            "Binomial formula examples",
            List.of(
                new ExamplePackage.ExampleEntry("(a+b)^2 expansion", "(a + b)^2", "a^2 + 2*a*b + b^2"),
                new ExamplePackage.ExampleEntry("difference of squares", "a^2 - b^2", "(a - b)*(a + b)")
            ),
            List.of("binomial", "examples")
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
