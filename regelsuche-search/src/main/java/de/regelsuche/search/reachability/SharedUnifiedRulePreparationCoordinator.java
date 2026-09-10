package de.regelsuche.search.reachability;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.knowledge.RuleInventoryFingerprint;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.ExprMatcher;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternMatchAnalyzer;
import de.regelsuche.transform.RewriteApplicabilitySchema;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.SafePreparationEngineRegistry;
import de.regelsuche.transform.Transformation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Versioned shared successor of {@link UnifiedRulePreparationCoordinator}.
 *
 * <p>Direct concrete replay remains the first authority, followed by visible
 * theory-aware match analysis and the native exact specialist registry. Only
 * principals still unresolved after those cheap stages enter one bounded shared
 * fallback traversal. The traversal shares physical state expansion,
 * fingerprints, pattern analysis, deduplication and budgets while concrete
 * principal replay and guard authorization remain separate per principal.</p>
 *
 * <p>This class intentionally has a new configuration identity instead of
 * silently changing the historical v1 coordinator. Every {@link #analyze}
 * call creates fresh short-lived caches, so deterministic verification cannot
 * depend on earlier requests.</p>
 */
public final class SharedUnifiedRulePreparationCoordinator {
    public static final String COORDINATOR_ID =
        "regelsuche.unified-safe-rule-preparation-coordinator/v2";
    public static final String SHARED_EXECUTION_REVISION =
        "regelsuche.unified-shared-preparation-work/v1";
    private static final String EXACT_REGISTRY_INVENTORY_REVISION =
        "regelsuche.unified-exact-registry-inventory/v2";
    private static final String COMBINED_PREPARATION_REVISION =
        "regelsuche.unified-preparation-inventory/v2";

    private static final Comparator<Transformation> EXACT_CANDIDATE_ORDER =
        Comparator.comparingInt(Transformation::primitiveStepCount)
            .thenComparing(Transformation::applicationKey)
            .thenComparing(Transformation::transformedExpression);

    private final List<RewriteApplicabilitySchema> principalSchemas;
    private final List<RewriteRule> preparationRules;
    private final Map<String, PrincipalRuntime> runtimes;
    private final String repositoryRevision;
    private final PatternTargetedLocalBridgeSearch.Budget bridgeBudget;
    private final String principalInventoryFingerprint;
    private final String preparationInventoryFingerprint;
    private final String exactRegistryFingerprint;
    private final ExpressionParser parser = new ExpressionParser();

    public SharedUnifiedRulePreparationCoordinator(
        List<RewriteApplicabilitySchema> principalSchemas,
        List<? extends RewriteRule> preparationRules,
        String repositoryRevision,
        PatternTargetedLocalBridgeSearch.Budget bridgeBudget
    ) {
        this.repositoryRevision = requireRevision(repositoryRevision);
        this.bridgeBudget = Objects.requireNonNull(bridgeBudget, "bridgeBudget");

        // Reuse the already qualified v1 input validator and inventory ordering;
        // execution below does not invoke the v1 per-principal fallback.
        UnifiedRulePreparationCoordinator validation =
            new UnifiedRulePreparationCoordinator(
                principalSchemas,
                preparationRules,
                this.repositoryRevision,
                this.bridgeBudget);
        this.principalSchemas = validation.principalSchemas();
        this.preparationRules = validatePreparationRules(preparationRules);
        this.principalInventoryFingerprint =
            validation.principalInventoryFingerprint();

        Map<String, PrincipalRuntime> indexed = new LinkedHashMap<>();
        for (RewriteApplicabilitySchema schema : this.principalSchemas) {
            SafePreparationEngineRegistry.Registration exact =
                SafePreparationEngineRegistry.production(
                    exactVisibleRules(schema, this.preparationRules));
            indexed.put(schema.ruleId(), new PrincipalRuntime(schema, exact));
        }
        this.runtimes = Map.copyOf(indexed);
        this.exactRegistryFingerprint =
            exactRegistryFingerprint(this.principalSchemas, this.runtimes);
        this.preparationInventoryFingerprint = combinedPreparationFingerprint(
            RuleInventoryFingerprint.contentHash(this.preparationRules),
            this.exactRegistryFingerprint);
    }

    public List<RewriteApplicabilitySchema> principalSchemas() {
        return principalSchemas;
    }

    public String principalInventoryFingerprint() {
        return principalInventoryFingerprint;
    }

    public String preparationInventoryFingerprint() {
        return preparationInventoryFingerprint;
    }

    public String exactRegistryFingerprint() {
        return exactRegistryFingerprint;
    }

    /** Evaluates all visible principals with one shared bounded fallback. */
    public Evaluation analyze(
        String sourceExpression,
        AssumptionSignature initialAssumptions
    ) {
        String source = normalize(sourceExpression);
        AssumptionSignature assumptions = normalized(initialAssumptions);
        SharedPreparationTraversal sourceAnalysis =
            new SharedPreparationTraversal(preparationRules, bridgeBudget);
        SharedPreparationTraversal.ParsedExpression sourceParsed =
            sourceAnalysis.parse(source);
        SharedPreparationGuardFacts guardFacts =
            new SharedPreparationGuardFacts();
        Map<String, RulePreparationCoordinator.Outcome> outcomesByRule =
            new LinkedHashMap<>();
        Map<String, PatternMatchAnalyzer.Analysis> initialAnalyses =
            new LinkedHashMap<>();
        List<RulePreparationCoordinator.Outcome> nonFallbackOutcomes =
            new ArrayList<>();
        List<RewriteApplicabilitySchema> fallbackSchemas = new ArrayList<>();

        for (RewriteApplicabilitySchema schema : principalSchemas) {
            PrincipalRuntime runtime = runtimes.get(schema.ruleId());

            Optional<Transformation> direct;
            try {
                direct = directCandidate(schema.executor(), source, assumptions);
            } catch (RuntimeException exception) {
                PatternMatchAnalyzer.Analysis initial = analysisForEvidence(
                    schema, sourceParsed, sourceAnalysis);
                initialAnalyses.put(schema.ruleId(), initial);
                RulePreparationCoordinator.Outcome technical = technicalOutcome(
                    runtime,
                    initial,
                    PatternTargetedLocalBridgeSearch.Work.empty(),
                    Set.of(),
                    "UNIFIED_V2_DIRECT_REPLAY_TECHNICAL_FAILURE");
                outcomesByRule.put(schema.ruleId(), technical);
                nonFallbackOutcomes.add(technical);
                continue;
            }

            PatternMatchAnalyzer.Analysis initial = analysisForEvidence(
                schema, sourceParsed, sourceAnalysis);
            initialAnalyses.put(schema.ruleId(), initial);

            if (direct.isPresent()) {
                RulePreparationCoordinator.Outcome outcome = directOutcome(
                    runtime,
                    initial,
                    assumptions,
                    direct.orElseThrow(),
                    guardFacts);
                outcomesByRule.put(schema.ruleId(), outcome);
                nonFallbackOutcomes.add(outcome);
                continue;
            }

            if (runtime.exactRegistry().supportsPrincipal(schema.ruleId())
                    && schema.requiredAssumptions().isEmpty()) {
                Optional<ExactCandidate> exact;
                try {
                    exact = exactCandidate(runtime, source, assumptions);
                } catch (RuntimeException exception) {
                    RulePreparationCoordinator.Outcome technical = technicalOutcome(
                        runtime,
                        initial,
                        PatternTargetedLocalBridgeSearch.Work.empty(),
                        Set.of(),
                        "UNIFIED_V2_EXACT_REGISTRY_TECHNICAL_FAILURE");
                    outcomesByRule.put(schema.ruleId(), technical);
                    nonFallbackOutcomes.add(technical);
                    continue;
                }
                if (exact.isPresent()) {
                    RulePreparationCoordinator.Outcome outcome = exactOutcome(
                        runtime, initial, exact.orElseThrow());
                    outcomesByRule.put(schema.ruleId(), outcome);
                    nonFallbackOutcomes.add(outcome);
                    continue;
                }
            }

            fallbackSchemas.add(schema);
        }

        SharedFallback fallback = fallbackSchemas.isEmpty()
            ? SharedFallback.notExecuted()
            : executeSharedFallback(
                source,
                assumptions,
                fallbackSchemas,
                initialAnalyses,
                outcomesByRule,
                guardFacts);

        List<RulePreparationCoordinator.Outcome> outcomes = principalSchemas
            .stream()
            .map(schema -> Objects.requireNonNull(
                outcomesByRule.get(schema.ruleId()),
                "missing unified v2 principal outcome: " + schema.ruleId()))
            .toList();
        RulePreparationCoordinator.AggregateWork aggregateWork =
            aggregateWork(nonFallbackOutcomes, fallback.work());
        SharedPreparationGuardFacts.Work guardWork = guardFacts.work();
        SharedExecutionWork sharedWork = new SharedExecutionWork(
            SHARED_EXECUTION_REVISION,
            fallbackSchemas.stream()
                .map(RewriteApplicabilitySchema::ruleId)
                .toList(),
            sourceAnalysis.work(),
            fallback.work() == null
                ? PatternTargetedLocalBridgeSearch.Work.empty()
                : fallback.work(),
            fallback.physicalWork(),
            fallback.reachedLimits(),
            guardWork.requests(),
            guardWork.uniqueFacts(),
            guardWork.cacheHits());

        return new Evaluation(
            COORDINATOR_ID,
            repositoryRevision,
            principalInventoryFingerprint,
            preparationInventoryFingerprint,
            exactRegistryFingerprint,
            bridgeBudget,
            source,
            assumptions,
            outcomes,
            aggregateWork,
            sharedWork);
    }

    /** Recomputes the complete v2 evaluation from fresh per-request caches. */
    public Verification verify(Evaluation evaluation) {
        if (evaluation == null) {
            return new Verification(false, "EVALUATION_MISSING");
        }
        if (!COORDINATOR_ID.equals(evaluation.coordinatorId())
                || !repositoryRevision.equals(evaluation.repositoryRevision())
                || !principalInventoryFingerprint.equals(
                    evaluation.principalInventoryFingerprint())
                || !preparationInventoryFingerprint.equals(
                    evaluation.preparationInventoryFingerprint())
                || !exactRegistryFingerprint.equals(
                    evaluation.exactRegistryFingerprint())
                || !bridgeBudget.equals(evaluation.bridgeBudget())) {
            return new Verification(
                false, "COORDINATOR_CONFIGURATION_MISMATCH");
        }
        Evaluation recomputed = analyze(
            evaluation.sourceExpression(),
            evaluation.sourceAssumptions());
        return recomputed.equals(evaluation)
            ? new Verification(true, "VERIFIED")
            : new Verification(false, "EVALUATION_RECOMPUTATION_MISMATCH");
    }

    private SharedFallback executeSharedFallback(
        String source,
        AssumptionSignature assumptions,
        List<RewriteApplicabilitySchema> fallbackSchemas,
        Map<String, PatternMatchAnalyzer.Analysis> initialAnalyses,
        Map<String, RulePreparationCoordinator.Outcome> outcomesByRule,
        SharedPreparationGuardFacts guardFacts
    ) {
        try {
            SharedMultiPrincipalPreparationTraversal.Evaluation shared =
                new SharedMultiPrincipalPreparationTraversal(
                    fallbackSchemas,
                    preparationRules,
                    bridgeBudget)
                    .analyze(source, assumptions);
            SharedPreparedPrincipalReplay replay =
                new SharedPreparedPrincipalReplay(
                    preparationRules,
                    repositoryRevision,
                    bridgeBudget,
                    guardFacts);
            for (RewriteApplicabilitySchema schema : fallbackSchemas) {
                RulePreparationCoordinator.Outcome outcome = replay.replay(
                    schema,
                    shared,
                    shared.outcome(schema.ruleId()).orElseThrow());
                outcomesByRule.put(schema.ruleId(), outcome);
            }
            return new SharedFallback(
                SharedPreparedPrincipalReplay.legacyWork(shared.work()),
                shared.work().physicalWork(),
                shared.work().reachedLimits());
        } catch (RuntimeException exception) {
            for (RewriteApplicabilitySchema schema : fallbackSchemas) {
                PrincipalRuntime runtime = runtimes.get(schema.ruleId());
                outcomesByRule.put(
                    schema.ruleId(),
                    technicalOutcome(
                        runtime,
                        initialAnalyses.get(schema.ruleId()),
                        PatternTargetedLocalBridgeSearch.Work.empty(),
                        Set.of(),
                        "UNIFIED_V2_SHARED_FALLBACK_TECHNICAL_FAILURE"));
            }
            return SharedFallback.failed();
        }
    }

    private RulePreparationCoordinator.Outcome directOutcome(
        PrincipalRuntime runtime,
        PatternMatchAnalyzer.Analysis initial,
        AssumptionSignature assumptions,
        Transformation candidate,
        SharedPreparationGuardFacts guardFacts
    ) {
        SharedPreparationGuardFacts.Fact guards;
        try {
            guards = guardFacts.evaluate(runtime.schema(), initial, assumptions);
        } catch (RuntimeException exception) {
            return technicalOutcome(
                runtime,
                initial,
                PatternTargetedLocalBridgeSearch.Work.empty(),
                Set.of(),
                "UNIFIED_V2_DIRECT_GUARD_TECHNICAL_FAILURE");
        }
        if (!guards.satisfied()) {
            return rejectedByGuards(runtime, initial, guards);
        }
        return new RulePreparationCoordinator.Outcome(
            runtime.schema().ruleId(),
            runtime.schema().contentHash(),
            PatternTargetedLocalBridgeSearch.Status.DIRECT_MATCH_AVAILABLE,
            Optional.of(candidate),
            true,
            PatternTargetedLocalBridgeSearch.AnalysisSnapshot.from(initial),
            PatternTargetedLocalBridgeSearch.Work.empty(),
            Set.of(),
            "UNIFIED_V2_DIRECT_REPLAYED",
            "");
    }

    private RulePreparationCoordinator.Outcome exactOutcome(
        PrincipalRuntime runtime,
        PatternMatchAnalyzer.Analysis initial,
        ExactCandidate exact
    ) {
        return new RulePreparationCoordinator.Outcome(
            runtime.schema().ruleId(),
            runtime.schema().contentHash(),
            PatternTargetedLocalBridgeSearch.Status.PREPARED,
            Optional.of(exact.transformation()),
            true,
            PatternTargetedLocalBridgeSearch.AnalysisSnapshot.from(initial),
            exact.work(),
            Set.of(),
            "EXACT_REGISTRY_PREPARATION_REPLAYED",
            exact.certificateHash());
    }

    private RulePreparationCoordinator.Outcome rejectedByGuards(
        PrincipalRuntime runtime,
        PatternMatchAnalyzer.Analysis initial,
        SharedPreparationGuardFacts.Fact guards
    ) {
        PatternTargetedLocalBridgeSearch.Status status =
            guards.status() == SharedPreparationGuardFacts.Status.INVALID
                ? PatternTargetedLocalBridgeSearch.Status.TECHNICAL_FAILURE
                : PatternTargetedLocalBridgeSearch.Status.UNSUPPORTED;
        return new RulePreparationCoordinator.Outcome(
            runtime.schema().ruleId(),
            runtime.schema().contentHash(),
            status,
            Optional.empty(),
            false,
            PatternTargetedLocalBridgeSearch.AnalysisSnapshot.from(initial),
            PatternTargetedLocalBridgeSearch.Work.empty(),
            Set.of(),
            guards.detailCode(),
            "");
    }

    private RulePreparationCoordinator.Outcome technicalOutcome(
        PrincipalRuntime runtime,
        PatternMatchAnalyzer.Analysis initial,
        PatternTargetedLocalBridgeSearch.Work work,
        Set<String> reachedLimits,
        String detailCode
    ) {
        return new RulePreparationCoordinator.Outcome(
            runtime.schema().ruleId(),
            runtime.schema().contentHash(),
            PatternTargetedLocalBridgeSearch.Status.TECHNICAL_FAILURE,
            Optional.empty(),
            false,
            PatternTargetedLocalBridgeSearch.AnalysisSnapshot.from(initial),
            work,
            reachedLimits,
            detailCode,
            "");
    }

    private Optional<ExactCandidate> exactCandidate(
        PrincipalRuntime runtime,
        String source,
        AssumptionSignature assumptions
    ) {
        SafePreparationEngineRegistry.Execution execution =
            runtime.exactRegistry().transform(source);
        List<Transformation> prepared = execution.preparedTransformations();
        Optional<Transformation> selected = prepared.stream()
            .filter(value -> runtime.schema().ruleId().equals(value.rule()))
            .filter(Transformation::equivalencePreservingByConstruction)
            .sorted(EXACT_CANDIDATE_ORDER)
            .findFirst()
            .map(value -> withCumulativeAssumptions(value, assumptions));
        if (selected.isEmpty()) {
            return Optional.empty();
        }

        Transformation candidate = selected.orElseThrow();
        String certificateHash = exactCertificateHash(
            runtime, source, assumptions, candidate);
        int generated = execution.transformations().size();
        int analyzed = prepared.size();
        PatternTargetedLocalBridgeSearch.Work work =
            new PatternTargetedLocalBridgeSearch.Work(
                1,
                generated,
                2,
                1,
                0,
                analyzed,
                Math.max(1, generated));
        return Optional.of(new ExactCandidate(candidate, work, certificateHash));
    }

    private static Optional<Transformation> directCandidate(
        RewriteRule executor,
        String source,
        AssumptionSignature assumptions
    ) {
        return new AstRewriteTransformationEngine(
                List.of(executor),
                Integer.MAX_VALUE,
                1)
            .transform(source)
            .stream()
            .filter(value -> executor.id().equals(value.rule()))
            .findFirst()
            .map(value -> withCumulativeAssumptions(value, assumptions));
    }

    private PatternMatchAnalyzer.Analysis analysisForEvidence(
        RewriteApplicabilitySchema schema,
        SharedPreparationTraversal.ParsedExpression expression,
        SharedPreparationTraversal shared
    ) {
        try {
            return shared.analyze(
                schema.pattern(),
                schema.recognitionProfile(),
                expression);
        } catch (RuntimeException exception) {
            return technicalAnalysis(schema.pattern());
        }
    }

    private static PatternMatchAnalyzer.Analysis technicalAnalysis(
        PatternExpr pattern
    ) {
        return new PatternMatchAnalyzer.Analysis(
            PatternMatchAnalyzer.Status.INCONCLUSIVE,
            List.of(),
            Map.of(),
            List.of(),
            List.of(new ExprMatcher.MatchDiagnostic(
                "MATCH_ANALYSIS_TECHNICAL_FAILURE",
                COORDINATOR_ID)),
            0,
            0,
            0,
            0,
            patternNodeCount(pattern),
            "MATCH_ANALYSIS_TECHNICAL_FAILURE");
    }

    private static int patternNodeCount(PatternExpr pattern) {
        if (pattern instanceof PatternExpr.Operation operation) {
            return 1
                + patternNodeCount(operation.left())
                + patternNodeCount(operation.right());
        }
        if (pattern instanceof PatternExpr.Function function) {
            return 1 + function.arguments().stream()
                .mapToInt(SharedUnifiedRulePreparationCoordinator::patternNodeCount)
                .sum();
        }
        return 1;
    }

    private static Transformation withCumulativeAssumptions(
        Transformation transformation,
        AssumptionSignature sourceAssumptions
    ) {
        AssumptionSignature cumulative = AssumptionSignature.merge(
            sourceAssumptions,
            AssumptionSignature.ofExpressions(transformation.assumptions()));
        return new Transformation(
            transformation.rule(),
            transformation.transformedExpression(),
            transformation.kind(),
            transformation.mayIncreaseComplexity(),
            transformation.estimatedCostDelta(),
            transformation.equivalencePreservingByConstruction(),
            transformation.applicationKey(),
            cumulative.normalizedAssumptions(),
            transformation.packId(),
            transformation.license(),
            transformation.primitiveRuleIds());
    }

    private static RulePreparationCoordinator.AggregateWork aggregateWork(
        List<RulePreparationCoordinator.Outcome> nonFallbackOutcomes,
        PatternTargetedLocalBridgeSearch.Work sharedFallbackWork
    ) {
        RulePreparationCoordinator.AggregateWork base =
            RulePreparationCoordinator.AggregateWork.from(nonFallbackOutcomes);
        if (sharedFallbackWork == null) {
            return base;
        }
        return new RulePreparationCoordinator.AggregateWork(
            Math.addExact(base.expandedStates(),
                sharedFallbackWork.expandedStates()),
            Math.addExact(base.generatedTransitions(),
                sharedFallbackWork.generatedTransitions()),
            Math.addExact(base.discoveredStates(),
                sharedFallbackWork.discoveredStates()),
            Math.addExact(base.retainedTransitions(),
                sharedFallbackWork.retainedTransitions()),
            Math.addExact(base.duplicateTransitions(),
                sharedFallbackWork.duplicateTransitions()),
            Math.addExact(base.analyzedCandidates(),
                sharedFallbackWork.analyzedCandidates()),
            Math.max(base.maxFrontierSize(),
                sharedFallbackWork.maxFrontierSize()));
    }

    private static List<RewriteRule> exactVisibleRules(
        RewriteApplicabilitySchema schema,
        List<RewriteRule> preparationRules
    ) {
        Map<String, RewriteRule> rules = new LinkedHashMap<>();
        rules.put(schema.ruleId(), schema.executor());
        for (RewriteRule rule : preparationRules) {
            rules.putIfAbsent(rule.id(), rule);
        }
        return List.copyOf(rules.values());
    }

    private static List<RewriteRule> validatePreparationRules(
        List<? extends RewriteRule> supplied
    ) {
        Objects.requireNonNull(supplied, "preparationRules");
        Map<String, RewriteRule> result = new LinkedHashMap<>();
        for (RewriteRule rule : supplied) {
            RewriteRule checked = Objects.requireNonNull(
                rule, "preparation rule");
            if (!checked.isEquivalencePreservingByConstruction()) {
                throw new IllegalArgumentException(
                    "preparation rules must preserve equivalence: "
                        + checked.id());
            }
            if (result.put(checked.id(), checked) != null) {
                throw new IllegalArgumentException(
                    "duplicate preparation rule ID: " + checked.id());
            }
        }
        return List.copyOf(result.values());
    }

    private String normalize(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException(
                "sourceExpression must not be blank");
        }
        return ExpressionFormatter.format(parser.parseTerm(expression));
    }

    private static AssumptionSignature normalized(
        AssumptionSignature assumptions
    ) {
        AssumptionSignature supplied = Objects.requireNonNull(
            assumptions, "initialAssumptions");
        return AssumptionSignature.ofExpressions(
            supplied.normalizedAssumptions());
    }

    private static String exactRegistryFingerprint(
        List<RewriteApplicabilitySchema> schemas,
        Map<String, PrincipalRuntime> runtimes
    ) {
        StringBuilder descriptor = new StringBuilder();
        append(descriptor, EXACT_REGISTRY_INVENTORY_REVISION);
        append(descriptor, Integer.toString(schemas.size()));
        for (RewriteApplicabilitySchema schema : schemas) {
            PrincipalRuntime runtime = runtimes.get(schema.ruleId());
            append(descriptor, schema.contentHash());
            append(descriptor, runtime.exactRegistry().registryFingerprint());
        }
        return sha256(descriptor.toString());
    }

    private static String combinedPreparationFingerprint(
        String localFingerprint,
        String exactFingerprint
    ) {
        StringBuilder descriptor = new StringBuilder();
        append(descriptor, COMBINED_PREPARATION_REVISION);
        append(descriptor, localFingerprint);
        append(descriptor, exactFingerprint);
        append(descriptor, SharedPreparationTraversal.REVISION);
        append(descriptor, SharedMultiPrincipalPreparationTraversal.REVISION);
        append(descriptor, SharedPreparationGuardFacts.REVISION);
        append(descriptor, SharedPreparedPrincipalReplay.CERTIFICATE_SCHEMA);
        return sha256(descriptor.toString());
    }

    private String exactCertificateHash(
        PrincipalRuntime runtime,
        String source,
        AssumptionSignature assumptions,
        Transformation candidate
    ) {
        StringBuilder descriptor = new StringBuilder();
        append(descriptor, COORDINATOR_ID);
        append(descriptor, repositoryRevision);
        append(descriptor, runtime.exactRegistry().registryFingerprint());
        append(descriptor, runtime.schema().contentHash());
        append(descriptor, source);
        append(descriptor, assumptions.fingerprint());
        append(descriptor, candidate.rule());
        append(descriptor, candidate.transformedExpression());
        append(descriptor, candidate.applicationKey());
        append(descriptor, String.join("\u0000", candidate.assumptions()));
        append(descriptor, String.join("\u0000", candidate.primitiveRuleIds()));
        return sha256(descriptor.toString());
    }

    private static String requireRevision(String revision) {
        if (revision == null || !revision.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException(
                "repositoryRevision must be a lowercase commit SHA");
        }
        return revision;
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

    private record PrincipalRuntime(
        RewriteApplicabilitySchema schema,
        SafePreparationEngineRegistry.Registration exactRegistry
    ) {
        private PrincipalRuntime {
            schema = Objects.requireNonNull(schema, "schema");
            exactRegistry = Objects.requireNonNull(exactRegistry, "exactRegistry");
        }
    }

    private record ExactCandidate(
        Transformation transformation,
        PatternTargetedLocalBridgeSearch.Work work,
        String certificateHash
    ) {
        private ExactCandidate {
            transformation = Objects.requireNonNull(
                transformation, "transformation");
            work = Objects.requireNonNull(work, "work");
            if (certificateHash == null
                    || !certificateHash.matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                    "exact candidate certificate is invalid");
            }
        }
    }

    private record SharedFallback(
        PatternTargetedLocalBridgeSearch.Work work,
        SharedPreparationTraversal.Work physicalWork,
        Set<String> reachedLimits
    ) {
        private SharedFallback {
            reachedLimits = Set.copyOf(Objects.requireNonNull(
                reachedLimits, "reachedLimits"));
            physicalWork = Objects.requireNonNull(physicalWork, "physicalWork");
        }

        static SharedFallback notExecuted() {
            return new SharedFallback(null, zeroPhysicalWork(), Set.of());
        }

        static SharedFallback failed() {
            return new SharedFallback(null, zeroPhysicalWork(), Set.of());
        }
    }

    private static SharedPreparationTraversal.Work zeroPhysicalWork() {
        return new SharedPreparationTraversal.Work(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public record SharedExecutionWork(
        String revision,
        List<String> fallbackPrincipalIds,
        SharedPreparationTraversal.Work sourceAnalysisWork,
        PatternTargetedLocalBridgeSearch.Work fallbackWork,
        SharedPreparationTraversal.Work fallbackPhysicalWork,
        Set<String> fallbackReachedLimits,
        long guardRequests,
        long uniqueGuardFacts,
        long guardCacheHits
    ) {
        public SharedExecutionWork {
            if (!SHARED_EXECUTION_REVISION.equals(revision)) {
                throw new IllegalArgumentException(
                    "unexpected shared execution work revision");
            }
            fallbackPrincipalIds = List.copyOf(Objects.requireNonNull(
                fallbackPrincipalIds, "fallbackPrincipalIds"));
            sourceAnalysisWork = Objects.requireNonNull(
                sourceAnalysisWork, "sourceAnalysisWork");
            fallbackWork = Objects.requireNonNull(fallbackWork, "fallbackWork");
            fallbackPhysicalWork = Objects.requireNonNull(
                fallbackPhysicalWork, "fallbackPhysicalWork");
            fallbackReachedLimits = Set.copyOf(Objects.requireNonNull(
                fallbackReachedLimits, "fallbackReachedLimits"));
            if (guardRequests < 0 || uniqueGuardFacts < 0 || guardCacheHits < 0
                    || guardRequests != uniqueGuardFacts + guardCacheHits) {
                throw new IllegalArgumentException(
                    "shared guard work counters are inconsistent");
            }
            if (fallbackPrincipalIds.isEmpty()
                    && (fallbackWork.generatedTransitions() != 0
                        || fallbackPhysicalWork.uniqueExpansions() != 0)) {
                throw new IllegalArgumentException(
                    "fallback work requires fallback principals");
            }
        }

        public boolean fallbackExecuted() {
            return !fallbackPrincipalIds.isEmpty();
        }
    }

    public record Evaluation(
        String coordinatorId,
        String repositoryRevision,
        String principalInventoryFingerprint,
        String preparationInventoryFingerprint,
        String exactRegistryFingerprint,
        PatternTargetedLocalBridgeSearch.Budget bridgeBudget,
        String sourceExpression,
        AssumptionSignature sourceAssumptions,
        List<RulePreparationCoordinator.Outcome> outcomes,
        RulePreparationCoordinator.AggregateWork aggregateWork,
        SharedExecutionWork sharedExecutionWork
    ) {
        public Evaluation {
            if (!COORDINATOR_ID.equals(coordinatorId)
                    || repositoryRevision == null
                    || !repositoryRevision.matches("[0-9a-f]{40}")
                    || !hash(principalInventoryFingerprint)
                    || !hash(preparationInventoryFingerprint)
                    || !hash(exactRegistryFingerprint)) {
                throw new IllegalArgumentException(
                    "shared unified evaluation identity is invalid");
            }
            bridgeBudget = Objects.requireNonNull(bridgeBudget, "bridgeBudget");
            if (sourceExpression == null || sourceExpression.isBlank()) {
                throw new IllegalArgumentException(
                    "sourceExpression must not be blank");
            }
            sourceAssumptions = normalized(sourceAssumptions);
            outcomes = List.copyOf(Objects.requireNonNull(outcomes, "outcomes"));
            if (outcomes.isEmpty()) {
                throw new IllegalArgumentException(
                    "evaluation requires principal outcomes");
            }
            Set<String> ids = new LinkedHashSet<>();
            for (RulePreparationCoordinator.Outcome outcome : outcomes) {
                if (!ids.add(outcome.ruleId())) {
                    throw new IllegalArgumentException(
                        "duplicate outcome rule ID: " + outcome.ruleId());
                }
            }
            aggregateWork = Objects.requireNonNull(aggregateWork, "aggregateWork");
            sharedExecutionWork = Objects.requireNonNull(
                sharedExecutionWork, "sharedExecutionWork");
        }

        public Optional<RulePreparationCoordinator.Outcome> outcome(
            String ruleId
        ) {
            return outcomes.stream()
                .filter(value -> value.ruleId().equals(ruleId))
                .findFirst();
        }

        public List<Transformation> candidates() {
            return outcomes.stream()
                .flatMap(value -> value.candidate().stream())
                .toList();
        }

        private static boolean hash(String value) {
            return value != null && value.matches("sha256:[0-9a-f]{64}");
        }
    }

    public record Verification(boolean valid, String detailCode) {
        public Verification {
            if (detailCode == null || detailCode.isBlank()) {
                throw new IllegalArgumentException(
                    "detailCode must not be blank");
            }
        }
    }
}
