package de.regelsuche.runtime;

import de.regelsuche.app.transform.SymPyTransformationEngine;
import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.json.JsonReader;
import de.regelsuche.knowledge.RuleInventoryFingerprint;
import de.regelsuche.math.algorithms.linalg.MatrixPreparation;
import de.regelsuche.math.algorithms.linalg.MatrixPreparationJson;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.plugin.PluginAwareAstRewriteTransformationEngine;
import de.regelsuche.plugin.PluginRuntime;
import de.regelsuche.plugin.PluginRuntimeConfig;
import de.regelsuche.search.reachability.ContextualOccurrenceRulePreparationCoordinator;
import de.regelsuche.search.reachability.OccurrenceAwareSharedRulePreparationCoordinator;
import de.regelsuche.search.reachability.RulePreparationCoordinator;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.RecordedExecution;
import de.regelsuche.transform.RewriteApplicabilitySchema;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import static de.regelsuche.runtime.RuntimeJson.*;

/**
 * One opt-in product policy, shared by CLI and Workbench. Imported observations
 * never supply executors. Replay calls the current trusted inventory again.
 */
public final class SafeRuntimeAdapter implements AutoCloseable {
    public static final String ADAPTER_ID = "regelsuche.safe-product-runtime/v1";
    public static final String ARTIFACT_SCHEMA = "regelsuche.safe-runtime-artifact/v1";
    public static final String WORK_ID = "regelsuche.safe-product-logical-work/v1";

    /** Original executor plus trusted authority identity; suitable for authorized programs too. */
    public record DirectSource(String id, String authorityHash, TransformationEngine engine) {
        public DirectSource {
            if (id == null || id.isBlank() || authorityHash == null || authorityHash.isBlank()) {
                throw new IllegalArgumentException("direct source needs an authority identity");
            }
            Objects.requireNonNull(engine, "engine");
        }
    }

    private final List<RewriteRule> rules;
    private final List<DirectSource> sources;
    private final String implementationHash;
    private final Function<List<RewriteRule>, TransformationEngine> directRules;
    private final Set<String> contextualRuleIds;
    private final Runnable release;
    private final LearnedRuntimeAuthority learnedAuthority;

    /** Trusted in-process seam. Public request JSON has no corresponding executor constructor. */
    public SafeRuntimeAdapter(List<? extends RewriteRule> rules, List<DirectSource> sources, String implementationHash) {
        this(rules, sources, implementationHash, null);
    }

    public SafeRuntimeAdapter(List<? extends RewriteRule> rules, List<DirectSource> sources, String implementationHash,
            LearnedRuntimeAuthority learnedAuthority) {
        this(rules, sources, implementationHash, selected -> new AstRewriteTransformationEngine(selected), Set.of(), () -> { }, learnedAuthority);
    }

    private SafeRuntimeAdapter(List<? extends RewriteRule> rules, List<DirectSource> sources, String implementationHash,
            Function<List<RewriteRule>, TransformationEngine> directRules, Set<String> contextualRuleIds,
            Runnable release, LearnedRuntimeAuthority learnedAuthority) {
        this.rules = List.copyOf(rules);
        this.sources = List.copyOf(sources);
        RewriteApplicabilitySchema.coverage(this.rules); // includes duplicate-ID rejection
        var identifiers = new java.util.HashSet<>(this.rules.stream().map(RewriteRule::id).toList());
        for (var source : sources) if (!identifiers.add(source.id())) throw new IllegalArgumentException("duplicate runtime source: " + source.id());
        if (implementationHash == null || !implementationHash.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("runtime implementation fingerprint is required");
        }
        this.implementationHash = implementationHash;
        this.directRules = directRules;
        this.contextualRuleIds = Set.copyOf(contextualRuleIds);
        this.release = release;
        this.learnedAuthority = learnedAuthority;
    }

