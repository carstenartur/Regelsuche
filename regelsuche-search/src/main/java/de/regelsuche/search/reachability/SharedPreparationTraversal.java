package de.regelsuche.search.reachability;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.knowledge.RuleInventoryFingerprint;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.EquivalentExpressionProvider;
import de.regelsuche.transform.ExprMatcher;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternMatchAnalyzer;
import de.regelsuche.transform.RecognitionProfile;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.Transformation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Per-evaluation physical cache shared by several logical local-bridge searches.
 *
 * <p>Principal searches keep their own frontier, ranking and logical budget
 * accounting. This object only deduplicates work whose semantics are independent
 * of the principal: preparation-engine expansion, parsing/structural
 * fingerprinting of produced expressions and pattern analysis when two
 * principals use the exact same pattern/recognition contract.</p>
 *
 * <p>The cache is intentionally short lived. A coordinator creates one instance
 * for one source evaluation, so no work or result can leak between independent
 * requests or repository/configuration identities.</p>
 */
public final class SharedPreparationTraversal {
    public static final String REVISION =
        "regelsuche.shared-preparation-traversal/v1";

    private final List<RewriteRule> preparationRules;
    private final PatternTargetedLocalBridgeSearch.Budget budget;
    private final String preparationInventoryFingerprint;
    private final AstRewriteTransformationEngine preparationEngine;
    private final ExpressionParser parser = new ExpressionParser();
    private final PatternMatchAnalyzer analyzer = new PatternMatchAnalyzer();
    private final Map<String, ParsedExpression> parsedExpressions =
        new LinkedHashMap<>();
    private final Map<String, Expansion> expansions = new LinkedHashMap<>();
    private final Map<AnalysisKey, PatternMatchAnalyzer.Analysis> analyses =
        new LinkedHashMap<>();

    private long expansionRequests;
    private long expansionCacheHits;
    private long generatedTransformations;
    private long parseRequests;
    private long parseCacheHits;
    private long analysisRequests;
    private long analysisCacheHits;

    public SharedPreparationTraversal(
        List<? extends RewriteRule> preparationRules,
        PatternTargetedLocalBridgeSearch.Budget budget
    ) {
        this.preparationRules = validatePreparationRules(preparationRules);
        this.budget = Objects.requireNonNull(budget, "budget");
        this.preparationInventoryFingerprint =
            RuleInventoryFingerprint.contentHash(this.preparationRules);
        this.preparationEngine = new AstRewriteTransformationEngine(
            this.preparationRules,
            Integer.MAX_VALUE,
            engineCandidateObservationLimit(this.budget));
    }

    public String preparationInventoryFingerprint() {
        return preparationInventoryFingerprint;
    }

    public PatternTargetedLocalBridgeSearch.Budget budget() {
        return budget;
    }

    /** Returns one immutable physical expansion, computing it at most once. */
    public synchronized Expansion expand(String sourceExpression) {
        String source = parse(sourceExpression).expression();
        expansionRequests++;
        Expansion retained = expansions.get(source);
        if (retained != null) {
            expansionCacheHits++;
            return retained;
        }
        List<Transformation> generated = Objects.requireNonNull(
            preparationEngine.transform(source),
            "preparation engine result");
        generatedTransformations = add(
            generatedTransformations, generated.size());
        List<Transition> transitions = new ArrayList<>(generated.size());
        for (Transformation transformation : generated) {
            Transformation checked = Objects.requireNonNull(
                transformation, "preparation transformation");
            ParsedExpression target = parse(checked.transformedExpression());
            transitions.add(new Transition(checked, target));
        }
        Expansion result = new Expansion(source, transitions);
        expansions.put(source, result);
        return result;
    }

