package de.regelsuche.search.reachability;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.Expr;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.EquivalentExpressionProvider;
import de.regelsuche.transform.ExprMatcher;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternMatchAnalyzer;
import de.regelsuche.transform.RecognitionProfile;
import de.regelsuche.transform.RequiredAssumptionTemplate;
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
 * Product-facing coordinator for direct replay, exact specialized preparation
 * and the bounded pattern-targeted local bridge fallback.
 *
 * <p>Concrete executor replay is always attempted before applicability-schema
 * analysis. The exact registry follows only when a registered specialist owns
 * the principal; the generic local bridge remains the final fallback. Guarded
 * schemas stay on the local path until an exact specialist exposes terminal
 * matcher bindings. Technical exceptions become retained fail-closed outcomes
 * rather than ordinary non-applicability.</p>
 */
public final class UnifiedRulePreparationCoordinator {
    /** Typed matrix/system principals retain their relation through concrete replay. */
    public static <S, T, C, R, D> de.regelsuche.representation.RepresentationPreparation.Outcome<T, C, R, D>
            prepareRepresentation(S source,
                de.regelsuche.representation.RepresentationBridge<S, T, C> bridge,
                Set<de.regelsuche.representation.RepresentationBridge.Relation> acceptedRelations,
                de.regelsuche.representation.RepresentationBridge<T, R, D> principal,
                de.regelsuche.representation.RepresentationBridge.Budget budget) {
        return de.regelsuche.representation.RepresentationPreparation.analyze(
            source, bridge, acceptedRelations, principal, budget);
    }

    public static final String COORDINATOR_ID =
        "regelsuche.unified-safe-rule-preparation-coordinator/v1";
    private static final String EXACT_REGISTRY_INVENTORY_REVISION =
        "regelsuche.unified-exact-registry-inventory/v1";
    private static final String COMBINED_PREPARATION_REVISION =
        "regelsuche.unified-preparation-inventory/v1";

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
    private final PatternMatchAnalyzer analyzer = new PatternMatchAnalyzer();

