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
    private final List<RuleDebugMetadata> debugMetadata;
    private List<AstVisitorContext.VisitorDiagnostic> lastVisitorDiagnostics = List.of();
    private boolean debugMode = false;
    private RuleDebugReport lastDebugReport;

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
        this.debugMetadata = List.of();
    }

    PluginAwareAstRewriteTransformationEngine(
        List<RewriteRule> rules,
        AstVisitorRegistry visitorRegistry,
        int maxAstSizeIncreasePerStep,
        int maxCandidatesPerState,
        List<RuleDebugMetadata> debugMetadata
    ) {
        this.rules = List.copyOf(rules);
        this.visitorRegistry = visitorRegistry;
        this.maxAstSizeIncreasePerStep = maxAstSizeIncreasePerStep;
        this.maxCandidatesPerState = maxCandidatesPerState;
        this.debugMetadata = List.copyOf(debugMetadata);
    }

    public List<RewriteRule> rules() {
        return rules;
    }

    public List<AstVisitorContext.VisitorDiagnostic> lastVisitorDiagnostics() {
        return lastVisitorDiagnostics;
    }

    public void enableDebugMode() {
        this.debugMode = true;
    }

    public void disableDebugMode() {
        this.debugMode = false;
    }

    public boolean isDebugMode() {
        return debugMode;
    }

    public RuleDebugReport lastDebugReport() {
        return lastDebugReport;
    }

    @Override
    public List<Transformation> transform(String expression) {
        Expr root;
        lastDebugReport = null;
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
        List<RuleAttempt> attempts = debugMode ? new ArrayList<>() : null;
        if (attempts != null) {
            appendRuntimeDebugAttempts(attempts);
        }
        Set<Transformation> transformations = new LinkedHashSet<>();
        for (RewriteResult result : rewriteEverywhere(root, context, attempts)) {
            String formatted = ExpressionFormatter.format(result.expression());
            if (formatted.equals(formattedInput)) {
                continue;
            }
            int growth = canonicalizer.astNodeCount(formatted) - originalSize;
            if (growth > maxAstSizeIncreasePerStep) {
                if (attempts != null) {
                    attempts.add(new RuleAttempt(
                        result.rule().id(),
                        formatted,
                        "SEARCH",
                        false,
                        RuleRejectionReason.GROWTH_LIMIT_EXCEEDED
                    ));
                }
                continue;
            }
            if (transformations.size() >= maxCandidatesPerState) {
                if (attempts != null) {
                    attempts.add(new RuleAttempt(
                        result.rule().id(),
                        formatted,
                        "SEARCH",
                        false,
                        RuleRejectionReason.CANDIDATE_LIMIT_REACHED
                    ));
                }
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
        }
        visitorRegistry.execute(AstVisitorPhase.BEFORE_OUTPUT, root, context);
        visitorRegistry.execute(AstVisitorPhase.EXPLAIN_PATH, root, context);
        lastVisitorDiagnostics = context.diagnostics();
        if (attempts != null) {
            lastDebugReport = buildDebugReport(expression, attempts);
        }
        return new ArrayList<>(transformations);
    }

    private List<RewriteResult> rewriteEverywhere(
        Expr subtree,
        AstVisitorContext visitorContext,
        List<RuleAttempt> attempts
    ) {
        List<RewriteResult> results = new ArrayList<>();
        String subtreeHash = canonicalizer.stableHash(ExpressionFormatter.format(subtree));
        for (RewriteRule rule : rules) {
            visitorRegistry.execute(AstVisitorPhase.DURING_SEARCH, subtree, visitorContext);
            Expr rewritten = applyRule(rule, subtree, visitorContext, attempts);
            if (rewritten != null && !rewritten.equals(subtree)) {
                results.add(new RewriteResult(rule, rewritten, subtreeHash));
            }
        }
        if (subtree instanceof BinaryExpr binaryExpr) {
            for (RewriteResult leftRewrite : rewriteEverywhere(binaryExpr.left(), visitorContext, attempts)) {
                results.add(new RewriteResult(
                    leftRewrite.rule(),
                    new BinaryExpr(leftRewrite.expression(), binaryExpr.operator(), binaryExpr.right()),
                    leftRewrite.sourceSubtreeHash()
                ));
            }
            for (RewriteResult rightRewrite : rewriteEverywhere(binaryExpr.right(), visitorContext, attempts)) {
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
                for (RewriteResult argRewrite : rewriteEverywhere(arguments.get(index), visitorContext, attempts)) {
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

    private Expr applyRule(
        RewriteRule rule,
        Expr subtree,
        AstVisitorContext visitorContext,
        List<RuleAttempt> attempts
    ) {
        String formattedSubtree = attempts == null ? null : ExpressionFormatter.format(subtree);
        if (rule instanceof PatternTransformation transformation) {
            TransformationMatchContext matchContext = TransformationMatchContext.from(visitorContext, subtree);
            if (!transformation.matches(subtree, matchContext)) {
                if (attempts != null) {
                    attempts.add(new RuleAttempt(
                        rule.id(),
                        formattedSubtree,
                        AstVisitorPhase.DURING_SEARCH.name(),
                        false,
                        RuleRejectionReason.PATTERN_MISMATCH
                    ));
                }
                return null;
            }
            Expr result = transformation.transform(subtree, TransformationContext.from(visitorContext, subtree));
            if (attempts != null) {
                attempts.add(new RuleAttempt(
                    rule.id(),
                    formattedSubtree,
                    AstVisitorPhase.DURING_SEARCH.name(),
                    true,
                    RuleRejectionReason.APPLIED
                ));
            }
            return result;
        }
        if (!rule.matches(subtree)) {
            if (attempts != null) {
                attempts.add(new RuleAttempt(
                    rule.id(),
                    formattedSubtree,
                    AstVisitorPhase.DURING_SEARCH.name(),
                    false,
                    RuleRejectionReason.PATTERN_MISMATCH
                ));
            }
            return null;
        }
        Expr result = rule.apply(subtree);
        if (attempts != null) {
            attempts.add(new RuleAttempt(
                rule.id(),
                formattedSubtree,
                AstVisitorPhase.DURING_SEARCH.name(),
                true,
                RuleRejectionReason.APPLIED
            ));
        }
        return result;
    }

    private RuleDebugReport buildDebugReport(String expression, List<RuleAttempt> attempts) {
        int successful = (int) attempts.stream()
            .filter(attempt -> attempt.reason() == RuleRejectionReason.APPLIED)
            .count();
        int growthRejections = (int) attempts.stream()
            .filter(attempt -> attempt.reason() == RuleRejectionReason.GROWTH_LIMIT_EXCEEDED)
            .count();
        int candidateLimitRejections = (int) attempts.stream()
            .filter(attempt -> attempt.reason() == RuleRejectionReason.CANDIDATE_LIMIT_REACHED)
            .count();
        return new RuleDebugReport(
            expression,
            attempts,
            attempts.size(),
            successful,
            growthRejections,
            candidateLimitRejections,
            countReason(attempts, RuleRejectionReason.DISABLED_BY_CONFIG),
            countReason(attempts, RuleRejectionReason.DISABLED_BY_PROFILE),
            countReason(attempts, RuleRejectionReason.CONDITION_FAILED),
            countReason(attempts, RuleRejectionReason.CYCLE_RISK),
            debugMetadata.stream().map(RuleDebugMetadata::diagnostic).toList()
        );
    }

    private int countReason(List<RuleAttempt> attempts, RuleRejectionReason reason) {
        return (int) attempts.stream().filter(attempt -> attempt.reason() == reason).count();
    }

    private void appendRuntimeDebugAttempts(List<RuleAttempt> attempts) {
        for (RuleDebugMetadata metadata : debugMetadata) {
            attempts.add(new RuleAttempt(
                metadata.ruleId(),
                metadata.context(),
                "RUNTIME",
                false,
                metadata.reason(),
                metadata.detail()
            ));
        }
    }

    private record RewriteResult(RewriteRule rule, Expr expression, String sourceSubtreeHash) {
    }
}