    /**
     * Returns a parsed/fingerprinted immutable expression. Equal normalized
     * expression text is parsed exactly once in one shared traversal.
     */
    public synchronized ParsedExpression parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("expression must not be blank");
        }
        parseRequests++;
        ParsedExpression byInput = parsedExpressions.get(expression);
        if (byInput != null) {
            parseCacheHits++;
            return byInput;
        }
        Expr ast;
        try {
            ast = parser.parseTerm(expression);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "expression is not a supported term", exception);
        }
        String normalized = ExpressionFormatter.format(ast);
        ParsedExpression canonical = parsedExpressions.get(normalized);
        if (canonical != null) {
            parseCacheHits++;
            parsedExpressions.put(expression, canonical);
            return canonical;
        }
        ParsedExpression parsed = new ParsedExpression(
            normalized,
            ast,
            structuralFingerprint(ast),
            expressionNodes(ast));
        parsedExpressions.put(normalized, parsed);
        if (!normalized.equals(expression)) {
            parsedExpressions.put(expression, parsed);
        }
        return parsed;
    }

    /**
     * Shares match analysis only when pattern, recognition profile and matcher
     * limits are structurally identical. Different principals never share a
     * result merely because their rule IDs look related.
     */
    public synchronized PatternMatchAnalyzer.Analysis analyze(
        PatternExpr pattern,
        RecognitionProfile recognitionProfile,
        ParsedExpression expression
    ) {
        Objects.requireNonNull(pattern, "pattern");
        RecognitionProfile profile = recognitionProfile == null
            ? RecognitionProfile.exact()
            : recognitionProfile;
        Objects.requireNonNull(expression, "expression");
        analysisRequests++;
        AnalysisKey key = new AnalysisKey(
            pattern,
            profile,
            expression.structuralFingerprint(),
            budget.maxMatchResults(),
            budget.maxMatchSteps(),
            budget.maxPatternBranches());
        PatternMatchAnalyzer.Analysis retained = analyses.get(key);
        if (retained != null) {
            analysisCacheHits++;
            return retained;
        }
        PatternMatchAnalyzer.Analysis result = analyzer.analyze(
            pattern,
            expression.ast(),
            profile,
            new ExprMatcher.MatchOptions(
                EquivalentExpressionProvider.identity(),
                budget.maxMatchResults(),
                budget.maxMatchSteps(),
                budget.maxPatternBranches()));
        analyses.put(key, result);
        return result;
    }

    public synchronized Work work() {
        return new Work(
            expansionRequests,
            expansions.size(),
            expansionCacheHits,
            generatedTransformations,
            parseRequests,
            canonicalParsedExpressionCount(),
            parseCacheHits,
            analysisRequests,
            analyses.size(),
            analysisCacheHits);
    }

    private int canonicalParsedExpressionCount() {
        return Math.toIntExact(parsedExpressions.values().stream()
            .map(ParsedExpression::structuralFingerprint)
            .distinct()
            .count());
    }

    private static int engineCandidateObservationLimit(
        PatternTargetedLocalBridgeSearch.Budget budget
    ) {
        int tighterLimit = Math.min(
            budget.maxSuccessorsPerState(),
            budget.maxGeneratedTransitions());
        if (tighterLimit == Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return Math.max(1, tighterLimit + 1);
    }

    private static List<RewriteRule> validatePreparationRules(
        List<? extends RewriteRule> supplied
    ) {
        Objects.requireNonNull(supplied, "preparationRules");
        List<RewriteRule> result = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (RewriteRule rule : supplied) {
            RewriteRule checked = Objects.requireNonNull(
                rule, "preparation rule");
            if (!checked.isEquivalencePreservingByConstruction()) {
                throw new IllegalArgumentException(
                    "preparation rules must preserve equivalence: "
                        + checked.id());
            }
            if (!ids.add(checked.id())) {
                throw new IllegalArgumentException(
                    "duplicate preparation rule ID: " + checked.id());
            }
            result.add(checked);
        }
        return List.copyOf(result);
    }

    private static int expressionNodes(Expr expression) {
        int count = 0;
        ArrayDeque<Expr> pending = new ArrayDeque<>();
        pending.push(expression);
        while (!pending.isEmpty()) {
            Expr current = pending.pop();
            count++;
            if (current instanceof BinaryExpr binary) {
                pending.push(binary.right());
                pending.push(binary.left());
            } else if (current instanceof FunctionExpr function) {
                for (int index = function.arguments().size() - 1;
                        index >= 0; index--) {
                    pending.push(function.arguments().get(index));
                }
            }
        }
        return count;
    }

    private static String structuralFingerprint(Expr expression) {
        StringBuilder descriptor = new StringBuilder();
        appendExpression(descriptor, expression);
        return sha256(descriptor.toString());
    }

    private static void appendExpression(
        StringBuilder target,
        Expr expression
    ) {
        if (expression instanceof NumberExpr number) {
            append(target, "number");
            append(target, number.value().canonicalText());
        } else if (expression instanceof VariableExpr variable) {
            append(target, "variable");
            append(target, variable.name());
        } else if (expression instanceof BinaryExpr binary) {
            append(target, "binary");
            append(target, binary.operator().name());
            appendExpression(target, binary.left());
            appendExpression(target, binary.right());
        } else if (expression instanceof FunctionExpr function) {
            append(target, "function");
            append(target, function.name());
            function.arguments().forEach(value ->
                appendExpression(target, value));
        } else {
            throw new IllegalArgumentException(
                "unsupported expression type: "
                    + expression.getClass().getName());
        }
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }

    private static String sha256(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static long add(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    public record ParsedExpression(
        String expression,
        Expr ast,
        String structuralFingerprint,
        int expressionNodes
    ) {
        public ParsedExpression {
            if (expression == null || expression.isBlank()
                    || ast == null
                    || structuralFingerprint == null
                    || !structuralFingerprint.matches("sha256:[0-9a-f]{64}")
                    || expressionNodes < 1) {
                throw new IllegalArgumentException(
                    "parsed expression metadata is invalid");
            }
        }
    }

    public record Transition(
        Transformation transformation,
        ParsedExpression target
    ) {
        public Transition {
            transformation = Objects.requireNonNull(
                transformation, "transformation");
            target = Objects.requireNonNull(target, "target");
        }
    }

    public record Expansion(
        String sourceExpression,
        List<Transition> transitions
    ) {
        public Expansion {
            if (sourceExpression == null || sourceExpression.isBlank()) {
                throw new IllegalArgumentException(
                    "sourceExpression must not be blank");
            }
            transitions = List.copyOf(Objects.requireNonNull(
                transitions, "transitions"));
        }
    }

    /** Physical work, counted once even when several principals reuse it. */
    public record Work(
        long expansionRequests,
        long uniqueExpansions,
        long expansionCacheHits,
        long generatedTransformations,
        long parseRequests,
        long uniqueParsedExpressions,
        long parseCacheHits,
        long analysisRequests,
        long uniqueAnalyses,
        long analysisCacheHits
    ) {
        public Work {
            if (expansionRequests < 0
                    || uniqueExpansions < 0
                    || expansionCacheHits < 0
                    || generatedTransformations < 0
                    || parseRequests < 0
                    || uniqueParsedExpressions < 0
                    || parseCacheHits < 0
                    || analysisRequests < 0
                    || uniqueAnalyses < 0
                    || analysisCacheHits < 0
                    || expansionRequests != uniqueExpansions + expansionCacheHits
                    || analysisRequests != uniqueAnalyses + analysisCacheHits
                    || parseCacheHits > parseRequests) {
                throw new IllegalArgumentException(
                    "shared traversal work ledger is inconsistent");
            }
        }

        public long avoidedExpansionInvocations() {
            return expansionCacheHits;
        }

        public long avoidedPatternAnalyses() {
            return analysisCacheHits;
        }
    }

    private record AnalysisKey(
        PatternExpr pattern,
        RecognitionProfile recognitionProfile,
        String structuralFingerprint,
        int maxMatchResults,
        int maxMatchSteps,
        int maxPatternBranches
    ) {
        private AnalysisKey {
            Objects.requireNonNull(pattern, "pattern");
            Objects.requireNonNull(recognitionProfile, "recognitionProfile");
            Objects.requireNonNull(
                structuralFingerprint, "structuralFingerprint");
        }
    }
}