    public static SafeRuntimeAdapter open(PluginRuntimeConfig config) {
        var runtime = new PluginRuntime(config);
        try {
            var byId = new java.util.LinkedHashMap<String, RewriteRule>();
            for (var rule : runtime.createTransformationEngine().rules()) {
                if (byId.put(rule.id(), rule) != null) throw new IllegalArgumentException("duplicate plugin/runtime rule: " + rule.id());
            }
            var domains = new de.regelsuche.rules.RuleDomainRegistry();
            for (var domain : domains.all()) for (var rule : domain.rules()) {
                var prior = byId.putIfAbsent(rule.id(), rule);
                if (prior != null && !RuleInventoryFingerprint.ruleContentHash(prior).equals(RuleInventoryFingerprint.ruleContentHash(rule))) {
                    throw new IllegalArgumentException("conflicting first-party/plugin rule: " + rule.id());
                }
            }
            var rules = List.copyOf(byId.values());
            // Plugin contributions execute with visitor state. An applicability schema
            // alone does not authorize replacing that engine with a context-free one.
            var contextualRuleIds = new java.util.HashSet<String>();
            runtime.ruleRegistry().enabledRules().forEach(rule -> contextualRuleIds.add(rule.id()));
            runtime.transformationRegistry().enabledTransformations().forEach(rule -> contextualRuleIds.add(rule.id()));
            runtime.macroTransformations().forEach(rule -> contextualRuleIds.add(rule.id()));
            var code = new ArrayList<Class<?>>();
            code.addAll(List.of(SafeRuntimeAdapter.class, RewriteRule.class,
                OccurrenceAwareSharedRulePreparationCoordinator.class, MatrixPreparation.class,
                de.regelsuche.egraph.EGraph.class, de.regelsuche.evolution.LearnedPatternRuleAuthorizationService.class,
                de.regelsuche.plugin.PatternTransformation.class));
            rules.forEach(rule -> code.add(rule.getClass()));
            String digest = RuntimeCodeIdentity.fingerprint(code.stream().map(RuntimeCodeIdentity::source).toList());
            return new SafeRuntimeAdapter(rules, List.of(new DirectSource("sympy", digest,
                new SymPyTransformationEngine()::transformSymPyStrict)), digest,
                selected -> new PluginAwareAstRewriteTransformationEngine(selected, runtime.astVisitorRegistry())::transformForRuntime,
                contextualRuleIds, runtime::close, LearnedRuntimeAuthority.fromConfiguredProperty());
        } catch (RuntimeException | LinkageError exception) {
            runtime.close();
            throw exception;
        }
    }

    public String analyze(Map<String, ?> request) { return analyze(SafeRuntimeRequest.read(request)); }

