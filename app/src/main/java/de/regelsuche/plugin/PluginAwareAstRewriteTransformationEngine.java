package de.regelsuche.plugin;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class PluginAwareAstRewriteTransformationEngine implements TransformationEngine {
    private static final int DEFAULT_MAX_AST_SIZE_INCREASE = 12;
    private static final int DEFAULT_MAX_CANDIDATES_PER_STATE = 80;

    private final ExpressionParser parser = new ExpressionParser();
    private final ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();
    private final List<RewriteRule> rules;
    private final AstVisitorRegistry visitorRegistry;
    private final int maxAstSizeIncreasePerStep;
    private final int maxCandidatesPerState;
    private List<AstVisitorContext.VisitorDiagnostic> lastVisitorDiagnostics = List.of();

    public PluginAwareAstRewriteTransformationEngine(List<RewriteRule> rules, AstVisitorRegistry visitorRegistry) {
        this(rules, visitorRegistry, DEFAULT_MAX_AST_SIZE_INCREASE, DEFAULT_MAX_CANDIDATES_PER_STATE);
    }

    public PluginAwareAstRewriteTransformationEngine(
        List<RewriteRule> rules,
        AstVisitorRegistry visitorRegistry,
        int maxAstSizeIncreasePerStep,
        int maxCandidatesPerState
    ) {
        this.rules = List.copyOf(rules);
        this.visitorRegistry = visitorRegistry;
        this.maxAstSizeIncreasePerStep = maxAstSizeIncreasePerStep;
        this.maxCandidatesPerState = maxCandidatesPerState;
    }

    public List<RewriteRule> rules() {
        return rules;
    }

    public List<AstVisitorContext.VisitorDiagnostic> lastVisitorDiagnostics() {
        return lastVisitorDiagnostics;
    }

    @Override
    public List<Transformation> transform(String expression) {
        Expr root;
        try {
            root = parser.parse(new InputRequest(InputType.TERM, expression)).terms().getFirst();
        } catch (IllegalArgumentException ex) {
            return List.of();
        }
        AstVisitorContext context = new AstVisitorContext();
        visitorRegistry.execute(AstVisitorPhase.AFTER_PARSE, root, context);
        visitorRegistry.execute(AstVisitorPhase.BEFORE_NORMALIZATION, root, context);
        visitorRegistry.execute(AstVisitorPhase.AFTER_NORMALIZATION, root, context);
        visitorRegistry.execute(AstVisitorPhase.BEFORE_SEARCH, root, context);
        String formattedInput = ExpressionFormatter.format(root);
        int originalSize = canonicalizer.astNodeCount(formattedInput);
        Set<Transformation> transformations = new LinkedHashSet<>();
        for (RewriteResult result : rewriteEverywhere(root, context)) {
            String formatted = ExpressionFormatter.format(result.expression());
            if (formatted.equals(formattedInput)) {
                continue;
            }
            int growth = canonicalizer.astNodeCount(formatted) - originalSize;
            if (growth > maxAstSizeIncreasePerStep) {
                continue;
            }
            RewriteRule rule = result.rule();
            context.setLastRuleId(rule.id());
            visitorRegistry.execute(AstVisitorPhase.AFTER_TRANSFORMATION, result.expression(), context);
            transformations.add(new Transformation(
                rule.id(),
                formatted,
                rule.kind(),
                rule.mayIncreaseComplexity(),
                rule.estimatedCostDelta(),
                rule.isEquivalencePreservingByConstruction(),
                rule.id() + ":" + result.sourceSubtreeHash()
            ));
            if (transformations.size() >= maxCandidatesPerState) {
                break;
            }
        }
        visitorRegistry.execute(AstVisitorPhase.BEFORE_OUTPUT, root, context);
        visitorRegistry.execute(AstVisitorPhase.EXPLAIN_PATH, root, context);
        lastVisitorDiagnostics = context.diagnostics();
        return new ArrayList<>(transformations);
    }

    private List<RewriteResult> rewriteEverywhere(Expr subtree, AstVisitorContext visitorContext) {
        List<RewriteResult> results = new ArrayList<>();
        String subtreeHash = canonicalizer.stableHash(ExpressionFormatter.format(subtree));
        for (RewriteRule rule : rules) {
            visitorRegistry.execute(AstVisitorPhase.DURING_SEARCH, subtree, visitorContext);
            Expr rewritten = applyRule(rule, subtree, visitorContext);
            if (rewritten != null && !rewritten.equals(subtree)) {
                results.add(new RewriteResult(rule, rewritten, subtreeHash));
            }
        }
        if (subtree instanceof BinaryExpr binaryExpr) {
            for (RewriteResult leftRewrite : rewriteEverywhere(binaryExpr.left(), visitorContext)) {
                results.add(new RewriteResult(
                    leftRewrite.rule(),
                    new BinaryExpr(leftRewrite.expression(), binaryExpr.operator(), binaryExpr.right()),
                    leftRewrite.sourceSubtreeHash()
                ));
            }
            for (RewriteResult rightRewrite : rewriteEverywhere(binaryExpr.right(), visitorContext)) {
                results.add(new RewriteResult(
                    rightRewrite.rule(),
                    new BinaryExpr(binaryExpr.left(), binaryExpr.operator(), rightRewrite.expression()),
                    rightRewrite.sourceSubtreeHash()
                ));
            }
        } else if (subtree instanceof FunctionExpr functionExpr) {
            List<Expr> arguments = functionExpr.arguments();
            for (int index = 0; index < arguments.size(); index++) {
                final int position = index;
                for (RewriteResult argRewrite : rewriteEverywhere(arguments.get(index), visitorContext)) {
                    List<Expr> replaced = new ArrayList<>(arguments);
                    replaced.set(position, argRewrite.expression());
                    results.add(new RewriteResult(
                        argRewrite.rule(),
                        new FunctionExpr(functionExpr.name(), replaced),
                        argRewrite.sourceSubtreeHash()
                    ));
                }
            }
        }
        return results;
    }

    private Expr applyRule(RewriteRule rule, Expr subtree, AstVisitorContext visitorContext) {
        if (rule instanceof PatternTransformation transformation) {
            TransformationMatchContext matchContext = TransformationMatchContext.from(visitorContext, subtree);
            if (!transformation.matches(subtree, matchContext)) {
                return null;
            }
            return transformation.transform(subtree, TransformationContext.from(visitorContext, subtree));
        }
        if (!rule.matches(subtree)) {
            return null;
        }
        return rule.apply(subtree);
    }

    private record RewriteResult(RewriteRule rule, Expr expression, String sourceSubtreeHash) {
    }
}
