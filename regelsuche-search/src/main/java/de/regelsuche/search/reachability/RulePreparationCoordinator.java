package de.regelsuche.search.reachability;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.Expr;
import de.regelsuche.knowledge.RuleDescriptor;
import de.regelsuche.knowledge.RuleInventoryFingerprint;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.EquivalentExpressionProvider;
import de.regelsuche.transform.ExprMatcher;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternMatchAnalyzer;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RequiredAssumptionTemplate;
import de.regelsuche.transform.RewriteApplicabilitySchema;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.Transformation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
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
 * One deterministic policy boundary for direct and bounded prepared
 * applicability of several explicitly schema-bearing rules.
 *
 * <p>The coordinator does not infer schemas. Concrete replay is attempted
 * before any schema-directed preparation. Required typed side conditions must
 * already be available in the cumulative assumption context; unknown guards
 * never authorize a candidate. Every prepared candidate additionally requires
 * independent bridge verification.</p>
 */
public final class RulePreparationCoordinator {
    public static final String COORDINATOR_ID =
        "regelsuche.safe-rule-preparation-coordinator/v1";
    private static final String SCHEMA_INVENTORY_REVISION =
        "regelsuche.applicability-schema-inventory/v1";
    private static final PatternExpr INTERNAL_RESULT_PLACEHOLDER =
        PatternExpr.var("COORDINATOR_INTERNAL_RESULT");

    private final List<RewriteApplicabilitySchema> principalSchemas;
    private final List<RewriteRule> preparationRules;
    private final Map<String, PrincipalRuntime> runtimes;
    private final String repositoryRevision;
    private final String principalInventoryFingerprint;
    private final String preparationInventoryFingerprint;
    private final PatternTargetedLocalBridgeSearch.Budget bridgeBudget;
    private final ExpressionParser parser = new ExpressionParser();
    private final PatternMatchAnalyzer analyzer = new PatternMatchAnalyzer();