    public String analyze(SafeRuntimeRequest request) {
        Objects.requireNonNull(request, "request");
        var learned = learnedAuthority == null ? null : learnedAuthority.load();
        var rules = new ArrayList<>(this.rules);
        var sources = new ArrayList<>(this.sources);
        if (learned != null) { rules.addAll(learned.rules()); sources.addAll(learned.sources()); }
        RewriteApplicabilitySchema.coverage(rules);
        var allIds = new java.util.HashSet<>(rules.stream().map(RewriteRule::id).toList());
        for (var source : sources) if (!allIds.add(source.id())) throw new IllegalArgumentException("duplicate runtime source: " + source.id());
        List<String> known = new ArrayList<>(rules.stream().map(RewriteRule::id).toList());
        known.addAll(sources.stream().map(DirectSource::id).toList());
        if (!known.containsAll(request.ruleIds())) throw new IllegalArgumentException("unknown rule/source in runtime selection");
        List<RewriteRule> selected = rules.stream().filter(rule -> selected(request, rule.id())).toList();
        var coverage = RewriteApplicabilitySchema.coverage(selected);
        var admitted = coverage.stream().filter(this::runtimePreparationEligible)
            .map(RewriteApplicabilitySchema.CoverageEntry::schema).toList();
        if (!admitted.stream().map(RewriteApplicabilitySchema::ruleId).toList().containsAll(request.preparationRuleIds())) {
            throw new IllegalArgumentException("preparation rules must be selected and admitted by explicit schema coverage and runtime executor eligibility");
        }
        var support = admitted.stream().filter(schema -> request.preparationRuleIds().contains(schema.ruleId())).toList();
        var principals = admitted.stream().filter(schema -> !request.preparationRuleIds().contains(schema.ruleId())).toList();
        var preparationRules = support.stream().map(RewriteApplicabilitySchema::executor).toList();
        var visible = new ArrayList<Map<String, Object>>();
        for (var entry : RewriteApplicabilitySchema.coverage(rules)) {
            visible.add(fields("id", entry.rule().id(), "contentHash", RuleInventoryFingerprint.ruleContentHash(entry.rule()),
                "selected", selected(request, entry.rule().id()), "coverage", entry.status().name(),
                "preparationSupport", request.preparationRuleIds().contains(entry.rule().id()),
                "schemaHash", entry.schema() == null ? "" : entry.schema().contentHash(),
                "exclusionReason", entry.exclusionReason(), "runtimePreparationEligible", runtimePreparationEligible(entry),
                "runtimeExclusionReason", runtimeExclusionReason(entry)));
        }
        for (var source : sources) visible.add(fields("id", source.id(), "contentHash", source.authorityHash(),
            "selected", enabled(request, source), "coverage", "DIRECT_EXECUTOR_ONLY", "schemaHash", "",
            "exclusionReason", "direct source has no admitted preparation schema", "runtimePreparationEligible", false,
            "runtimeExclusionReason", "DIRECT_EXECUTOR_HAS_NO_SCHEMA"));
        Map<String, Object> inventory = fields("visible", visible, "visibleFingerprint", hash(canonical(fields("entries", visible))),
            "selectedRuleFingerprint", RuleInventoryFingerprint.contentHash(selected));
        var work = new Work(request.maxWorkUnits());
        // Revalidation has already executed; never erase it when the remaining setup is refused.
        if (learned != null) work.setup = learned.setupWorkUnits();
        var outcomes = new ArrayList<Map<String, Object>>();
        Map<String, Object> authority = fields("coordinatorId", request.profile() == SafeRuntimeRequest.Profile.SAFE_PREPARATION_V4
                ? ContextualOccurrenceRulePreparationCoordinator.COORDINATOR_ID : OccurrenceAwareSharedRulePreparationCoordinator.COORDINATOR_ID,
            "occurrenceBindingRevision", OccurrenceAwareSharedRulePreparationCoordinator.OCCURRENCE_BINDING_REVISION,
            "occurrenceWorkRevision", OccurrenceAwareSharedRulePreparationCoordinator.OCCURRENCE_WORK_REVISION);
        if (learned != null) authority.put("learnedAuthorization", learned.identity());
        long setup = 1L + rules.size() + sources.size() + principals.size() * (1L + preparationRules.size()) + support.size();
        if (!work.reserveSetup(setup)) {
            outcomes.add(outcome("runtime", "BUDGET_INCONCLUSIVE", "SETUP_BUDGET_EXHAUSTED"));
        } else if (request.representation() != null) {
            analyzeRepresentation(request, work, outcomes, authority);
        } else {
            Expr parsedSource = new ExpressionParser().parseTerm(request.source());
            int nodes = sourceNodeCount(parsedSource);
            String source = ExpressionFormatter.format(parsedSource);
            if (nodes > request.preparationBudget().maxExpressionNodes()) {
                outcomes.add(outcome("runtime", "BUDGET_INCONCLUSIVE", "SOURCE_NODE_BUDGET_EXHAUSTED"));
            } else {
                if (!principals.isEmpty()) {
                    if (request.profile() == SafeRuntimeRequest.Profile.SAFE_PREPARATION_V4) {
                        analyzeSuccessorPrincipals(request, source, principals, preparationRules, work, outcomes, authority);
                    } else {
                        var coordinator = new OccurrenceAwareSharedRulePreparationCoordinator(principals, preparationRules,
                            implementationHash.substring(7, 47), request.preparationBudget());
                        authority.put("principalInventoryFingerprint", coordinator.principalInventoryFingerprint());
                        authority.put("preparationInventoryFingerprint", coordinator.preparationInventoryFingerprint());
                        authority.put("exactRegistryFingerprint", coordinator.exactRegistryFingerprint());
                        analyzePrincipals(request, source, nodes, coordinator, work, outcomes, authority, false);
                    }
                }
                if (!support.isEmpty()) {
                    var supportAuthority = fields("role", "PREPARATION_SUPPORT_DIRECT_EXECUTION");
                    analyzePrincipals(request, source, nodes, new OccurrenceAwareSharedRulePreparationCoordinator(
                        support, List.of(), implementationHash.substring(7, 47), request.preparationBudget()),
                        work, outcomes, supportAuthority, true);
                    authority.put("supportDirectEvaluation", supportAuthority);
                }
                for (var entry : coverage) if (!runtimePreparationEligible(entry)) {
                    runDirect(request, source, nodes, entry.rule().id(), directRules.apply(List.of(entry.rule())),
                        runtimeExclusionReason(entry), work, outcomes);
                }
                for (var direct : sources) if (enabled(request, direct)) {
                    runDirect(request, source, nodes, direct.id(), direct.engine(), "DIRECT_EXECUTOR_HAS_NO_SCHEMA", work, outcomes);
                }
            }
        }
        var evidence = fields("adapterId", ADAPTER_ID, "request", request.observation(), "inventory", inventory,
            "implementation", fields("revision", RuntimeCodeIdentity.REVISION, "contentHash", implementationHash,
                "authorityRevisionKind", "RUNTIME_CLASS_SHA256_PREFIX_160"),
            "authority", authority, "work", work.observation(), "outcomes", outcomes,
            "status", status(outcomes), "retainedAssumptions", request.assumptions().normalizedAssumptions());
        return canonical(fields("schema", ARTIFACT_SCHEMA, "contentHash", hash(canonical(evidence)), "evidence", evidence)) + "\n";
    }