    public UnifiedRulePreparationCoordinator(
        List<RewriteApplicabilitySchema> principalSchemas,
        List<? extends RewriteRule> preparationRules,
        String repositoryRevision,
        PatternTargetedLocalBridgeSearch.Budget bridgeBudget
    ) {
        this.repositoryRevision = requireRevision(repositoryRevision);
        this.bridgeBudget = Objects.requireNonNull(
            bridgeBudget, "bridgeBudget");

        RulePreparationCoordinator validation =
            new RulePreparationCoordinator(
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
            RulePreparationCoordinator local =
                new RulePreparationCoordinator(
                    List.of(schema),
                    this.preparationRules,
                    this.repositoryRevision,
                    this.bridgeBudget);
            indexed.put(
                schema.ruleId(),
                new PrincipalRuntime(schema, exact, local));
        }
        this.runtimes = Map.copyOf(indexed);
        this.exactRegistryFingerprint =
            exactRegistryFingerprint(this.principalSchemas, this.runtimes);
        this.preparationInventoryFingerprint = combinedPreparationFingerprint(
            validation.preparationInventoryFingerprint(),
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

    public Evaluation analyze(
        String sourceExpression,
        AssumptionSignature initialAssumptions
    ) {
        String source = normalize(sourceExpression);
        AssumptionSignature assumptions = normalized(initialAssumptions);
        List<RulePreparationCoordinator.Outcome> outcomes = new ArrayList<>();
        for (RewriteApplicabilitySchema schema : principalSchemas) {
            PrincipalRuntime runtime = runtimes.get(schema.ruleId());

            Optional<Transformation> direct;
            try {
                direct = directCandidate(
                    schema.executor(), source, assumptions);
            } catch (RuntimeException exception) {
                outcomes.add(technicalOutcome(
                    runtime,
                    analysisForEvidence(schema, source),
                    PatternTargetedLocalBridgeSearch.Work.empty(),
                    "UNIFIED_DIRECT_REPLAY_TECHNICAL_FAILURE"));
                continue;
            }

            PatternMatchAnalyzer.Analysis initial =
                analysisForEvidence(schema, source);
            if (direct.isPresent()) {
                GuardCheck guards = checkRequiredAssumptions(
                    schema, initial, assumptions);
                outcomes.add(guards.authorized()
                    ? directOutcome(runtime, initial, direct.orElseThrow())
                    : rejectedByGuards(runtime, initial, guards));
                continue;
            }

            if (runtime.exactRegistry().supportsPrincipal(schema.ruleId())
                    && schema.requiredAssumptions().isEmpty()) {
                Optional<ExactCandidate> exact;
                try {
                    exact = exactCandidate(runtime, source, assumptions);
                } catch (RuntimeException exception) {
                    outcomes.add(technicalOutcome(
                        runtime,
                        initial,
                        PatternTargetedLocalBridgeSearch.Work.empty(),
                        "UNIFIED_EXACT_REGISTRY_TECHNICAL_FAILURE"));
                    continue;
                }
                if (exact.isPresent()) {
                    outcomes.add(exactOutcome(
                        runtime, initial, exact.orElseThrow()));
                    continue;
                }
            }

            try {
                RulePreparationCoordinator.Evaluation local =
                    runtime.localCoordinator().analyze(source, assumptions);
                outcomes.add(local.outcome(schema.ruleId()).orElseThrow());
            } catch (RuntimeException exception) {
                outcomes.add(technicalOutcome(
                    runtime,
                    initial,
                    PatternTargetedLocalBridgeSearch.Work.empty(),
                    "UNIFIED_LOCAL_BRIDGE_TECHNICAL_FAILURE"));
            }
        }

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
            RulePreparationCoordinator.AggregateWork.from(outcomes));
    }

    /** The legacy analyze method and its evidence schema remain independent of this opt-in policy. */
    public String profileHash(PreparationProfile profile) {
        Objects.requireNonNull(profile, "profile");
        var material = new StringBuilder();
        for (String input : List.of("regelsuche.whole-source-preparation-profile/v1", COORDINATOR_ID,
                profile.name(), repositoryRevision, principalInventoryFingerprint, preparationInventoryFingerprint,
                exactRegistryFingerprint, bridgeBudget.identity())) append(material, input);
        return sha256(material.toString());
    }

    /** Runs the enabled stages on each principal with no reference result or case labels. */
    public List<PreparationProfile.Result> analyzeProfile(PreparationProfile profile, String sourceExpression,
            AssumptionSignature initialAssumptions) {
        Objects.requireNonNull(profile, "profile");
        String source = normalize(sourceExpression);
        var expression = parser.parseTerm(source);
        var assumptions = normalized(initialAssumptions);
        boolean oversized = new ExpressionCanonicalizer().astNodeCount(source) > bridgeBudget.maxExpressionNodes();
        return principalSchemas.stream().map(schema -> new ProfileRun(runtimes.get(schema.ruleId()),
            source, expression, assumptions).run(profile, oversized)).toList();
    }

    private final class ProfileRun {
        private final PrincipalRuntime runtime;
        private final RewriteApplicabilitySchema schema;
        private final String source;
        private final Expr expression;
        private final AssumptionSignature assumptions;
        private final List<PreparationProfile.Stage> stages = new ArrayList<>();

        ProfileRun(PrincipalRuntime runtime, String source, Expr expression, AssumptionSignature assumptions) {
            this.runtime = runtime;
            this.schema = runtime.schema();
            this.source = source;
            this.expression = expression;
            this.assumptions = assumptions;
        }

        PreparationProfile.Result run(PreparationProfile profile, boolean oversized) {
            if (ExplicitZeroConditions.conflict(assumptions)) return negative("UNSUPPORTED", "CONTRADICTED_NONZERO_PRECONDITION");
            if (oversized) return negative("BUDGET_INCONCLUSIVE", "SOURCE_EXCEEDS_MAX_EXPRESSION_NODES");
            var result = match("DIRECT_ONLY", RecognitionProfile.exact());
            if (result.isEmpty() && profile != PreparationProfile.DIRECT_ONLY
                    && !schema.recognitionProfile().equals(RecognitionProfile.exact())) {
                result = match("THEORY_MATCHING", schema.recognitionProfile());
            }
            if (result.isEmpty() && (profile == PreparationProfile.SAFE_EXACT_PREPARATION
                    || profile == PreparationProfile.SAFE_PREPARATION_PLUS_LOCAL_BRIDGE)) {
                result = specialist();
            }
            if (result.isPresent()) return result.orElseThrow();
            if (profile == PreparationProfile.SAFE_PREPARATION_PLUS_LOCAL_BRIDGE) return local();
            return negative("NO_MATCH_IN_ENABLED_STAGES", "ENABLED_STAGES_EXHAUSTED");
        }

        private Optional<PreparationProfile.Result> match(String stage, RecognitionProfile recognition) {
            PatternMatchAnalyzer.Analysis analysis;
            try {
                analysis = analyzer.analyze(schema.pattern(), expression, recognition, new ExprMatcher.MatchOptions(
                    EquivalentExpressionProvider.identity(), bridgeBudget.maxMatchResults(),
                    bridgeBudget.maxMatchSteps(), bridgeBudget.maxPatternBranches()));
            } catch (RuntimeException exception) {
                stages.add(new PreparationProfile.Stage(stage, "MATCHER_FAILURE", 0, 0, 0, 0, 0, 0, null, Set.of()));
                return Optional.of(negative("TECHNICAL_FAILURE", "MATCHER_FAILURE"));
            }
            if (!analysis.matched()) {
                var limits = analysis.diagnostics().stream().map(ExprMatcher.MatchDiagnostic::code)
                    .collect(java.util.stream.Collectors.toSet());
                matchWork(stage, analysis, analysis.detailCode(), 0, limits);
                return analysis.status() == PatternMatchAnalyzer.Status.INCONCLUSIVE
                    ? Optional.of(negative("BUDGET_INCONCLUSIVE", analysis.detailCode())) : Optional.empty();
            }
            GuardCheck guards = checkRequiredAssumptions(schema, analysis, assumptions);
            if (!guards.authorized()) {
                matchWork(stage, analysis, guards.detailCode(), 0, Set.of());
                return Optional.of(negative(guards.failureStatus().name(), guards.detailCode()));
            }
            return Optional.of(replay(stage, analysis));
        }

        private PreparationProfile.Result replay(String stage, PatternMatchAnalyzer.Analysis analysis) {
            List<Transformation> rootMatches = new ArrayList<>();
            try {
                new AstRewriteTransformationEngine(List.of(schema.executor()), Integer.MAX_VALUE, 1)
                    .transformWithOccurrences(source, generated -> {
                        if (generated.position().path().isEmpty()) rootMatches.add(generated.transformation());
                    });
            } catch (RuntimeException exception) {
                matchWork(stage, analysis, "EXECUTOR_FAILURE", 1, Set.of());
                return negative("TECHNICAL_FAILURE", "EXECUTOR_FAILURE");
            }
            if (rootMatches.isEmpty()) {
                matchWork(stage, analysis, "EXECUTOR_REJECTED_SOURCE", 1, Set.of());
                return negative("UNSUPPORTED", "EXECUTOR_REJECTED_SOURCE");
            }
            matchWork(stage, analysis, "ROOT_EXECUTOR_REPLAYED", 1, Set.of());
            return positive("DIRECT_MATCH_AVAILABLE", stage, "ROOT_EXECUTOR_REPLAYED",
                withCumulativeAssumptions(rootMatches.getFirst(), assumptions), "");
        }

        private void matchWork(String stage, PatternMatchAnalyzer.Analysis analysis, String detail, int replays,
                Set<String> limits) {
            stages.add(new PreparationProfile.Stage(stage, detail, analysis.evaluatedSteps(), analysis.patternBranches(),
                analysis.structuralComparisons(), replays, 0, 0, null, limits));
        }

        private Optional<PreparationProfile.Result> specialist() {
            String stage = "EXACT_SPECIALIST";
            boolean supported = runtime.exactRegistry().supportsPrincipal(schema.ruleId());
            if (!supported || !schema.requiredAssumptions().isEmpty()) {
                String detail = supported
                    ? "GUARDED_SPECIALIST_BINDINGS_UNAVAILABLE" : "NO_REGISTERED_SPECIALIST";
                stages.add(new PreparationProfile.Stage(stage, detail, 0, 0, 0, 0, 0, 0, null, Set.of()));
                return Optional.empty();
            }
            try {
                var execution = runtime.exactRegistry().transform(source);
                var exact = exactCandidate(runtime, source, assumptions, execution);
                String detail = exact.isPresent() ? "EXACT_REGISTRY_PREPARATION_REPLAYED" : "NO_SPECIALIST_RESULT";
                stages.add(new PreparationProfile.Stage(stage, detail, 0, 0, 0, 0, 1,
                    execution.transformations().size(), null, Set.of()));
                return exact.map(candidate -> positive("PREPARED", stage, detail, candidate.transformation(),
                    candidate.certificateHash()));
            } catch (RuntimeException exception) {
                stages.add(new PreparationProfile.Stage(stage, "EXACT_REGISTRY_FAILURE", 0, 0, 0, 0, 1, 0, null, Set.of()));
                return Optional.of(negative("TECHNICAL_FAILURE", "EXACT_REGISTRY_FAILURE"));
            }
        }

        private PreparationProfile.Result local() {
            String stage = "LOCAL_BRIDGE";
            try {
                var outcome = runtime.localCoordinator().analyzeBridge(schema.ruleId(), source, assumptions);
                stages.add(new PreparationProfile.Stage(stage, outcome.detailCode(), 0, 0, 0, 0, 0, 0,
                    outcome.work(), outcome.reachedLimits()));
                if (outcome.direct()) return negative("TECHNICAL_FAILURE", "DIRECT_STAGE_DISAGREES_WITH_BRIDGE");
                return outcome.prepared()
                    ? positive(outcome.status().name(), stage, outcome.detailCode(), outcome.candidate().orElseThrow(),
                        outcome.bridgeCertificateHash())
                    : negative(outcome.status().name(), outcome.detailCode());
            } catch (RuntimeException exception) {
                stages.add(new PreparationProfile.Stage(stage, "LOCAL_BRIDGE_FAILURE", 0, 0, 0, 0, 0, 0, null, Set.of()));
                return negative("TECHNICAL_FAILURE", "LOCAL_BRIDGE_FAILURE");
            }
        }

        private PreparationProfile.Result positive(String status, String stage, String detail,
                Transformation candidate, String certificateHash) {
            return new PreparationProfile.Result(schema.ruleId(), schema.contentHash(), status, stage, detail,
                Optional.of(candidate), certificateHash, stages);
        }

        private PreparationProfile.Result negative(String status, String detail) {
            return new PreparationProfile.Result(schema.ruleId(), schema.contentHash(), status, "NONE", detail,
                Optional.empty(), "", stages);
        }
    }

    public Verification verify(Evaluation evaluation) {
        if (evaluation == null) {
            return new Verification(false, "EVALUATION_MISSING");
        }
        if (!COORDINATOR_ID.equals(evaluation.coordinatorId())
                || !repositoryRevision.equals(
                    evaluation.repositoryRevision())
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

    private RulePreparationCoordinator.Outcome directOutcome(
        PrincipalRuntime runtime,
        PatternMatchAnalyzer.Analysis initial,
        Transformation candidate
    ) {
        return new RulePreparationCoordinator.Outcome(
            runtime.schema().ruleId(),
            runtime.schema().contentHash(),
            PatternTargetedLocalBridgeSearch.Status.DIRECT_MATCH_AVAILABLE,
            Optional.of(candidate),
            true,
            PatternTargetedLocalBridgeSearch.AnalysisSnapshot.from(initial),
            PatternTargetedLocalBridgeSearch.Work.empty(),
            Set.of(),
            "UNIFIED_DIRECT_REPLAYED",
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
        GuardCheck guards
    ) {
        return new RulePreparationCoordinator.Outcome(
            runtime.schema().ruleId(),
            runtime.schema().contentHash(),
            guards.failureStatus(),
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
            Set.of(),
            detailCode,
            "");
    }

    private Optional<ExactCandidate> exactCandidate(
        PrincipalRuntime runtime,
        String source,
        AssumptionSignature assumptions
    ) {
        return exactCandidate(runtime, source, assumptions, runtime.exactRegistry().transform(source));
    }

    private Optional<ExactCandidate> exactCandidate(PrincipalRuntime runtime, String source, AssumptionSignature assumptions,
            SafePreparationEngineRegistry.Execution execution) {
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
            runtime,
            source,
            assumptions,
            candidate);
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
        return Optional.of(new ExactCandidate(
            candidate, work, certificateHash));
    }

    private Optional<Transformation> directCandidate(
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
            .map(value -> withCumulativeAssumptions(
                value, assumptions));
    }

    private PatternMatchAnalyzer.Analysis analysisForEvidence(
        RewriteApplicabilitySchema schema,
        String expression
    ) {
        try {
            return analyzer.analyze(
                schema.pattern(),
                parser.parseTerm(expression),
                schema.recognitionProfile(),
                new ExprMatcher.MatchOptions(
                    EquivalentExpressionProvider.identity(),
                    bridgeBudget.maxMatchResults(),
                    bridgeBudget.maxMatchSteps(),
                    bridgeBudget.maxPatternBranches()));
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
                .mapToInt(UnifiedRulePreparationCoordinator::patternNodeCount)
                .sum();
        }
        return 1;
    }

    private GuardCheck checkRequiredAssumptions(
        RewriteApplicabilitySchema schema,
        PatternMatchAnalyzer.Analysis analysis,
        AssumptionSignature available
    ) {
        List<RequiredAssumptionTemplate> templates =
            schema.requiredAssumptions();
        if (templates.isEmpty()) {
            return GuardCheck.authorized("NO_REQUIRED_ASSUMPTIONS");
        }
        if (!analysis.matched()) {
            return GuardCheck.unknown(
                "REQUIRED_ASSUMPTION_BINDINGS_UNAVAILABLE");
        }
        try {
            Set<String> known = new LinkedHashSet<>(
                available.normalizedAssumptions());
            for (RequiredAssumptionTemplate template : templates) {
                Assumption required = template.instantiate(
                    analysis.bindings());
                String normalized = AssumptionSignature.normalizeExpression(
                    required.expression());
                if (!known.contains(normalized)) {
                    return GuardCheck.unknown(
                        "REQUIRED_ASSUMPTION_UNKNOWN");
                }
            }
            return GuardCheck.authorized(
                "REQUIRED_ASSUMPTIONS_SATISFIED");
        } catch (RuntimeException exception) {
            return GuardCheck.invalid(
                "REQUIRED_ASSUMPTION_TEMPLATE_INVALID");
        }
    }

    private static Transformation withCumulativeAssumptions(
        Transformation transformation,
        AssumptionSignature sourceAssumptions
    ) {
        AssumptionSignature cumulative = AssumptionSignature.merge(
            sourceAssumptions,
            AssumptionSignature.ofExpressions(
                transformation.assumptions()));
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
            append(descriptor,
                runtime.exactRegistry().registryFingerprint());
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
        append(descriptor,
            runtime.exactRegistry().registryFingerprint());
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
        SafePreparationEngineRegistry.Registration exactRegistry,
        RulePreparationCoordinator localCoordinator
    ) {
        private PrincipalRuntime {
            schema = Objects.requireNonNull(schema, "schema");
            exactRegistry = Objects.requireNonNull(
                exactRegistry, "exactRegistry");
            localCoordinator = Objects.requireNonNull(
                localCoordinator, "localCoordinator");
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

    private record GuardCheck(
        boolean authorized,
        boolean invalid,
        String detailCode
    ) {
        private GuardCheck {
            if (authorized && invalid) {
                throw new IllegalArgumentException(
                    "authorized guard check cannot be invalid");
            }
            if (detailCode == null || detailCode.isBlank()) {
                throw new IllegalArgumentException(
                    "guard detailCode must not be blank");
            }
        }

        static GuardCheck authorized(String detailCode) {
            return new GuardCheck(true, false, detailCode);
        }

        static GuardCheck unknown(String detailCode) {
            return new GuardCheck(false, false, detailCode);
        }

        static GuardCheck invalid(String detailCode) {
            return new GuardCheck(false, true, detailCode);
        }

        PatternTargetedLocalBridgeSearch.Status failureStatus() {
            return invalid
                ? PatternTargetedLocalBridgeSearch.Status.TECHNICAL_FAILURE
                : PatternTargetedLocalBridgeSearch.Status.UNSUPPORTED;
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
        RulePreparationCoordinator.AggregateWork aggregateWork
    ) {
        public Evaluation {
            if (!COORDINATOR_ID.equals(coordinatorId)
                    || repositoryRevision == null
                    || !repositoryRevision.matches("[0-9a-f]{40}")
                    || !hash(principalInventoryFingerprint)
                    || !hash(preparationInventoryFingerprint)
                    || !hash(exactRegistryFingerprint)) {
                throw new IllegalArgumentException(
                    "unified evaluation identity is invalid");
            }
            bridgeBudget = Objects.requireNonNull(
                bridgeBudget, "bridgeBudget");
            if (sourceExpression == null || sourceExpression.isBlank()) {
                throw new IllegalArgumentException(
                    "sourceExpression must not be blank");
            }
            sourceAssumptions = normalized(sourceAssumptions);
            outcomes = List.copyOf(
                Objects.requireNonNull(outcomes, "outcomes"));
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
            aggregateWork = Objects.requireNonNull(
                aggregateWork, "aggregateWork");
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

        public long directApplications() {
            return outcomes.stream()
                .filter(RulePreparationCoordinator.Outcome::direct)
                .count();
        }

        public long preparedApplications() {
            return outcomes.stream()
                .filter(RulePreparationCoordinator.Outcome::prepared)
                .count();
        }

        private static boolean hash(String value) {
            return value != null
                && value.matches("sha256:[0-9a-f]{64}");
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
