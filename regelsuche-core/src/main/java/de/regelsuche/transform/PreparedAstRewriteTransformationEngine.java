package de.regelsuche.transform;

import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Prepared variant of {@link AstRewriteTransformationEngine}.
 *
 * <p>It deliberately keeps the public string-based transformation boundary so
 * search semantics remain comparable, but removes avoidable work inside one
 * invocation:</p>
 *
 * <ul>
 *   <li>AST sizes and subtree hashes are computed directly from existing AST
 *       values instead of formatting and reparsing them;</li>
 *   <li>subtree hashes are computed lazily only after a rule actually rewrites
 *       that subtree;</li>
 *   <li>exact {@link PatternRewriteRule} instances share one binding pass for
 *       matching and target instantiation instead of running the matcher once
 *       for {@code matches} and again for {@code apply}.</li>
 * </ul>
 *
 * <p>Subclasses of {@code PatternRewriteRule} deliberately retain the ordinary
 * {@link RewriteRule} dispatch so overridden behavior is never bypassed.</p>
 *
 * <p>This is the production backend for repeated rewrite-program execution after
 * the matched-work, end-to-end and allocation measurements from #530. The
 * reference implementation remains selectable as the executable semantic
 * oracle, and differential tests require exact ordered transformation parity.</p>
 */