    public String replay(Map<String, ?> artifact) {
        only(artifact, "schema", "contentHash", "evidence");
        if (!ARTIFACT_SCHEMA.equals(artifact.get("schema"))) throw new IllegalArgumentException("unsupported safe runtime artifact schema");
        var evidence = object(artifact.get("evidence"));
        String actual = analyze(SafeRuntimeRequest.read(object(evidence.get("request"))));
        if (!new JsonReader(actual).readObject().equals(artifact)) {
            throw new IllegalArgumentException("runtime artifact differs from fresh trusted execution and replay");
        }
        return actual;
    }

    private void analyzePrincipals(SafeRuntimeRequest request, String source, int nodes,
            OccurrenceAwareSharedRulePreparationCoordinator coordinator, Work work,
            List<Map<String, Object>> outcomes, Map<String, Object> authority, boolean forceDirect) {
        if (!work.canSpend(1)) { outcomes.add(outcome("runtime", "BUDGET_INCONCLUSIVE", "ANALYSIS_BUDGET_EXHAUSTED")); return; }
        boolean direct = forceDirect || request.profile() == SafeRuntimeRequest.Profile.DIRECT_V1;
        var evaluation = direct ? coordinator.analyzeDirect(source, request.assumptions()) : coordinator.analyze(source, request.assumptions());
        long analyzed = analysisUnits(evaluation);
        work.analysis = Math.addExact(work.analysis, analyzed);
        retainV3Work(evaluation, authority);
        // Independent primitive replay traverses each direct rule over the full source.
        // Reserve all possible match/apply/assumption calls, including no-op calls.
        long replayReservation = evaluation.outcomes().stream().filter(RulePreparationCoordinator.Outcome::direct).count() * (1L + 3L * nodes);
        long verification = Math.addExact(analyzed, replayReservation);
        boolean verified = false;
        String verificationDetail = "VERIFICATION_BUDGET_EXHAUSTED";
        if (work.reserveVerification(verification)) {
            var result = direct ? coordinator.verifyDirect(evaluation) : coordinator.verify(evaluation);
            verified = result.valid();
            verificationDetail = result.detailCode();
        }
        authority.put("independentReplayReservationUnits", replayReservation);
        authority.put("verified", verified);
        authority.put("verificationDetail", verificationDetail);
        retainPrincipalOutcomes(request, source, evaluation.outcomes(), verified, verificationDetail, work, outcomes);
    }