    public RulePreparationCoordinator(
        List<RewriteApplicabilitySchema> principalSchemas,
        List<? extends RewriteRule> preparationRules,
        String repositoryRevision,
        PatternTargetedLocalBridgeSearch.Budget bridgeBudget
    ) {
        this.principalSchemas = validatePrincipalSchemas(principalSchemas);
        this.preparationRules = validatePreparationRules(preparationRules);
        this.repositoryRevision = requireRevision(repositoryRevision);
        this.bridgeBudget = Objects.requireNonNull(
            bridgeBudget, "bridgeBudget");
        this.principalInventoryFingerprint =
            schemaInventoryFingerprint(this.principalSchemas);
        this.preparationInventoryFingerprint =
            RuleInventoryFingerprint.contentHash(this.preparationRules);
        Map<String, PrincipalRuntime> indexed = new LinkedHashMap<>();
        for (RewriteApplicabilitySchema schema : this.principalSchemas) {
            PatternRewriteRule adapter = new CoordinatorPatternAdapter(schema);
            indexed.put(
                schema.ruleId(),
                new PrincipalRuntime(
                    schema,
                    adapter,
                    new PatternTargetedLocalBridgeSearch(
                        adapter,
                        this.preparationRules,
                        this.repositoryRevision,
                        this.bridgeBudget)));
        }
        this.runtimes = Collections.unmodifiableMap(indexed);
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

    /** Evaluates every declared principal under one frozen policy. */
    public Evaluation analyze(
        String sourceExpression,
        AssumptionSignature initialAssumptions
    ) {
        String source = normalize(sourceExpression);
        AssumptionSignature assumptions = normalized(initialAssumptions);
        List<Outcome> outcomes = new ArrayList<>();
        for (RewriteApplicabilitySchema schema : principalSchemas) {
            PrincipalRuntime runtime = runtimes.get(schema.ruleId());
            Optional<Transformation> direct = directCandidate(
                runtime.adapter(), source, assumptions);
            if (direct.isPresent()) {
                outcomes.add(directOutcome(
                    runtime,
                    source,
                    assumptions,
                    direct.orElseThrow()));
                continue;
            }
            PatternTargetedLocalBridgeSearch.Attempt attempt =
                runtime.search().analyze(source, assumptions);
            outcomes.add(outcome(
                runtime,
                source,
                assumptions,
                attempt));
        }
        return new Evaluation(
            COORDINATOR_ID,
            repositoryRevision,
            principalInventoryFingerprint,
            preparationInventoryFingerprint,
            bridgeBudget,
            source,
            assumptions,
            outcomes,
            AggregateWork.from(outcomes));
    }

    /** Recomputes the complete deterministic evaluation and all bridge replay. */
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
                || !bridgeBudget.equals(evaluation.bridgeBudget())) {
            return new Verification(false,
                "COORDINATOR_CONFIGURATION_MISMATCH");
        }
        Evaluation recomputed = analyze(
            evaluation.sourceExpression(),
            evaluation.sourceAssumptions());
        return recomputed.equals(evaluation)
            ? new Verification(true, "VERIFIED")
            : new Verification(false, "EVALUATION_RECOMPUTATION_MISMATCH");
    }

    private Outcome directOutcome(
        PrincipalRuntime runtime,
        String source,
        AssumptionSignature assumptions,
        Transformation candidate
    ) {
        PatternMatchAnalyzer.Analysis analysis = analyzePattern(
            runtime.schema(), source);
        GuardCheck guards = checkRequiredAssumptions(
            runtime.schema(), analysis, assumptions);
        if (!guards.authorized()) {
            return rejectedByGuards(
                runtime,
                analysis,
                PatternTargetedLocalBridgeSearch.Work.empty(),
                Set.of(),
                guards);
        }
        return new Outcome(
            runtime.schema().ruleId(),
            runtime.schema().contentHash(),
            PatternTargetedLocalBridgeSearch.Status.DIRECT_MATCH_AVAILABLE,
            Optional.of(candidate),
            true,
            PatternTargetedLocalBridgeSearch.AnalysisSnapshot.from(analysis),
            PatternTargetedLocalBridgeSearch.Work.empty(),
            Set.of(),
            "COORDINATOR_DIRECT_REPLAYED",
            "");
    }

    private Outcome outcome(
        PrincipalRuntime runtime,
        String source,
        AssumptionSignature sourceAssumptions,
        PatternTargetedLocalBridgeSearch.Attempt attempt
    ) {
        PatternTargetedLocalBridgeSearch.Status status = attempt.status();
        Optional<Transformation> candidate = Optional.empty();
        boolean replayVerified = false;
        String detailCode = attempt.detailCode();

        if (status
                == PatternTargetedLocalBridgeSearch.Status
                    .DIRECT_MATCH_AVAILABLE) {
            Optional<Transformation> replay = directCandidate(
                runtime.adapter(), source, sourceAssumptions);
            if (replay.isEmpty()) {
                status = PatternTargetedLocalBridgeSearch.Status
                    .TECHNICAL_FAILURE;
                detailCode = "COORDINATOR_DIRECT_REPLAY_FAILED";
            } else {
                PatternMatchAnalyzer.Analysis analysis = analyzePattern(
                    runtime.schema(), source);
                GuardCheck guards = checkRequiredAssumptions(
                    runtime.schema(), analysis, sourceAssumptions);
                if (!guards.authorized()) {
                    status = guards.failureStatus();
                    detailCode = guards.detailCode();
                } else {
                    candidate = replay;
                    replayVerified = true;
                }
            }
        } else if (status
                == PatternTargetedLocalBridgeSearch.Status.PREPARED) {
            PatternTargetedLocalBridgeSearch.Bridge bridge =
                attempt.bridge().orElseThrow();
            PatternTargetedLocalBridgeSearch.Verification verification =
                runtime.search().verify(bridge);
            if (!verification.valid()) {
                status = PatternTargetedLocalBridgeSearch.Status
                    .INVALID_CERTIFICATE;
                detailCode = verification.detailCode();
            } else {
                PatternMatchAnalyzer.Analysis terminal = analyzePattern(
                    runtime.schema(), bridge.terminalExpression());
                GuardCheck guards = checkRequiredAssumptions(
                    runtime.schema(), terminal,
                    bridge.terminalAssumptions());
                if (!guards.authorized()) {
                    status = guards.failureStatus();
                    detailCode = guards.detailCode();
                } else {
                    replayVerified = true;
                    candidate = Optional.of(preparedCandidate(
                        runtime.schema().executor(), bridge));
                }
            }
        }

        String certificateHash = status
                == PatternTargetedLocalBridgeSearch.Status.PREPARED
            ? attempt.bridge().map(
                PatternTargetedLocalBridgeSearch.Bridge::certificateHash)
                .orElseThrow()
            : "";
        return new Outcome(
            runtime.schema().ruleId(),
            runtime.schema().contentHash(),
            status,
            candidate,
            replayVerified,
            attempt.initialAnalysis(),
            attempt.work(),
            attempt.reachedLimits(),
            detailCode,
            certificateHash);
    }

    private Outcome rejectedByGuards(
        PrincipalRuntime runtime,
        PatternMatchAnalyzer.Analysis analysis,
        PatternTargetedLocalBridgeSearch.Work work,
        Set<String> reachedLimits,
        GuardCheck guards
    ) {
        return new Outcome(
            runtime.schema().ruleId(),
            runtime.schema().contentHash(),
            guards.failureStatus(),
            Optional.empty(),
            false,
            PatternTargetedLocalBridgeSearch.AnalysisSnapshot.from(analysis),
            work,
            reachedLimits,
            guards.detailCode(),
            "");
    }

    private Optional<Transformation> directCandidate(
        PatternRewriteRule adapter,
        String source,
        AssumptionSignature sourceAssumptions
    ) {
        List<Transformation> generated =
            new AstRewriteTransformationEngine(
                List.of(adapter),
                Integer.MAX_VALUE,
                1)
                .transform(source);
        return generated.stream()
            .filter(value -> adapter.id().equals(value.rule()))
            .findFirst()
            .map(value -> withCumulativeAssumptions(
                value, sourceAssumptions));
    }

    private PatternMatchAnalyzer.Analysis analyzePattern(
        RewriteApplicabilitySchema schema,
        String expression
    ) {
        return analyzer.analyze(
            schema.pattern(),
            parser.parseTerm(expression),
            schema.recognitionProfile(),
            new ExprMatcher.MatchOptions(
                EquivalentExpressionProvider.identity(),
                bridgeBudget.maxMatchResults(),
                bridgeBudget.maxMatchSteps(),
                bridgeBudget.maxPatternBranches()));
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

    private static Transformation preparedCandidate(
        RewriteRule executor,
        PatternTargetedLocalBridgeSearch.Bridge bridge
    ) {
        return new Transformation(
            executor.id(),
            bridge.resultExpression(),
            executor.kind(),
            executor.mayIncreaseComplexity(),
            executor.estimatedCostDelta(),
            executor.isEquivalencePreservingByConstruction(),
            "coordinated-preparation:" + bridge.certificateHash(),
            bridge.resultAssumptions().normalizedAssumptions(),
            executor.descriptor().packId(),
            executor.descriptor().license(),
            bridge.primitiveRuleIds());
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

    private static List<RewriteApplicabilitySchema> validatePrincipalSchemas(
        List<RewriteApplicabilitySchema> supplied
    ) {
        Objects.requireNonNull(supplied, "principalSchemas");
        if (supplied.isEmpty()) {
            throw new IllegalArgumentException(
                "at least one principal schema is required");
        }
        Map<String, RewriteApplicabilitySchema> byRule =
            new LinkedHashMap<>();
        Set<String> schemaIds = new LinkedHashSet<>();
        for (RewriteApplicabilitySchema schema : supplied) {
            RewriteApplicabilitySchema checked = Objects.requireNonNull(
                schema, "principal schema");
            RewriteRule executor = checked.executor();
            if (!executor.isEquivalencePreservingByConstruction()) {
                throw new IllegalArgumentException(
                    "principal rules must preserve equivalence: "
                        + executor.id());
            }
            if (!executor.descriptor().eligibleForRegistration()) {
                throw new IllegalArgumentException(
                    "principal rule is not review-qualified: "
                        + executor.id());
            }
            if (executor.descriptor().external()
                    && !"low".equalsIgnoreCase(
                        executor.descriptor().riskLevel())) {
                throw new IllegalArgumentException(
                    "safe coordinator v1 accepts only low-risk external rules: "
                        + executor.id());
            }
            if (!schemaIds.add(checked.schemaId())) {
                throw new IllegalArgumentException(
                    "duplicate applicability schema ID: "
                        + checked.schemaId());
            }
            if (byRule.put(checked.ruleId(), checked) != null) {
                throw new IllegalArgumentException(
                    "duplicate principal rule ID: " + checked.ruleId());
            }
        }
        return byRule.values().stream()
            .sorted(Comparator
                .comparing(RewriteApplicabilitySchema::ruleId)
                .thenComparing(RewriteApplicabilitySchema::schemaId))
            .toList();
    }

    private static List<RewriteRule> validatePreparationRules(
        List<? extends RewriteRule> supplied
    ) {
        Objects.requireNonNull(supplied, "preparationRules");
        Map<String, RewriteRule> indexed = new LinkedHashMap<>();
        for (RewriteRule rule : supplied) {
            RewriteRule checked = Objects.requireNonNull(
                rule, "preparation rule");
            if (!checked.isEquivalencePreservingByConstruction()) {
                throw new IllegalArgumentException(
                    "preparation rules must preserve equivalence: "
                        + checked.id());
            }
            if (indexed.put(checked.id(), checked) != null) {
                throw new IllegalArgumentException(
                    "duplicate preparation rule ID: " + checked.id());
            }
        }
        return indexed.values().stream()
            .sorted(Comparator.comparing(RewriteRule::id))
            .toList();
    }

    private static String schemaInventoryFingerprint(
        List<RewriteApplicabilitySchema> schemas
    ) {
        StringBuilder descriptor = new StringBuilder();
        append(descriptor, SCHEMA_INVENTORY_REVISION);
        append(descriptor, Integer.toString(schemas.size()));
        schemas.stream()
            .map(RewriteApplicabilitySchema::contentHash)
            .sorted()
            .forEach(value -> append(descriptor, value));
        return sha256(descriptor.toString());
    }

    private static String requireRevision(String revision) {
        if (revision == null || !revision.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException(
                "repositoryRevision must be a lowercase commit SHA");
        }
        return revision;
    }

    private static RuleDescriptor adapterDescriptor(
        RewriteApplicabilitySchema schema
    ) {
        RuleDescriptor source = schema.executor().descriptor();
        return new RuleDescriptor(
            source.ruleId(),
            source.packId(),
            source.originProject(),
            source.license(),
            source.sourceVersion(),
            source.sourceReference()
                + "; applicabilitySchema=" + schema.schemaId()
                + "; applicabilitySchemaHash=" + schema.contentHash()
                + "; executorClass="
                + schema.executor().getClass().getName(),
            source.derivationType(),
            source.status(),
            source.riskLevel(),
            source.categories(),
            source.searchEffects(),
            source.validationExamples(),
            source.counterExamples());
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
        PatternRewriteRule adapter,
        PatternTargetedLocalBridgeSearch search
    ) {
        private PrincipalRuntime {
            schema = Objects.requireNonNull(schema, "schema");
            adapter = Objects.requireNonNull(adapter, "adapter");
            search = Objects.requireNonNull(search, "search");
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

    /** Private bridge-only adapter; it is never exposed as a registrable rule. */
    private static final class CoordinatorPatternAdapter
            extends PatternRewriteRule {
        private final RewriteRule executor;

        private CoordinatorPatternAdapter(
            RewriteApplicabilitySchema schema
        ) {
            super(
                schema.ruleId(),
                schema.pattern(),
                INTERNAL_RESULT_PLACEHOLDER,
                schema.executor().kind(),
                schema.executor().mayIncreaseComplexity(),
                schema.executor().estimatedCostDelta(),
                schema.executor().isEquivalencePreservingByConstruction(),
                adapterDescriptor(schema),
                schema.recognitionProfile());
            this.executor = schema.executor();
        }

        @Override
        public boolean matches(Expr subtree) {
            return executor.matches(subtree);
        }

        @Override
        public Expr apply(Expr subtree) {
            return executor.apply(subtree);
        }

        @Override
        public List<Assumption> assumptions(Expr subtree) {
            return executor.assumptions(subtree);
        }

        @Override
        public boolean mayEmitAssumptions() {
            return executor.mayEmitAssumptions();
        }
    }

    public record Outcome(
        String ruleId,
        String ruleFingerprint,
        PatternTargetedLocalBridgeSearch.Status status,
        Optional<Transformation> candidate,
        boolean replayVerified,
        PatternTargetedLocalBridgeSearch.AnalysisSnapshot initialAnalysis,
        PatternTargetedLocalBridgeSearch.Work work,
        Set<String> reachedLimits,
        String detailCode,
        String bridgeCertificateHash
    ) {
        public Outcome {
            if (ruleId == null || ruleId.isBlank()
                    || ruleFingerprint == null
                    || !ruleFingerprint.matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                    "rule identity is invalid");
            }
            status = Objects.requireNonNull(status, "status");
            candidate = Objects.requireNonNull(candidate, "candidate");
            initialAnalysis = Objects.requireNonNull(
                initialAnalysis, "initialAnalysis");
            work = Objects.requireNonNull(work, "work");
            reachedLimits = Collections.unmodifiableSet(
                new LinkedHashSet<>(Objects.requireNonNull(
                    reachedLimits, "reachedLimits")));
            if (detailCode == null || detailCode.isBlank()) {
                throw new IllegalArgumentException(
                    "detailCode must not be blank");
            }
            bridgeCertificateHash = bridgeCertificateHash == null
                ? "" : bridgeCertificateHash;
            boolean positive = status
                    == PatternTargetedLocalBridgeSearch.Status
                        .DIRECT_MATCH_AVAILABLE
                || status
                    == PatternTargetedLocalBridgeSearch.Status.PREPARED;
            if (positive != candidate.isPresent()
                    || positive != replayVerified) {
                throw new IllegalArgumentException(
                    "only replay-verified positive outcomes retain candidates");
            }
            if (status
                    == PatternTargetedLocalBridgeSearch.Status.PREPARED) {
                if (!bridgeCertificateHash.matches(
                        "sha256:[0-9a-f]{64}")) {
                    throw new IllegalArgumentException(
                        "prepared outcome requires a bridge certificate");
                }
            } else if (!bridgeCertificateHash.isEmpty()) {
                throw new IllegalArgumentException(
                    "only prepared outcomes retain bridge certificates");
            }
        }

        public boolean direct() {
            return status
                == PatternTargetedLocalBridgeSearch.Status
                    .DIRECT_MATCH_AVAILABLE;
        }

        public boolean prepared() {
            return status
                == PatternTargetedLocalBridgeSearch.Status.PREPARED;
        }

        public boolean positive() {
            return direct() || prepared();
        }
    }

    public record AggregateWork(
        long expandedStates,
        long generatedTransitions,
        long discoveredStates,
        long retainedTransitions,
        long duplicateTransitions,
        long analyzedCandidates,
        int maxFrontierSize
    ) {
        public AggregateWork {
            if (expandedStates < 0 || generatedTransitions < 0
                    || discoveredStates < 0 || retainedTransitions < 0
                    || duplicateTransitions < 0 || analyzedCandidates < 0
                    || maxFrontierSize < 1) {
                throw new IllegalArgumentException(
                    "aggregate work is invalid");
            }
        }

        static AggregateWork from(List<Outcome> outcomes) {
            long expanded = 0;
            long generated = 0;
            long discovered = 0;
            long retained = 0;
            long duplicates = 0;
            long analyzed = 0;
            int frontier = 1;
            for (Outcome outcome : outcomes) {
                PatternTargetedLocalBridgeSearch.Work work = outcome.work();
                expanded += work.expandedStates();
                generated += work.generatedTransitions();
                discovered += work.discoveredStates();
                retained += work.retainedTransitions();
                duplicates += work.duplicateTransitions();
                analyzed += work.analyzedCandidates();
                frontier = Math.max(frontier, work.maxFrontierSize());
            }
            return new AggregateWork(
                expanded, generated, discovered, retained,
                duplicates, analyzed, frontier);
        }
    }

    public record Evaluation(
        String coordinatorId,
        String repositoryRevision,
        String principalInventoryFingerprint,
        String preparationInventoryFingerprint,
        PatternTargetedLocalBridgeSearch.Budget bridgeBudget,
        String sourceExpression,
        AssumptionSignature sourceAssumptions,
        List<Outcome> outcomes,
        AggregateWork aggregateWork
    ) {
        public Evaluation {
            if (!COORDINATOR_ID.equals(coordinatorId)
                    || repositoryRevision == null
                    || !repositoryRevision.matches("[0-9a-f]{40}")
                    || principalInventoryFingerprint == null
                    || !principalInventoryFingerprint.matches(
                        "sha256:[0-9a-f]{64}")
                    || preparationInventoryFingerprint == null
                    || !preparationInventoryFingerprint.matches(
                        "sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                    "evaluation identity is invalid");
            }
            bridgeBudget = Objects.requireNonNull(
                bridgeBudget, "bridgeBudget");
            if (sourceExpression == null || sourceExpression.isBlank()) {
                throw new IllegalArgumentException(
                    "sourceExpression must not be blank");
            }
            sourceAssumptions = normalized(sourceAssumptions);
            outcomes = List.copyOf(Objects.requireNonNull(
                outcomes, "outcomes"));
            if (outcomes.isEmpty()) {
                throw new IllegalArgumentException(
                    "evaluation must retain principal outcomes");
            }
            Set<String> ids = new LinkedHashSet<>();
            for (Outcome outcome : outcomes) {
                if (!ids.add(outcome.ruleId())) {
                    throw new IllegalArgumentException(
                        "duplicate outcome rule ID: " + outcome.ruleId());
                }
            }
            aggregateWork = Objects.requireNonNull(
                aggregateWork, "aggregateWork");
        }

        public Optional<Outcome> outcome(String ruleId) {
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
            return outcomes.stream().filter(Outcome::direct).count();
        }

        public long preparedApplications() {
            return outcomes.stream().filter(Outcome::prepared).count();
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
