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
import de.regelsuche.knowledge.RuleDescriptor;
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
        Object[] pending = new Object[2];
        var retained = RetainedOperation.retainCompleted(2, this, root, steps, pending);
        Throwable primary = null;
        try {
            var rewritten = rewriteEverywhere(root, false);
            pending[0] = rewritten;
            RetainedOperation.checkpoint();
            for (RewriteResult result : rewritten) {
                AstRewriteTransport.requireBounded(result.expression());
                if (root.equals(result.expression())
                        || canonicalAstNodeCount(result.expression()) - originalSize > maxAstSizeIncreasePerStep) continue;
                appendNativeStep(root, result, steps);
                if (steps.size() >= maxCandidatesPerState) break;
            }
            var result = List.copyOf(steps);
            pending[1] = result;
            completed(steps.size() + 1L);
            return result;
        } catch (RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally { release(retained, primary); }
    }

    private static void appendNativeStep(Expr root, RewriteResult result, Set<AstRewriteTransport.Step> steps) {
        Object[] pending = new Object[6];
        var retained = RetainedOperation.retainCompleted(1, root, result, steps, pending);
        Throwable primary = null;
        try {
            var rule = result.rule();
            String id = rule.id();
            pending[0] = id;
            RewriteKind kind = rule.kind();
            pending[1] = kind;
            boolean mayIncrease = rule.mayIncreaseComplexity();
            int estimatedCostDelta = rule.estimatedCostDelta();
            boolean equivalencePreserving = rule.isEquivalencePreservingByConstruction();
            List<String> assumptions = result.assumptions().stream().map(Assumption::expression).toList();
            pending[2] = assumptions;
            completed(assumptions.size() + 1L);
            RuleDescriptor packDescriptor = rule.descriptor();
            pending[3] = packDescriptor;
            completed(1);
            String packId = packDescriptor.packId();
            // Preserve both original calls, including custom descriptor implementations.
            RuleDescriptor licenseDescriptor = rule.descriptor();
            pending[4] = licenseDescriptor;
            completed(1);
            var step = new AstRewriteTransport.Step(root, result.expression(), id, kind,
                mayIncrease, estimatedCostDelta, equivalencePreserving, assumptions,
                packId, licenseDescriptor.license());
            pending[5] = step;
            completed(1);
            steps.add(step);
            completed(1);
        } catch (RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally { release(retained, primary); }
    }

    private List<RewriteResult> rewriteEverywhere(Expr subtree) {
        return rewriteEverywhere(subtree, true);
    }

    private List<RewriteResult> rewriteEverywhere(Expr subtree, boolean retainLegacyHash) {
        List<RewriteResult> results = retainLegacyHash ? new ArrayList<>() : null;
        RetainedOperation.Frame owned = null;
        try {
            String subtreeHash = null;
            for (RewriteRule rule : ruleIndex == null ? rules : ruleIndex.candidates(subtree)) {
                Expr rewritten = applyIfMatched(rule, subtree);
                if (!retainLegacyHash && rewritten != null) AstRewriteTransport.requireBounded(rewritten);
                if (rewritten == null || rewritten.equals(subtree)) continue;
                if (retainLegacyHash && subtreeHash == null) subtreeHash = stableHash(subtree);
                if (results == null) {
                    results = new ArrayList<>();
                    owned = RetainedOperation.retainCompleted(1, results, rewritten);
                }
                results.add(rewriteResult(rule, rewritten, subtreeHash, rule.assumptions(subtree), retainLegacyHash));
                if (!retainLegacyHash) {
                    completed(1); // the result allocation was settled before insertion
                }
            }
            if (subtree instanceof BinaryExpr binaryExpr) {
                results = rewriteBinaryChildren(binaryExpr, retainLegacyHash, results);
            } else if (subtree instanceof FunctionExpr functionExpr) {
                results = rewriteFunctionArguments(functionExpr, retainLegacyHash, results);
            }
            return results == null ? List.of() : results;
        } catch (RuntimeException | Error failure) {
            var release = owned;
            owned = null;
            releaseAfterFailure(release, failure);
            throw failure;
        } finally {
            if (owned != null) owned.close();
        }
    }

    private List<RewriteResult> rewriteBinaryChildren(BinaryExpr binaryExpr, boolean retainLegacyHash,
            List<RewriteResult> results) {
        RetainedOperation.Frame owned = null;
        try {
            var leftRewrites = rewriteEverywhere(binaryExpr.left(), retainLegacyHash);
            if (results == null && !leftRewrites.isEmpty()) {
                results = new ArrayList<>();
                owned = RetainedOperation.retainCompleted(1, results, leftRewrites);
            }
            try (var child = retainLegacyHash || leftRewrites.isEmpty() ? null : RetainedOperation.retain(leftRewrites)) {

                for (RewriteResult rewrite : leftRewrites) {
                    results.add(rewriteResult(rewrite.rule(),
                        new BinaryExpr(rewrite.expression(), binaryExpr.operator(), binaryExpr.right()),
                        rewrite.sourceSubtreeHash(), rewrite.assumptions(), retainLegacyHash));
                    if (!retainLegacyHash) completed(1);
                }
            }
            var rightRewrites = rewriteEverywhere(binaryExpr.right(), retainLegacyHash);
            if (results == null && !rightRewrites.isEmpty()) {
                results = new ArrayList<>();
                owned = RetainedOperation.retainCompleted(1, results, rightRewrites);
            }
            try (var child = retainLegacyHash || rightRewrites.isEmpty() ? null : RetainedOperation.retain(rightRewrites)) {

                for (RewriteResult rewrite : rightRewrites) {
                    results.add(rewriteResult(rewrite.rule(),
                        new BinaryExpr(binaryExpr.left(), binaryExpr.operator(), rewrite.expression()),
                        rewrite.sourceSubtreeHash(), rewrite.assumptions(), retainLegacyHash));
                    if (!retainLegacyHash) completed(1);
                }
            }
            return results;
        } catch (RuntimeException | Error failure) {
            var release = owned;
            owned = null;
            releaseAfterFailure(release, failure);
            throw failure;
        } finally {
            if (owned != null) owned.close();
        }
    }

    private List<RewriteResult> rewriteFunctionArguments(FunctionExpr functionExpr, boolean retainLegacyHash,
            List<RewriteResult> results) {
        RetainedOperation.Frame owned = null;
        try {
            List<Expr> arguments = functionExpr.arguments();
            for (int index = 0; index < arguments.size(); index++) {
                var argumentRewrites = rewriteEverywhere(arguments.get(index), retainLegacyHash);
                if (results == null && !argumentRewrites.isEmpty()) {
                    results = new ArrayList<>();
                    owned = RetainedOperation.retainCompleted(1, results, argumentRewrites);
                }
                try (var child = retainLegacyHash || argumentRewrites.isEmpty() ? null : RetainedOperation.retain(argumentRewrites)) {

                    for (RewriteResult rewrite : argumentRewrites) {
                        appendFunctionRewrite(functionExpr, index, rewrite, retainLegacyHash, results);
                    }
                }
            }
            return results;
        } catch (RuntimeException | Error failure) {
            var release = owned;
            owned = null;
            releaseAfterFailure(release, failure);
            throw failure;
        } finally {
            if (owned != null) owned.close();
        }
    }

    private static void releaseAfterFailure(RetainedOperation.Frame owned, Throwable failure) {
        if (owned == null) return;
        try { owned.close(); }
        catch (RuntimeException | Error cleanup) {
            if (cleanup != failure) failure.addSuppressed(cleanup);
        }
    }

    private static void release(RetainedOperation.Frame frame, Throwable primary) {
        if (primary != null) releaseAfterFailure(frame, primary);
        else if (frame != null) frame.close();
    }

    private static void completed(long work) {
        try {
            RetainedOperation.work(work);
            RetainedOperation.checkpoint();
        } catch (RuntimeException | Error failure) {
            try { RetainedOperation.checkpoint(); }
            catch (RuntimeException | Error observation) {
                if (observation != failure) failure.addSuppressed(observation);
            }
            throw failure;
        }
    }

    private static RewriteResult rewriteResult(RewriteRule rule, Expr expression, String sourceSubtreeHash,
            List<Assumption> assumptions, boolean retainLegacyHash) {
        if (retainLegacyHash) return new RewriteResult(rule, expression, sourceSubtreeHash, assumptions);
        var original = RetainedOperation.retain(rule, expression, assumptions);
        Throwable primary = null;
        try {
            var result = new RewriteResult(rule, expression, sourceSubtreeHash, assumptions);
            long copied = assumptions != null && result.assumptions() != assumptions
                ? result.assumptions().size() + 1L : 0;
            // The existing result-allocation unit moves here; an actual copy adds its own work.
            var retained = RetainedOperation.retainCompleted(copied + 1, result);
            try { return result; }
            finally { if (retained != null) retained.close(); }
        } catch (RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally { release(original, primary); }
    }

    private void appendFunctionRewrite(FunctionExpr functionExpr, int position, RewriteResult rewrite,
            boolean retainLegacyHash, List<RewriteResult> results) {
        List<Expr> replaced = new ArrayList<>(functionExpr.arguments());
        var argumentsHeld = retainLegacyHash ? null : RetainedOperation.retainCompleted(replaced.size(), replaced);
        Throwable primary = null;
        try {
            replaced.set(position, rewrite.expression());
            if (!retainLegacyHash) RetainedOperation.work(1);
            results.add(rewriteResult(rewrite.rule(), new FunctionExpr(functionExpr.name(), replaced),
                rewrite.sourceSubtreeHash(), rewrite.assumptions(), retainLegacyHash));
            if (!retainLegacyHash) completed(replaced.size() + 1L);
        } catch (RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally { release(argumentsHeld, primary); }
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