    private void analyzeSuccessorPrincipals(SafeRuntimeRequest request, String source,
            List<RewriteApplicabilitySchema> principals, List<RewriteRule> preparationRules, Work work,
            List<Map<String, Object>> outcomes, Map<String, Object> authority) {
        if (!work.canSpend(1)) { outcomes.add(outcome("runtime", "BUDGET_INCONCLUSIVE", "ANALYSIS_BUDGET_EXHAUSTED")); return; }
        var contextualBudget = new ContextualOccurrenceRulePreparationCoordinator.ContextualBudget(
            Math.max(0, request.maxWorkUnits() - work.total()), request.preparationBudget().maxVisitedStates());
        var coordinator = new ContextualOccurrenceRulePreparationCoordinator(principals, preparationRules,
            implementationHash.substring(7, 47), request.preparationBudget(), contextualBudget);
        var evaluation = coordinator.analyze(source, request.assumptions());
        var base = evaluation.baseV3Evaluation();
        long analyzed = evaluation.work().analysisUnits();
        long verification = evaluation.work().verificationReservationUnits();
        work.analysis = Math.addExact(work.analysis, analyzed);
        authority.put("successorIdentityRevision", ContextualOccurrenceRulePreparationCoordinator.SUCCESSOR_IDENTITY_REVISION);
        authority.put("contextualBudget", contextualBudget);
        authority.put("principalInventoryFingerprint", base.principalInventoryFingerprint());
        authority.put("preparationInventoryFingerprint", base.preparationInventoryFingerprint());
        authority.put("exactRegistryFingerprint", base.exactRegistryFingerprint());
        authority.put("baseV3Evaluation", base);
        retainV3Work(base, authority);
        authority.put("contextualSuccessors", evaluation.successors());
        authority.put("contextualWork", fields("receipt", evaluation.work(), "analysisUnits", analyzed,
            "verificationReservationUnits", verification));
        // V4 owns every reconstruction and independent replay reservation.
        authority.put("independentReplayReservationUnits", verification - analyzed);
        boolean verified = false;
        String verificationDetail = "VERIFICATION_BUDGET_EXHAUSTED";
        if (work.reserveVerification(verification)) {
            var result = coordinator.verify(evaluation);
            verified = result.valid();
            verificationDetail = result.detailCode();
        }
        authority.put("verified", verified);
        authority.put("verificationDetail", verificationDetail);
        retainPrincipalOutcomes(request, source, evaluation.outcomes(), verified, verificationDetail, work, outcomes);
    }

    private static void retainV3Work(OccurrenceAwareSharedRulePreparationCoordinator.Evaluation evaluation, Map<String, Object> authority) {
        authority.put("occurrenceWork", evaluation.occurrenceWork());
        authority.put("aggregateWork", evaluation.aggregateWork());
        authority.put("directOccurrences", evaluation.directOccurrenceEvidence());
        authority.put("delegatedV2PrincipalIds", evaluation.delegatedV2PrincipalIds());
        authority.put("delegatedSharedExecutionWork", evaluation.delegatedSharedExecutionWork());
    }

