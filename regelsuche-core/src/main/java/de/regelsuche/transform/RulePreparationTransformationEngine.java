package de.regelsuche.transform;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.knowledge.KnowledgePackRegistry;
import de.regelsuche.knowledge.KnowledgePackSelection;
import de.regelsuche.knowledge.RuleInventoryFingerprint;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.RulePreparationPlanner.PreparedRuleApplication;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Opt-in transformation engine that augments direct rewrites with bounded,
 * rule-directed preparation plans.
 *
 * <p>Direct transformations are generated first and returned unchanged. Only
 * when the visible principal rule does not match directly does the planner
 * inspect a subtree. Historical users of {@link AstRewriteTransformationEngine}
 * therefore retain exactly their existing behavior and benchmark identity.</p>
 */
public final class RulePreparationTransformationEngine
        implements TransformationEngine {
    private static final int DEFAULT_MAX_PREPARED_CANDIDATES = 16;
    private static final int DEFAULT_MAX_PREPARATION_CACHE_ENTRIES = 128;
    private static final String PREPARATION_PACK_ID =
        "core-rule-preparation";

    private final TransformationEngine directEngine;
    private final Set<String> visibleRuleIds;
    private final RulePreparationPlanner planner;
    private final int maxPreparedCandidates;
    private final AssumptionSignature assumptionSignature;
    private final String ruleInventoryHash;
    private final int maxPreparationCacheEntries;
    private final ExpressionParser parser = new ExpressionParser();
    private final ExpressionCanonicalizer canonicalizer =
        new ExpressionCanonicalizer();

    public RulePreparationTransformationEngine() {
        this(AstRewriteTransformationEngine.defaultRules());
    }

    public RulePreparationTransformationEngine(List<RewriteRule> rules) {
        this(rules, AssumptionSignature.ofExpressions(List.of()));
    }

    public RulePreparationTransformationEngine(
        List<RewriteRule> rules,
        AssumptionSignature assumptionSignature
    ) {
        this(
            new AstRewriteTransformationEngine(
                List.copyOf(Objects.requireNonNull(rules, "rules"))),
            ruleIds(rules),
            new RulePreparationPlanner(),
            DEFAULT_MAX_PREPARED_CANDIDATES,
            assumptionSignature,
            RuleInventoryFingerprint.contentHash(rules),
            DEFAULT_MAX_PREPARATION_CACHE_ENTRIES);
    }

    public RulePreparationTransformationEngine(
        TransformationEngine directEngine,
        Set<String> visibleRuleIds,
        RulePreparationPlanner planner,
        int maxPreparedCandidates
    ) {
        this(
            directEngine,
            visibleRuleIds,
            planner,
            maxPreparedCandidates,
            AssumptionSignature.ofExpressions(List.of()),
            idOnlyInventoryHash(visibleRuleIds),
            DEFAULT_MAX_PREPARATION_CACHE_ENTRIES);
    }

    public RulePreparationTransformationEngine(
        TransformationEngine directEngine,
        Set<String> visibleRuleIds,
        RulePreparationPlanner planner,
        int maxPreparedCandidates,
        AssumptionSignature assumptionSignature,
        String ruleInventoryHash,
        int maxPreparationCacheEntries
    ) {
        this.directEngine = Objects.requireNonNull(
            directEngine,
            "directEngine");
        this.visibleRuleIds = Set.copyOf(
            Objects.requireNonNull(visibleRuleIds, "visibleRuleIds"));
        this.planner = Objects.requireNonNull(planner, "planner");
        this.assumptionSignature = Objects.requireNonNull(
            assumptionSignature,
            "assumptionSignature");
        if (ruleInventoryHash == null || ruleInventoryHash.isBlank()) {
            throw new IllegalArgumentException(
                "ruleInventoryHash must not be blank");
        }
        this.ruleInventoryHash = ruleInventoryHash.trim();
        if (maxPreparedCandidates < 0
                || maxPreparationCacheEntries < 0) {
            throw new IllegalArgumentException(
                "candidate and cache limits must not be negative");
        }
        this.maxPreparedCandidates = maxPreparedCandidates;
        this.maxPreparationCacheEntries = maxPreparationCacheEntries;
    }

    public static RulePreparationTransformationEngine withKnowledgePacks(
        KnowledgePackSelection selection
    ) {
        List<RewriteRule> rules = new ArrayList<>(
            AstRewriteTransformationEngine.defaultRules(selection));
        rules.addAll(new KnowledgePackRegistry().enabledRules(selection));
        return new RulePreparationTransformationEngine(rules);
    }

    public String plannerId() {
        return RulePreparationPlanner.PLANNER_ID;
    }

    public Set<String> visibleRuleIds() {
        return visibleRuleIds;
    }

    public AssumptionSignature assumptionSignature() {
        return assumptionSignature;
    }

    public String ruleInventoryHash() {
        return ruleInventoryHash;
    }

    @Override
    public List<Transformation> transform(String expression) {
        return transformWithEvidence(expression).transformations();
    }

    /**
     * Executes direct and prepared rewrites while retaining invocation-local
     * memoization metrics. The cache is intentionally scoped to one call so
     * its insertion and eviction order is deterministic and cannot leak across
     * experiments with different identities.
     */
    public Execution transformWithEvidence(String expression) {
        PreparationMemoizer memoizer = new PreparationMemoizer(
            maxPreparationCacheEntries);
        List<Transformation> direct = List.copyOf(
            directEngine.transform(expression));
        if (maxPreparedCandidates == 0
                || !visibleRuleIds.contains(
                    RulePreparationPlanner.PRINCIPAL_RULE_ID)
                || expression == null
                || expression.isBlank()) {
            return new Execution(direct, memoizer.metrics());
        }

        Expr root;
        try {
            root = parser.parse(new InputRequest(InputType.TERM, expression))
                .terms()
                .getFirst();
        } catch (IllegalArgumentException exception) {
            return new Execution(direct, memoizer.metrics());
        }

        String formattedRoot = ExpressionFormatter.format(root);
        Set<String> retainedOutputKeys = new LinkedHashSet<>();
        for (Transformation transformation : direct) {
            retainedOutputKeys.add(outputKey(
                transformation.transformedExpression(),
                transformation.assumptions()));
        }

        List<Transformation> prepared = new ArrayList<>();
        for (PositionedNode positioned : positionedNodes(root)) {
            if (prepared.size() >= maxPreparedCandidates) {
                break;
            }
            CacheKey cacheKey = cacheKey(positioned.expression());
            RulePreparationPlanner.PlanAttempt attempt =
                memoizer.find(cacheKey);
            if (attempt == null) {
                attempt = planner.plan(positioned.expression());
                if (attempt.status()
                        == RulePreparationPlanner.Status.PREPARED) {
                    memoizer.recordPreparedVerification();
                    PreparedRuleApplication proposed =
                        attempt.application().orElseThrow();
                    if (!verifyPreparedApplication(proposed)) {
                        memoizer.recordUnverifiable();
                        continue;
                    }
                }
                memoizer.retain(cacheKey, attempt);
            }
            if (attempt.status()
                    != RulePreparationPlanner.Status.PREPARED) {
                continue;
            }
            PreparedRuleApplication application =
                attempt.application().orElseThrow();
            Transformation principal = replayPrincipal(application);
            if (principal == null) {
                continue;
            }
            Expr rewrittenRoot = replaceAt(
                root,
                positioned.path(),
                0,
                application.resultSubtree());
            String transformed = ExpressionFormatter.format(rewrittenRoot);
            if (transformed.equals(formattedRoot)) {
                continue;
            }
            String outputKey = outputKey(
                transformed,
                application.assumptions());
            if (!retainedOutputKeys.add(outputKey)) {
                continue;
            }
            prepared.add(new Transformation(
                application.principalRuleId(),
                transformed,
                principal.kind(),
                true,
                principal.estimatedCostDelta(),
                principal.equivalencePreservingByConstruction(),
                "prepared:" + application.certificate().contentHash()
                    + ":" + positionKey(positioned.path()),
                application.assumptions(),
                PREPARATION_PACK_ID,
                "PROJECT",
                application.primitiveRuleIds()));
        }
        if (prepared.isEmpty()) {
            return new Execution(direct, memoizer.metrics());
        }
        List<Transformation> result = new ArrayList<>(direct);
        result.addAll(prepared);
        return new Execution(result, memoizer.metrics());
    }

    private boolean verifyPreparedApplication(
        PreparedRuleApplication application
    ) {
        try {
            return planner.verify(application);
        } catch (RuntimeException exception) {
            // Preparation is an optional candidate source. Invalid or stale
            // evidence must fail closed without failing the direct request.
            return false;
        }
    }

    private CacheKey cacheKey(Expr expression) {
        return new CacheKey(
            plannerId(),
            RulePreparationPlanner.PRINCIPAL_RULE_ID,
            canonicalizer.stableHash(ExpressionFormatter.format(expression)),
            assumptionSignature.fingerprint(),
            ruleInventoryHash,
            "maxSolverAttempts=" + planner.budget().maxSolverAttempts());
    }

    private Transformation replayPrincipal(
        PreparedRuleApplication application
    ) {
        String prepared = ExpressionFormatter.format(
            application.preparedSubtree());
        String expectedResultKey = outputKey(
            ExpressionFormatter.format(application.resultSubtree()),
            application.assumptions());
        return directEngine.transform(prepared).stream()
            .filter(transformation -> application.principalRuleId()
                .equals(transformation.rule()))
            .filter(transformation -> expectedResultKey.equals(outputKey(
                transformation.transformedExpression(),
                transformation.assumptions())))
            .findFirst()
            .orElse(null);
    }

    private String outputKey(
        String expression,
        List<String> assumptions
    ) {
        String expressionKey;
        try {
            expressionKey = canonicalizer.stableHash(expression);
        } catch (RuntimeException exception) {
            expressionKey = expression == null ? "" : expression.trim();
        }
        return expressionKey + "\u0000" + String.join("\u0000", assumptions);
    }

    private static List<PositionedNode> positionedNodes(Expr root) {
        List<PositionedNode> result = new ArrayList<>();
        collect(root, List.of(), result);
        return List.copyOf(result);
    }

    private static void collect(
        Expr expression,
        List<Integer> path,
        List<PositionedNode> result
    ) {
        result.add(new PositionedNode(expression, path));
        if (expression instanceof BinaryExpr binary) {
            collect(binary.left(), append(path, 0), result);
            collect(binary.right(), append(path, 1), result);
        } else if (expression instanceof FunctionExpr function) {
            for (int index = 0;
                    index < function.arguments().size();
                    index++) {
                collect(
                    function.arguments().get(index),
                    append(path, index),
                    result);
            }
        }
    }

    private static List<Integer> append(
        List<Integer> path,
        int component
    ) {
        List<Integer> appended = new ArrayList<>(path);
        appended.add(component);
        return List.copyOf(appended);
    }

    private static Expr replaceAt(
        Expr current,
        List<Integer> path,
        int pathIndex,
        Expr replacement
    ) {
        if (pathIndex == path.size()) {
            return replacement;
        }
        int component = path.get(pathIndex);
        if (current instanceof BinaryExpr binary) {
            if (component == 0) {
                return new BinaryExpr(
                    replaceAt(
                        binary.left(),
                        path,
                        pathIndex + 1,
                        replacement),
                    binary.operator(),
                    binary.right());
            }
            if (component == 1) {
                return new BinaryExpr(
                    binary.left(),
                    binary.operator(),
                    replaceAt(
                        binary.right(),
                        path,
                        pathIndex + 1,
                        replacement));
            }
            throw new IllegalArgumentException(
                "binary path component must be 0 or 1");
        }
        if (current instanceof FunctionExpr function) {
            if (component < 0
                    || component >= function.arguments().size()) {
                throw new IllegalArgumentException(
                    "function path component out of bounds");
            }
            List<Expr> arguments = new ArrayList<>(function.arguments());
            arguments.set(
                component,
                replaceAt(
                    arguments.get(component),
                    path,
                    pathIndex + 1,
                    replacement));
            return new FunctionExpr(function.name(), arguments);
        }
        throw new IllegalArgumentException(
            "path descends through a leaf expression");
    }

    private static String positionKey(List<Integer> path) {
        if (path.isEmpty()) {
            return "root";
        }
        return path.stream()
            .map(String::valueOf)
            .collect(java.util.stream.Collectors.joining("."));
    }

    private static Set<String> ruleIds(List<RewriteRule> rules) {
        Objects.requireNonNull(rules, "rules");
        Set<String> ids = new LinkedHashSet<>();
        for (RewriteRule rule : rules) {
            ids.add(Objects.requireNonNull(rule, "rule").id());
        }
        return Set.copyOf(ids);
    }

    private static String idOnlyInventoryHash(Set<String> ruleIds) {
        Objects.requireNonNull(ruleIds, "ruleIds");
        String payload = ruleIds.stream().sorted().toList().toString();
        try {
            return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record Execution(
        List<Transformation> transformations,
        CacheMetrics cacheMetrics
    ) {
        public Execution {
            transformations = List.copyOf(
                Objects.requireNonNull(transformations, "transformations"));
            cacheMetrics = Objects.requireNonNull(
                cacheMetrics,
                "cacheMetrics");
        }
    }

    public record CacheMetrics(
        int lookups,
        int hits,
        int misses,
        int retainedEntries,
        int evictions,
        int skippedInconclusive,
        int preparedVerifications,
        int skippedUnverifiable
    ) {
        public CacheMetrics {
            if (lookups < 0 || hits < 0 || misses < 0
                    || retainedEntries < 0 || evictions < 0
                    || skippedInconclusive < 0
                    || preparedVerifications < 0
                    || skippedUnverifiable < 0
                    || lookups != hits + misses) {
                throw new IllegalArgumentException(
                    "cache metrics must be non-negative and balanced");
            }
        }
    }

    private record CacheKey(
        String plannerId,
        String principalRuleId,
        String subtreeHash,
        String assumptionFingerprint,
        String ruleInventoryHash,
        String budgetIdentity
    ) {
        private CacheKey {
            Objects.requireNonNull(plannerId, "plannerId");
            Objects.requireNonNull(principalRuleId, "principalRuleId");
            Objects.requireNonNull(subtreeHash, "subtreeHash");
            Objects.requireNonNull(
                assumptionFingerprint,
                "assumptionFingerprint");
            Objects.requireNonNull(ruleInventoryHash, "ruleInventoryHash");
            Objects.requireNonNull(budgetIdentity, "budgetIdentity");
        }
    }

    private static final class PreparationMemoizer {
        private final int maxEntries;
        private final Map<CacheKey, RulePreparationPlanner.PlanAttempt> entries =
            new LinkedHashMap<>();
        private int lookups;
        private int hits;
        private int misses;
        private int evictions;
        private int skippedInconclusive;
        private int preparedVerifications;
        private int skippedUnverifiable;

        private PreparationMemoizer(int maxEntries) {
            this.maxEntries = maxEntries;
        }

        private RulePreparationPlanner.PlanAttempt find(CacheKey key) {
            lookups++;
            RulePreparationPlanner.PlanAttempt attempt = entries.get(key);
            if (attempt == null) {
                misses++;
            } else {
                hits++;
            }
            return attempt;
        }

        private void retain(
            CacheKey key,
            RulePreparationPlanner.PlanAttempt attempt
        ) {
            if (attempt.status()
                    == RulePreparationPlanner.Status.BUDGET_INCONCLUSIVE) {
                skippedInconclusive++;
                return;
            }
            if (maxEntries == 0 || entries.containsKey(key)) {
                return;
            }
            if (entries.size() >= maxEntries) {
                CacheKey oldest = entries.keySet().iterator().next();
                entries.remove(oldest);
                evictions++;
            }
            entries.put(key, attempt);
        }

        private void recordPreparedVerification() {
            preparedVerifications++;
        }

        private void recordUnverifiable() {
            skippedUnverifiable++;
        }

        private CacheMetrics metrics() {
            return new CacheMetrics(
                lookups,
                hits,
                misses,
                entries.size(),
                evictions,
                skippedInconclusive,
                preparedVerifications,
                skippedUnverifiable);
        }
    }

    private record PositionedNode(
        Expr expression,
        List<Integer> path
    ) {
        private PositionedNode {
            Objects.requireNonNull(expression, "expression");
            path = List.copyOf(Objects.requireNonNull(path, "path"));
        }
    }
}
