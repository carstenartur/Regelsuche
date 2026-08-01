package de.regelsuche.transform;

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
 * Experimental prepared variant of {@link AstRewriteTransformationEngine}.
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
 * <p>This class is an evidence-gathering backend for #530, not yet the default
 * production engine. Differential tests require exact ordered transformation
 * parity with the reference implementation.</p>
 */
public final class PreparedAstRewriteTransformationEngine
        implements TransformationEngine {
    private static final int DEFAULT_MAX_AST_SIZE_INCREASE = 12;
    private static final int DEFAULT_MAX_CANDIDATES_PER_STATE = 80;

    private final ExpressionParser parser = new ExpressionParser();
    private final ExpressionCanonicalizer canonicalizer =
        new ExpressionCanonicalizer();
    private final List<RewriteRule> rules;
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
        this.rules = List.copyOf(rules);
        this.maxAstSizeIncreasePerStep = maxAstSizeIncreasePerStep;
        this.maxCandidatesPerState = maxCandidatesPerState;
    }

    public List<RewriteRule> rules() {
        return rules;
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

    private List<RewriteResult> rewriteEverywhere(Expr subtree) {
        List<RewriteResult> results = new ArrayList<>();
        String subtreeHash = null;
        for (RewriteRule rule : rules) {
            Expr rewritten = applyIfMatched(rule, subtree);
            if (rewritten == null || rewritten.equals(subtree)) {
                continue;
            }
            if (subtreeHash == null) {
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
            for (RewriteResult leftRewrite :
                    rewriteEverywhere(binaryExpr.left())) {
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
            }
            for (RewriteResult rightRewrite :
                    rewriteEverywhere(binaryExpr.right())) {
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
            }
        } else if (subtree instanceof FunctionExpr functionExpr) {
            List<Expr> arguments = functionExpr.arguments();
            for (int index = 0; index < arguments.size(); index++) {
                final int position = index;
                for (RewriteResult argumentRewrite :
                        rewriteEverywhere(arguments.get(index))) {
                    List<Expr> replaced = new ArrayList<>(arguments);
                    replaced.set(position, argumentRewrite.expression());
                    results.add(new RewriteResult(
                        argumentRewrite.rule(),
                        new FunctionExpr(functionExpr.name(), replaced),
                        argumentRewrite.sourceSubtreeHash(),
                        argumentRewrite.assumptions()
                    ));
                }
            }
        }
        return results;
    }

    private static Expr applyIfMatched(RewriteRule rule, Expr subtree) {
        if (rule.getClass() == PatternRewriteRule.class) {
            PatternRewriteRule patternRule = (PatternRewriteRule) rule;
            Map<String, Expr> bindings = new HashMap<>();
            if (!EquivalenceAwarePatternMatcher.match(
                    patternRule.source(),
                    subtree,
                    bindings,
                    patternRule.recognitionProfile())) {
                return null;
            }
            return patternRule.target().instantiate(bindings);
        }
        if (!rule.matches(subtree)) {
            return null;
        }
        return rule.apply(subtree);
    }

    private int canonicalAstNodeCount(Expr expression) {
        return count(canonicalizer.canonicalize(expression));
    }

    private static int count(Expr expression) {
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

    private String stableHash(Expr expression) {
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
    ) {
        private RewriteResult {
            assumptions = assumptions == null
                ? List.of()
                : List.copyOf(assumptions);
        }
    }
}