    private static void retainPrincipalOutcomes(SafeRuntimeRequest request, String source,
            List<RulePreparationCoordinator.Outcome> results, boolean verified, String verificationDetail,
            Work work, List<Map<String, Object>> outcomes) {
        if (!verified) {
            outcomes.add(outcome("runtime", work.exhausted() ? "BUDGET_INCONCLUSIVE" : "TECHNICAL_FAILURE", verificationDetail));
        }
        for (var result : results) {
            String status = result.detailCode().equals("DIRECT_NO_MATCH") ? "NO_MATCH" : result.status().name();
            var retained = outcome(result.ruleId(), status, result.detailCode());
            retained.put("work", result.work());
            retained.put("reachedLimits", result.reachedLimits());
            retained.put("bridgeCertificateHash", result.bridgeCertificateHash());
            if (result.positive()) {
                if (!withinPathBudget(request, result.candidate().orElseThrow())) {
                    retained.put("status", "BUDGET_INCONCLUSIVE"); retained.put("detailCode", "CANDIDATE_PATH_BUDGET_EXHAUSTED");
                } else if (verified) retained.put("candidate", candidate(source, result.candidate().orElseThrow(), request.assumptions()));
                else { retained.put("status", work.exhausted() ? "BUDGET_INCONCLUSIVE" : "TECHNICAL_FAILURE"); retained.put("detailCode", verificationDetail); }
            }
            outcomes.add(retained);
        }
    }

    private static long analysisUnits(OccurrenceAwareSharedRulePreparationCoordinator.Evaluation evaluation) {
        var aggregate = evaluation.aggregateWork();
        long units = evaluation.occurrenceWork().chargedUnits() + aggregate.expandedStates() + aggregate.generatedTransitions()
            + aggregate.discoveredStates() + aggregate.retainedTransitions() + aggregate.duplicateTransitions() + aggregate.analyzedCandidates();
        units += evaluation.candidates().stream().mapToLong(step -> step.executionWork().canonicalWorkUnits()).sum();
        if (evaluation.delegatedSharedExecutionWork().isPresent()) {
            var shared = evaluation.delegatedSharedExecutionWork().orElseThrow();
            units += shared.sourceAnalysisWork().uniqueParsedExpressions() + shared.sourceAnalysisWork().uniqueAnalyses() + shared.guardRequests();
        }
        return units;
    }

    private static void runDirect(SafeRuntimeRequest request, String source, int nodes, String id,
            TransformationEngine engine, String exclusion, Work work, List<Map<String, Object>> outcomes) {
        long reservation = 1L + 3L * nodes;
        if (!work.reserveAnalysis(reservation)) { outcomes.add(outcome(id, "BUDGET_INCONCLUSIVE", "DIRECT_EXECUTION_BUDGET_EXHAUSTED")); return; }
        try {
            var batch = directExecution(engine, source);
            var steps = batch.steps();
            long execution = batch.units();
            work.analysis = Math.addExact(work.analysis, execution);
            if (steps.stream().anyMatch(step -> !withinPathBudget(request, step))) {
                var rejected = outcome(id, "BUDGET_INCONCLUSIVE", "CANDIDATE_PATH_BUDGET_EXHAUSTED");
                rejected.put("directWork", batch.observation()); outcomes.add(rejected); return;
            }
            if (!work.reserveVerification(reservation + execution)) {
                outcomes.add(outcome(id, "BUDGET_INCONCLUSIVE", "DIRECT_REPLAY_BUDGET_EXHAUSTED")); return;
            }
            var replayed = directExecution(engine, source);
            work.verification += Math.max(0, replayed.units() - execution);
            if (!batch.equals(replayed)) { outcomes.add(outcome(id, "TECHNICAL_FAILURE", "DIRECT_REPLAY_MISMATCH")); return; }
            if (steps.isEmpty()) {
                boolean unsupported = request.profile() != SafeRuntimeRequest.Profile.DIRECT_V1;
                var result = outcome(id, unsupported ? "UNSUPPORTED" : "NO_MATCH", unsupported ? exclusion : "DIRECT_NO_MATCH");
                result.put("directWork", batch.observation()); outcomes.add(result);
            }
            for (var step : steps) {
                var result = outcome(id, "DIRECT_MATCH_AVAILABLE", "ORIGINAL_DIRECT_EXECUTOR_REPLAYED");
                result.put("directWork", batch.observation());
                result.put("candidate", candidate(source, step, request.assumptions()));
                outcomes.add(result);
            }
        } catch (RuntimeException | LinkageError exception) {
            outcomes.add(outcome(id, "TECHNICAL_FAILURE", "DIRECT_EXECUTOR_FAILURE:" + exception.getClass().getName()));
        }
    }