public final class PreparedAstRewriteTransformationEngine
        implements TransformationEngine,RetainedGraph.View {
    @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(parser);v.reference(canonicalizer);v.reference(rules);v.reference(ruleIndex);}

    private static final int DEFAULT_MAX_AST_SIZE_INCREASE = 12;
    private static final int DEFAULT_MAX_CANDIDATES_PER_STATE = 80;

    private final ExpressionParser parser = new ExpressionParser();
    private final ExpressionCanonicalizer canonicalizer =
        new ExpressionCanonicalizer();
    private final List<RewriteRule> rules;
    private final RuleShapeIndex ruleIndex;
    private final int maxAstSizeIncreasePerStep;
    private final int maxCandidatesPerState;

    public PreparedAstRewriteTransformationEngine() {
        this(AstRewriteTransformationEngine.defaultRules());
    }

    public PreparedAstRewriteTransformationEngine(List<RewriteRule> rules) {
        this(
            rules,
            DEFAULT_MAX_AST_SIZE_INCREASE,
            DEFAULT_MAX_CANDIDATES_PER_STATE
        );
    }

    public PreparedAstRewriteTransformationEngine(
        List<RewriteRule> rules,
        int maxAstSizeIncreasePerStep,
        int maxCandidatesPerState
    ) {
        this(rules, maxAstSizeIncreasePerStep, maxCandidatesPerState, false);
    }

    PreparedAstRewriteTransformationEngine(List<RewriteRule> rules,
            int maxAstSizeIncreasePerStep, int maxCandidatesPerState, boolean indexed) {
        this.rules = List.copyOf(rules);
        this.ruleIndex = indexed ? new RuleShapeIndex(this.rules) : null;
        this.maxAstSizeIncreasePerStep = maxAstSizeIncreasePerStep;
        this.maxCandidatesPerState = maxCandidatesPerState;
    }

    public List<RewriteRule> rules() {
        return rules;
    }

    /** Opt-in root discrimination; preserves original rule order and the unfiltered reference path. */
    public PreparedAstRewriteTransformationEngine withRuleIndex() {
        return ruleIndex == null ? new PreparedAstRewriteTransformationEngine(
            rules, maxAstSizeIncreasePerStep, maxCandidatesPerState, true) : this;
    }

    /** Independent typed view with this source's exact rule objects and generation limits. */
    public AstRewriteTransport astTransport() {
        return new AstRewriteTransport(rules, maxAstSizeIncreasePerStep, maxCandidatesPerState, ruleIndex != null);
    }

    /** Explicit native cursor capability; the historical list-based transformation path is unchanged. */
    public TransformationCursor openCursor(String expression) {
        return openCursor(expression, TransformationCursor.DEFAULT_MATCHER_BRANCH_LIMIT);
    }

    public TransformationCursor openCursor(String expression, int matcherBranchLimit) {
        return new PreparedTransformationCursor(this, expression, cursorDefinition(matcherBranchLimit));
    }

    /** Explicit v2 suspension: WORK_EXHAUSTED resumes the same traversal on the next positive pull. */
    public TransformationCursor openResumableCursor(String expression, int matcherBranchLimit) {
        return new PreparedTransformationCursor(this, expression, cursorDefinition(matcherBranchLimit), true);
    }

    /** Preflight rejects custom dispatch rather than silently materializing an unsupported provider. */
    public TransformationCursor.Definition cursorDefinition(int matcherBranchLimit) {
        if (maxCandidatesPerState < 1 || matcherBranchLimit < 1)
            throw new IllegalArgumentException("invalid native cursor bounds");
        var definitions = rules.stream().map(rule -> {
            if (rule.getClass() != PatternRewriteRule.class)
                throw new IllegalArgumentException("native cursor requires exact PatternRewriteRule instances");
            return TransformationCursor.RuleDefinition.of((PatternRewriteRule) rule);
        }).toList();
        return new TransformationCursor.Definition(TransformationCursor.ORDER_REVISION, definitions,
            maxAstSizeIncreasePerStep, maxCandidatesPerState, matcherBranchLimit);
    }

    @Override
    public List<Transformation> transform(String expression) {
        Expr root;
        try {
            root = parser.parse(new InputRequest(InputType.TERM, expression))
                .terms()
                .getFirst();
        } catch (IllegalArgumentException ex) {
            return List.of();
        }

        String formattedInput = ExpressionFormatter.format(root);
        int originalSize = canonicalAstNodeCount(root);
        Set<Transformation> transformations = new LinkedHashSet<>();
        for (RewriteResult result : rewriteEverywhere(root)) {
            String formatted = ExpressionFormatter.format(result.expression());
            if (formatted.equals(formattedInput)) {
                continue;
            }
            int growth = canonicalAstNodeCount(result.expression()) - originalSize;
            if (growth > maxAstSizeIncreasePerStep) {
                continue;
            }
            RewriteRule rule = result.rule();
            transformations.add(new Transformation(
                rule.id(),
                formatted,
                rule.kind(),
                rule.mayIncreaseComplexity(),
                rule.estimatedCostDelta(),
                rule.isEquivalencePreservingByConstruction(),
                rule.id() + ":" + result.sourceSubtreeHash(),
                result.assumptions().stream()
                    .map(Assumption::expression)
                    .toList(),
                rule.descriptor().packId(),
                rule.descriptor().license()
            ));
            if (transformations.size() >= maxCandidatesPerState) {
                break;
            }
        }
        return new ArrayList<>(transformations);
    }

    /** Typed sibling of the historical string path; called only by the opt-in transport. */
    List<AstRewriteTransport.Step> transformAst(Expr root) {
        AstRewriteTransport.requireBounded(root);
        int originalSize = canonicalAstNodeCount(root);
        Set<AstRewriteTransport.Step> steps = new LinkedHashSet<>();
        try(var retained=RetainedOperation.retain(this,root,steps)) {
        var rewritten=rewriteEverywhere(root,false);
        try(var candidates=RetainedOperation.retain(rewritten)) {
        for (RewriteResult result : rewritten) {
            AstRewriteTransport.requireBounded(result.expression());
            if (root.equals(result.expression())
                    || canonicalAstNodeCount(result.expression()) - originalSize > maxAstSizeIncreasePerStep) continue;
            var rule = result.rule();
            steps.add(new AstRewriteTransport.Step(root, result.expression(), rule.id(), rule.kind(),
                rule.mayIncreaseComplexity(), rule.estimatedCostDelta(), rule.isEquivalencePreservingByConstruction(),
                result.assumptions().stream().map(Assumption::expression).toList(),
                rule.descriptor().packId(), rule.descriptor().license()));
            if (steps.size() >= maxCandidatesPerState) break;
        }
        return RetainedOperation.produced(List.copyOf(steps));
        }
        }
    }

    private List<RewriteResult> rewriteEverywhere(Expr subtree) {
        return rewriteEverywhere(subtree, true);
    }

    private List<RewriteResult> rewriteEverywhere(Expr subtree, boolean retainLegacyHash) {
        List<RewriteResult> results = new ArrayList<>();
        try(var retained=retainLegacyHash?null:RetainedOperation.retain(this,subtree,results)) {
        String subtreeHash = null;
        for (RewriteRule rule : ruleIndex == null ? rules : ruleIndex.candidates(subtree)) {
            Expr rewritten = applyIfMatched(rule, subtree);
            if (!retainLegacyHash && rewritten != null) AstRewriteTransport.requireBounded(rewritten);
            if (rewritten == null || rewritten.equals(subtree)) {
                continue;
            }
            if (retainLegacyHash && subtreeHash == null) {
                subtreeHash = stableHash(subtree);
            }
            results.add(new RewriteResult(
                rule,
                rewritten,
                subtreeHash,
                rule.assumptions(subtree)
            ));
        }

        if (subtree instanceof BinaryExpr binaryExpr) {
            var leftRewrites=rewriteEverywhere(binaryExpr.left(), retainLegacyHash);
            try(var child=retainLegacyHash?null:RetainedOperation.retain(leftRewrites)) {
            for (RewriteResult leftRewrite : leftRewrites) {
                results.add(new RewriteResult(
                    leftRewrite.rule(),
                    new BinaryExpr(
                        leftRewrite.expression(),
                        binaryExpr.operator(),
                        binaryExpr.right()
                    ),
                    leftRewrite.sourceSubtreeHash(),
                    leftRewrite.assumptions()
                ));
                if(!retainLegacyHash){RetainedOperation.work(2);RetainedOperation.checkpoint();}
            }
            }
            var rightRewrites=rewriteEverywhere(binaryExpr.right(), retainLegacyHash);
            try(var child=retainLegacyHash?null:RetainedOperation.retain(rightRewrites)) {
            for (RewriteResult rightRewrite : rightRewrites) {
                results.add(new RewriteResult(
                    rightRewrite.rule(),
                    new BinaryExpr(
                        binaryExpr.left(),
                        binaryExpr.operator(),
                        rightRewrite.expression()
                    ),
                    rightRewrite.sourceSubtreeHash(),
                    rightRewrite.assumptions()
                ));
                if(!retainLegacyHash){RetainedOperation.work(2);RetainedOperation.checkpoint();}
            }
            }
        } else if (subtree instanceof FunctionExpr functionExpr) {
            List<Expr> arguments = functionExpr.arguments();
            for (int index = 0; index < arguments.size(); index++) {
                final int position = index;
                var argumentRewrites=rewriteEverywhere(arguments.get(index), retainLegacyHash);
                try(var child=retainLegacyHash?null:RetainedOperation.retain(argumentRewrites)) {
                for (RewriteResult argumentRewrite : argumentRewrites) {
                    List<Expr> replaced = new ArrayList<>(arguments);
                    if(!retainLegacyHash)RetainedOperation.work(arguments.size());
                    try(var argumentsHeld=retainLegacyHash?null:RetainedOperation.retain(replaced)) {
                    replaced.set(position, argumentRewrite.expression());
                    if(!retainLegacyHash)RetainedOperation.work(1);
                    results.add(new RewriteResult(
                        argumentRewrite.rule(),
                        new FunctionExpr(functionExpr.name(), replaced),
                        argumentRewrite.sourceSubtreeHash(),
                        argumentRewrite.assumptions()
                    ));
                    if(!retainLegacyHash){RetainedOperation.work(replaced.size()+2L);RetainedOperation.checkpoint();}
                    }
                }
                }
            }
        }
        if(!retainLegacyHash)RetainedOperation.checkpoint();
        return results;
        }
    }

    private static Expr applyIfMatched(RewriteRule rule, Expr subtree) {
        if (rule.getClass() == PatternRewriteRule.class) {
            PatternRewriteRule patternRule = (PatternRewriteRule) rule;
            Map<String, Expr> bindings = new HashMap<>();
            try(var retained=RetainedOperation.retain(patternRule,subtree,bindings)) {
            RetainedOperation.work(1);
            if (!EquivalenceAwarePatternMatcher.match(
                    patternRule.source(),
                    subtree,
                    bindings,
                    patternRule.recognitionProfile())) {
                return null;
            }
            return patternRule.target().instantiate(bindings);
            }
        }
        if (!rule.matches(subtree)) {
            return null;
        }
        return rule.apply(subtree);
    }

    int canonicalAstNodeCount(Expr expression) {
        var canonical=canonicalizer.canonicalize(expression);
        try(var retained=RetainedOperation.retain(expression,canonical)){return count(canonical);}
    }

    private static int count(Expr expression) {
        RetainedOperation.validation(1);
        if (expression instanceof BinaryExpr binaryExpr) {
            return 1 + count(binaryExpr.left()) + count(binaryExpr.right());
        }
        if (expression instanceof FunctionExpr functionExpr) {
            int total = 1;
            for (Expr argument : functionExpr.arguments()) {
                total += count(argument);
            }
            return total;
        }
        return 1;
    }

    String stableHash(Expr expression) {
        Expr canonical = canonicalizer.canonicalize(expression);
        return sha256(ExpressionFormatter.format(canonical));
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private record RewriteResult(
        RewriteRule rule,
        Expr expression,
        String sourceSubtreeHash,
        List<Assumption> assumptions
    ) implements RetainedGraph.View {
        @Override public void retainedReferences(RetainedGraph.Visitor v){v.reference(rule);v.reference(expression);v.reference(sourceSubtreeHash);v.reference(assumptions);}
        private RewriteResult {
            assumptions = assumptions == null
                ? List.of()
                : List.copyOf(assumptions);
        }
    }
}