    private record DirectExecution(List<Transformation> steps, Map<String, Object> observation, long units) { }

    private static DirectExecution directExecution(TransformationEngine engine, String source) {
        if (engine instanceof de.regelsuche.transform.MeasuredTransformationEngine measured) {
            var batch = measured.transformMeasured(source);
            long candidateUnits = batch.transformations().stream().mapToLong(step -> step.executionWork().canonicalWorkUnits()).sum();
            return new DirectExecution(batch.transformations(), fields("kind", "MEASURED_ORIGINAL_ENGINE", "metrics", batch.workMetrics()),
                batch.workMetrics().totalWorkUnits() + Math.max(candidateUnits, batch.workMetrics().candidateWork().canonicalWorkUnits()));
        }
        var steps = List.copyOf(engine instanceof de.regelsuche.transform.WorkAwareTransformationEngine aware
            ? aware.verifiedTransformations(source) : engine.transform(source));
        long units = steps.stream().mapToLong(step -> step.executionWork().canonicalWorkUnits()).sum();
        return new DirectExecution(steps, fields("kind", engine instanceof de.regelsuche.transform.WorkAwareTransformationEngine
            ? "WORK_AWARE_ORIGINAL_ENGINE" : "ORIGINAL_ENGINE", "candidateExecutionUnits", units), units);
    }

    private static boolean withinPathBudget(SafeRuntimeRequest request, Transformation step) {
        return step.executionWork().primitiveRewrites() <= request.maxPrimitiveRewrites()
            && step.executionWork().exactTheoryWorkUnits() <= request.maxTheoryWorkUnits();
    }

    private static void analyzeRepresentation(SafeRuntimeRequest request, Work work, List<Map<String, Object>> outcomes,
            Map<String, Object> authority) {
        // Construction and concrete audit each receive their own half of the remaining budget.
        int allowance = (int) Math.max(0, (request.maxWorkUnits() - work.total()) / 2);
        var source = request.representation();
        var bounded = new MatrixPreparation.Request(source.equations(), source.unknowns(), source.profile(), allowance,
            source.catalog(), source.operatorExpressions(), source.eigenvalueParameter(), source.nonZeroVector(), source.matrixExpression(), source.rightHandSide());
        try {
            var analysis = new MatrixPreparation().analyze(bounded);
            work.analysis += analysis.work().consumedWorkUnits();
            String typed = MatrixPreparationJson.toJson(analysis);
            work.verification += analysis.work().consumedWorkUnits();
            String replay = MatrixPreparationJson.replay(new JsonReader(typed).readObject());
            if (!typed.equals(replay)) throw new IllegalArgumentException("typed concrete replay mismatch");
            authority.put("typedRelationAuthority", MatrixPreparation.SCHEMA);
            authority.put("typedArtifact", typed);
            outcomes.add(outcome("representation", analysis.status().name(), analysis.detailCode()));
        } catch (RuntimeException | LinkageError exception) {
            outcomes.add(outcome("representation", "TECHNICAL_FAILURE", "TYPED_EXECUTOR_FAILURE:" + exception.getClass().getName()));
        }
    }

    private static Map<String, Object> candidate(String source, Transformation step, AssumptionSignature assumptions) {
        var execution = RecordedExecution.capture(source, List.of(step));
        return fields("expression", step.transformedExpression(), "rule", step.rule(),
            "retainedAssumptions", AssumptionSignature.merge(assumptions, AssumptionSignature.ofExpressions(step.assumptions())).normalizedAssumptions(),
            "primitiveRuleIds", step.primitiveRuleIds(), "executionWork", step.executionWork(), "execution", execution.toCanonicalJson());
    }

    private static Map<String, Object> outcome(String id, String status, String detail) {
        return fields("id", id, "status", status, "detailCode", detail);
    }
    private boolean runtimePreparationEligible(RewriteApplicabilitySchema.CoverageEntry entry) {
        return entry.safeProfileEligible() && !contextualRuleIds.contains(entry.rule().id());
    }
    private String runtimeExclusionReason(RewriteApplicabilitySchema.CoverageEntry entry) {
        return contextualRuleIds.contains(entry.rule().id())
            ? "PLUGIN_CONTEXT_REQUIRES_ORIGINAL_EXECUTOR" : entry.exclusionReason();
    }
    private static int sourceNodeCount(Expr source) {
        var pending = new java.util.ArrayDeque<Expr>();
        pending.push(source);
        int nodes = 0;
        while (!pending.isEmpty()) {
            Expr current = pending.pop();
            nodes++;
            if (current instanceof BinaryExpr binary) {
                pending.push(binary.left()); pending.push(binary.right());
            } else if (current instanceof FunctionExpr function) {
                function.arguments().forEach(pending::push);
            }
        }
        return nodes;
    }
    private static boolean selected(SafeRuntimeRequest request, String id) { return request.ruleIds().isEmpty() || request.ruleIds().contains(id); }
    private static boolean enabled(SafeRuntimeRequest request, DirectSource source) {
        return selected(request, source.id()) && (!source.id().equals("sympy") || request.includeSymPy());
    }
    private static String status(List<Map<String, Object>> outcomes) {
        if (outcomes.stream().anyMatch(value -> value.get("status").equals("TECHNICAL_FAILURE"))) return "TECHNICAL_FAILURE";
        if (outcomes.stream().anyMatch(value -> value.get("status").equals("BUDGET_INCONCLUSIVE"))) return "BUDGET_INCONCLUSIVE";
        if (outcomes.stream().anyMatch(value -> value.containsKey("candidate") || Set.of("REPRESENTED", "DIRECT_REPRESENTATION_AVAILABLE").contains(value.get("status")))) return "SUCCESS";
        if (outcomes.stream().anyMatch(value -> Set.of("UNSUPPORTED", "DOMAIN_UNSUPPORTED", "ASSUMPTION_REQUIRED", "NONLINEAR").contains(value.get("status")))) return "UNSUPPORTED";
        return "NO_MATCH";
    }
    @Override public void close() { release.run(); }

    private static final class Work {
        final long maximum;
        long setup;
        long analysis;
        long verification;
        long refusedReservation;
        Work(long maximum) { this.maximum = maximum; }
        long total() { return setup + analysis + verification; }
        boolean canSpend(long units) { return units <= maximum - total(); }
        boolean reserveSetup(long units) { if (!reserve(units)) return false; setup += units; return true; }
        boolean reserveAnalysis(long units) { if (!reserve(units)) return false; analysis += units; return true; }
        boolean reserveVerification(long units) { if (!reserve(units)) return false; verification += units; return true; }
        boolean reserve(long units) { if (canSpend(units)) return true; refusedReservation = Math.max(refusedReservation, units); return false; }
        boolean exhausted() { return total() > maximum || refusedReservation > 0; }
        Map<String, Object> observation() { return fields("revision", WORK_ID, "configuredWorkUnits", maximum,
            "setupUnits", setup, "analysisUnits", analysis, "verificationUnits", verification, "chargedUnits", total(),
            "refusedReservationUnits", refusedReservation,
            "convention", "LOGICAL_BATCH_ACCOUNTING; bounded analysis can cross the retention budget; all spent work remains charged; independent direct replay uses a conservative node-call reservation"); }
    }
}
