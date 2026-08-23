package de.regelsuche.docs;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.docs.HiddenRulePilotRunner.CandidateSnapshot;
import de.regelsuche.docs.HiddenRulePilotRunner.NegativeHoldout;
import de.regelsuche.docs.HiddenRulePilotRunner.PositiveHoldout;
import de.regelsuche.docs.HiddenRulePilotRunner.RuntimeResult;
import de.regelsuche.docs.HiddenRulePilotRunner.RuntimeTask;
import de.regelsuche.evolution.ExactPolynomialPatternIdentityVerifier;
import de.regelsuche.evolution.ExactPolynomialPatternVerificationService;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.mining.DynamicOperatorCompiler;
import de.regelsuche.mining.DynamicPatternOperator;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalMetrics;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalSearchResult;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchProblem.SearchTarget;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.transform.HypothesisOperator;
import de.regelsuche.transform.HypothesisTransformationEngine;
import de.regelsuche.transform.OccurrenceAwareAstRewriteTransformationEngine;
import de.regelsuche.transform.TransformationEngine;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Executes a proof-gated, generation-separated rule-mining development campaign.
 *
 * <p>Every generation runs against one frozen input inventory. Candidates become
 * eligible only after search, candidate validation, counterexample search,
 * positive and negative holdouts, an empty-assumption check, exact polynomial
 * identity verification and executable compilation. Eligible operators are
 * activated only after every task of that generation has finished. This blocks
 * same-generation self-confirmation and makes every inventory revision
 * content-addressed and reversible.</p>
 *
 * <p>The activated rules remain an experimental shadow inventory. This campaign
 * does not write to the default production inventory and does not upgrade the
 * public {@code PROMOTION} capability status.</p>
 */
public final class GenerationalRuleMiningCampaign {
    public static final String SCHEMA =
        "regelsuche.generational-rule-mining-campaign/v1";
    public static final String CAMPAIGN_ID =
        "historical-rediscovery-generation-0";

    private static final SearchHeuristic COMPOSITION_HEURISTIC =
        new SearchHeuristic(12, 1_200, 1, 32, 1_200, 1_200);

    private final HiddenRulePilotRunner runner = new HiddenRulePilotRunner();
    private final ExactPolynomialPatternVerificationService exactVerifier =
        new ExactPolynomialPatternVerificationService();
    private final DynamicOperatorCompiler compiler = new DynamicOperatorCompiler();
    private final ExpressionParser parser = new ExpressionParser();
    private final ExpressionCanonicalizer canonicalizer =
        new ExpressionCanonicalizer();
    private final ExpressionScorer scorer = new ExpressionScorer();

    public CampaignReport run(String repositoryRevision) {
        requireText(repositoryRevision, "repositoryRevision");
        InventoryState initial = InventoryState.empty(repositoryRevision);

        GenerationExecution seed = executeGeneration(
            0,
            "seed-path-mining",
            "CASE_LOCAL_FROZEN_PRIMITIVES",
            initial,
            HiddenRulePilotRuntimeCatalog.tasks());

        GenerationExecution firstComposition = executeGeneration(
            1,
            "first-learned-composition",
            "PREVIOUS_EXACT_SHADOW_INVENTORY",
            seed.outputInventory(),
            List.of(compositionTask(
                "generation-001-composition",
                seed.outputInventory(),
                1)));

        GenerationExecution secondComposition = executeGeneration(
            2,
            "second-learned-composition",
            "PREVIOUS_EXACT_SHADOW_INVENTORY",
            firstComposition.outputInventory(),
            List.of(compositionTask(
                "generation-002-composition",
                firstComposition.outputInventory(),
                2)));

        InventoryState finalInventory = secondComposition.outputInventory();
        ReachabilityComparison reachability = compareReachability(
            initial,
            finalInventory,
            repeatWrap("x", 3),
            "x");

        return new CampaignReport(
            SCHEMA,
            CAMPAIGN_ID,
            repositoryRevision,
            initial.inventoryHash(),
            finalInventory.inventoryHash(),
            List.of(
                seed.report(),
                firstComposition.report(),
                secondComposition.report()),
            finalInventory.rules(),
            reachability,
            "Development evidence only: exact shadow activation is not production promotion.");
    }

    public Path write(Path output, CampaignReport report) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(report, "report");
        try {
            Path normalized = output.toAbsolutePath().normalize();
            Path parent = normalized.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(normalized, report.toJson(), StandardCharsets.UTF_8);
            return output;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private GenerationExecution executeGeneration(
        int generation,
        String name,
        String executionSurface,
        InventoryState inputInventory,
        List<RuntimeTask> tasks
    ) {
        Objects.requireNonNull(inputInventory, "inputInventory");
        List<EvaluatedTask> evaluated = tasks.stream()
            .sorted(Comparator.comparing(RuntimeTask::opaqueCaseId))
            .map(task -> evaluateTask(generation, inputInventory, task))
            .toList();

        InventoryState outputInventory = inputInventory.activate(
            generation,
            evaluated.stream()
                .filter(value -> value.operator() != null)
                .map(value -> new PendingActivation(
                    value.report().assessment(),
                    value.operator()))
                .toList());
        assertNoSameGenerationFeedback(evaluated, outputInventory, inputInventory);

        List<ActivatedRule> additions = outputInventory.rules().stream()
            .filter(rule -> rule.generation() == generation)
            .toList();
        GenerationReport report = new GenerationReport(
            generation,
            name,
            executionSurface,
            inputInventory.inventoryHash(),
            outputInventory.inventoryHash(),
            true,
            evaluated.stream().map(EvaluatedTask::report).toList(),
            additions);
        return new GenerationExecution(report, outputInventory);
    }

    private EvaluatedTask evaluateTask(
        int generation,
        InventoryState inputInventory,
        RuntimeTask task
    ) {
        RuntimeResult runtime = runner.run(task);
        CandidateEvaluation candidate = evaluateCandidate(
            generation,
            inputInventory.inventoryHash(),
            runtime);
        TaskReport report = new TaskReport(
            task.opaqueCaseId(),
            sha256(task.observableInput()),
            runtime.status().name(),
            runtime.searchStatus().name(),
            runtime.path(),
            runtime.primitiveRuleIds(),
            runtime.assumptions(),
            runtime.searchMetrics(),
            runtime.validationEvidence().passed(),
            runtime.holdouts().allPassed(),
            runtime.holdouts().materialAblations(),
            runtime.failureReason(),
            candidate.assessment());
        return new EvaluatedTask(report, candidate.operator());
    }

    private CandidateEvaluation evaluateCandidate(
        int generation,
        String inputInventoryHash,
        RuntimeResult runtime
    ) {
        CandidateSnapshot candidate = runtime.candidate();
        if (candidate == null) {
            return CandidateEvaluation.rejected(
                CandidateStatus.NO_CANDIDATE,
                "runtime produced no candidate");
        }
        String candidateHash = candidateHash(candidate);
        if (!runtime.frozen()) {
            return CandidateEvaluation.rejected(
                candidate,
                candidateHash,
                CandidateStatus.RUNTIME_NOT_FROZEN,
                runtime.failureReason());
        }
        if (!runtime.validationEvidence().passed()) {
            return CandidateEvaluation.rejected(
                candidate,
                candidateHash,
                CandidateStatus.VALIDATION_REJECTED,
                "validation or counterexample evidence did not pass");
        }
        if (!runtime.holdouts().allPassed()) {
            return CandidateEvaluation.rejected(
                candidate,
                candidateHash,
                CandidateStatus.HOLDOUT_REJECTED,
                "positive or negative holdout failed");
        }
        if (!candidate.assumptions().isEmpty()) {
            return CandidateEvaluation.rejected(
                candidate,
                candidateHash,
                CandidateStatus.ASSUMPTIONS_UNSUPPORTED,
                "v1 shadow activation requires an assumption-free candidate");
        }

        ExactPolynomialPatternIdentityVerifier.Verification proof;
        try {
            proof = exactVerifier.verify(
                candidate.leftPattern(),
                candidate.rightPattern());
        } catch (RuntimeException exception) {
            return CandidateEvaluation.rejected(
                candidate,
                candidateHash,
                CandidateStatus.EXACT_VERIFICATION_FAILED,
                safeMessage(exception));
        }
        if (!proof.proved()) {
            return CandidateEvaluation.rejected(
                candidate,
                candidateHash,
                CandidateStatus.EXACT_NOT_PROVED,
                proof.status().name() + ":" + proof.detailCode(),
                proof);
        }

        String hypothesisId = "generation-" + generation + "-"
            + runtime.opaqueCaseId() + "-" + shortHash(candidateHash);
        DynamicOperatorCompiler.CompilationResult compilation = compiler.compile(
            hypothesisId,
            inputInventoryHash,
            candidate.leftPattern(),
            candidate.rightPattern());
        if (!compilation.isSuccess()) {
            return CandidateEvaluation.rejected(
                candidate,
                candidateHash,
                CandidateStatus.COMPILATION_REJECTED,
                compilation.rejectionReason(),
                proof);
        }
        DynamicPatternOperator operator = compilation.operator().orElseThrow();
        CandidateAssessment assessment = new CandidateAssessment(
            CandidateStatus.EXACT_SHADOW_ELIGIBLE,
            candidateHash,
            candidate.leftPattern(),
            candidate.rightPattern(),
            candidate.assumptions(),
            proof.status().name(),
            proof.detailCode(),
            proof.proofHash(),
            operator.ruleId(),
            operator.provenanceHash(),
            "eligible after generation barrier");
        return new CandidateEvaluation(assessment, operator);
    }

    private RuntimeTask compositionTask(
        String id,
        InventoryState inventory,
        int nesting
    ) {
        return new RuntimeTask(
            id,
            repeatWrap("x", nesting),
            syntaxTarget("x"),
            inventory.engine(),
            COMPOSITION_HEURISTIC,
            List.of(
                new PositiveHoldout(
                    id + "-positive-1",
                    repeatWrap("y + z", nesting),
                    "y + z"),
                new PositiveHoldout(
                    id + "-positive-2",
                    repeatWrap("sin(t)", nesting),
                    "sin(t)")),
            List.of(
                new NegativeHoldout(
                    id + "-negative-1",
                    brokenOuterAdd("x", nesting)),
                new NegativeHoldout(
                    id + "-negative-2",
                    brokenOuterMultiplier("x", nesting))));
    }

    private ReachabilityComparison compareReachability(
        InventoryState baselineInventory,
        InventoryState accumulatedInventory,
        String input,
        String target
    ) {
        GoalSearchResult baseline = search(
            baselineInventory.engine(), input, target);
        GoalSearchResult accumulated = search(
            accumulatedInventory.engine(), input, target);
        SearchState reached = accumulated.reachedState();
        return new ReachabilityComparison(
            sha256(input),
            sha256(target),
            baselineInventory.inventoryHash(),
            accumulatedInventory.inventoryHash(),
            baseline.reached(),
            baseline.status().name(),
            baseline.metrics().exploredStates(),
            accumulated.reached(),
            accumulated.status().name(),
            accumulated.metrics().exploredStates(),
            reached == null ? List.of() : reached.path(),
            reached == null ? List.of() : reached.appliedRuleIds(),
            accumulated.reached() && !baseline.reached());
    }

    private GoalSearchResult search(
        TransformationEngine engine,
        String input,
        String target
    ) {
        SearchProblem problem = new SearchProblem(
            input,
            engine,
            scorer,
            canonicalizer,
            COMPOSITION_HEURISTIC).withTarget(syntaxTarget(target));
        return new BestFirstSearchStrategy().searchWithDiagnostics(problem);
    }

    private SearchTarget syntaxTarget(String expression) {
        return SearchTarget.syntaxExact(
            ExpressionFormatter.format(parser.parseTerm(expression)));
    }

    private static String repeatWrap(String expression, int count) {
        if (count < 1) {
            throw new IllegalArgumentException("count must be positive");
        }
        String result = expression;
        for (int i = 0; i < count; i++) {
            result = wrap(result);
        }
        return result;
    }

    private static String wrap(String expression) {
        return "(((" + expression + ") + 0) * 1) * 1 + 0";
    }

    private static String brokenOuterAdd(String expression, int nesting) {
        String inner = nesting == 1
            ? expression
            : repeatWrap(expression, nesting - 1);
        return "(((" + inner + ") + 0) * 1) * 1 + 1";
    }

    private static String brokenOuterMultiplier(String expression, int nesting) {
        String inner = nesting == 1
            ? expression
            : repeatWrap(expression, nesting - 1);
        return "(((" + inner + ") + 0) * 2) * 1 + 0";
    }

    private static void assertNoSameGenerationFeedback(
        List<EvaluatedTask> evaluated,
        InventoryState output,
        InventoryState input
    ) {
        Set<String> inputRuleIds = input.rules().stream()
            .map(ActivatedRule::operatorRuleId)
            .collect(java.util.stream.Collectors.toSet());
        Set<String> newRuleIds = output.rules().stream()
            .map(ActivatedRule::operatorRuleId)
            .filter(ruleId -> !inputRuleIds.contains(ruleId))
            .collect(java.util.stream.Collectors.toSet());
        for (EvaluatedTask task : evaluated) {
            Set<String> intersection = new HashSet<>(task.report().appliedRuleIds());
            intersection.retainAll(newRuleIds);
            if (!intersection.isEmpty()) {
                throw new IllegalStateException(
                    "same-generation candidate feedback detected: " + intersection);
            }
        }
    }

    private static String candidateHash(CandidateSnapshot candidate) {
        StringBuilder value = new StringBuilder();
        append(value, candidate.leftPattern());
        append(value, candidate.rightPattern());
        candidate.assumptions().stream().sorted()
            .forEach(assumption -> append(value, assumption));
        append(value, candidate.dynamicRuleId());
        append(value, candidate.provenanceHash());
        return sha256(value.toString());
    }

    private static String inventoryHash(
        String repositoryRevision,
        String previousHash,
        List<ActivatedRule> rules
    ) {
        StringBuilder value = new StringBuilder();
        append(value, "regelsuche.exact-shadow-inventory/v1");
        append(value, repositoryRevision);
        append(value, previousHash);
        rules.stream()
            .sorted(Comparator.comparing(ActivatedRule::candidateHash))
            .forEach(rule -> append(value, rule.canonicalMaterial()));
        return sha256(value.toString());
    }

    private static void append(StringBuilder target, String value) {
        String safe = value == null ? "" : value;
        target.append(safe.length()).append(':').append(safe);
    }

    private static String shortHash(String hash) {
        return hash.substring("sha256:".length(), "sha256:".length() + 12);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null
            ? exception.getClass().getSimpleName()
            : exception.getMessage();
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private record GenerationExecution(
        GenerationReport report,
        InventoryState outputInventory
    ) {
    }

    private record EvaluatedTask(
        TaskReport report,
        DynamicPatternOperator operator
    ) {
    }

    private record CandidateEvaluation(
        CandidateAssessment assessment,
        DynamicPatternOperator operator
    ) {
        static CandidateEvaluation rejected(
            CandidateStatus status,
            String reason
        ) {
            return new CandidateEvaluation(
                CandidateAssessment.rejected(status, reason),
                null);
        }

        static CandidateEvaluation rejected(
            CandidateSnapshot candidate,
            String candidateHash,
            CandidateStatus status,
            String reason
        ) {
            return rejected(
                candidate,
                candidateHash,
                status,
                reason,
                null);
        }

        static CandidateEvaluation rejected(
            CandidateSnapshot candidate,
            String candidateHash,
            CandidateStatus status,
            String reason,
            ExactPolynomialPatternIdentityVerifier.Verification proof
        ) {
            return new CandidateEvaluation(
                new CandidateAssessment(
                    status,
                    candidateHash,
                    candidate.leftPattern(),
                    candidate.rightPattern(),
                    candidate.assumptions(),
                    proof == null ? "NOT_EVALUATED" : proof.status().name(),
                    proof == null ? "" : proof.detailCode(),
                    proof == null ? "" : proof.proofHash(),
                    "",
                    "",
                    reason == null ? "" : reason),
                null);
        }
    }

    private record PendingActivation(
        CandidateAssessment assessment,
        DynamicPatternOperator operator
    ) {
    }

    private record InventoryState(
        String repositoryRevision,
        String inventoryHash,
        List<ActivatedRule> rules,
        List<DynamicPatternOperator> operators
    ) {
        InventoryState {
            requireText(repositoryRevision, "repositoryRevision");
            requireText(inventoryHash, "inventoryHash");
            rules = List.copyOf(rules);
            operators = List.copyOf(operators);
        }

        static InventoryState empty(String repositoryRevision) {
            String root = GenerationalRuleMiningCampaign.inventoryHash(
                repositoryRevision,
                "sha256:" + "0".repeat(64),
                List.of());
            return new InventoryState(
                repositoryRevision,
                root,
                List.of(),
                List.of());
        }

        InventoryState activate(
            int generation,
            List<PendingActivation> pending
        ) {
            Set<String> known = rules.stream()
                .map(ActivatedRule::candidateHash)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            List<ActivatedRule> additions = new ArrayList<>();
            List<DynamicPatternOperator> addedOperators = new ArrayList<>();
            pending.stream()
                .sorted(Comparator.comparing(value ->
                    value.assessment().candidateHash()))
                .forEach(value -> {
                    CandidateAssessment assessment = value.assessment();
                    if (known.add(assessment.candidateHash())) {
                        additions.add(new ActivatedRule(
                            generation,
                            assessment.candidateHash(),
                            assessment.leftPattern(),
                            assessment.rightPattern(),
                            assessment.proofHash(),
                            assessment.operatorRuleId(),
                            assessment.operatorProvenanceHash()));
                        addedOperators.add(value.operator());
                    }
                });
            if (additions.isEmpty()) {
                return this;
            }
            List<ActivatedRule> allRules = new ArrayList<>(rules);
            allRules.addAll(additions);
            allRules.sort(Comparator.comparing(ActivatedRule::candidateHash));
            List<DynamicPatternOperator> allOperators = new ArrayList<>(operators);
            allOperators.addAll(addedOperators);
            String nextHash = GenerationalRuleMiningCampaign.inventoryHash(
                repositoryRevision,
                inventoryHash,
                additions);
            return new InventoryState(
                repositoryRevision,
                nextHash,
                allRules,
                allOperators);
        }

        TransformationEngine engine() {
            TransformationEngine base =
                new OccurrenceAwareAstRewriteTransformationEngine(List.of());
            List<HypothesisOperator> active = operators.stream()
                .map(operator -> (HypothesisOperator) operator)
                .toList();
            return new HypothesisTransformationEngine(
                base,
                active,
                Math.max(16, active.size() * 8));
        }
    }

    public enum CandidateStatus {
        NO_CANDIDATE,
        RUNTIME_NOT_FROZEN,
        VALIDATION_REJECTED,
        HOLDOUT_REJECTED,
        ASSUMPTIONS_UNSUPPORTED,
        EXACT_VERIFICATION_FAILED,
        EXACT_NOT_PROVED,
        COMPILATION_REJECTED,
        EXACT_SHADOW_ELIGIBLE
    }

    public record CandidateAssessment(
        CandidateStatus status,
        String candidateHash,
        String leftPattern,
        String rightPattern,
        List<String> assumptions,
        String exactProofStatus,
        String exactProofDetail,
        String proofHash,
        String operatorRuleId,
        String operatorProvenanceHash,
        String reason
    ) {
        public CandidateAssessment {
            Objects.requireNonNull(status, "status");
            candidateHash = candidateHash == null ? "" : candidateHash;
            leftPattern = leftPattern == null ? "" : leftPattern;
            rightPattern = rightPattern == null ? "" : rightPattern;
            assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
            exactProofStatus = exactProofStatus == null ? "NOT_EVALUATED" : exactProofStatus;
            exactProofDetail = exactProofDetail == null ? "" : exactProofDetail;
            proofHash = proofHash == null ? "" : proofHash;
            operatorRuleId = operatorRuleId == null ? "" : operatorRuleId;
            operatorProvenanceHash = operatorProvenanceHash == null
                ? ""
                : operatorProvenanceHash;
            reason = reason == null ? "" : reason;
        }

        static CandidateAssessment rejected(
            CandidateStatus status,
            String reason
        ) {
            return new CandidateAssessment(
                status,
                "",
                "",
                "",
                List.of(),
                "NOT_EVALUATED",
                "",
                "",
                "",
                "",
                reason);
        }

        public boolean eligible() {
            return status == CandidateStatus.EXACT_SHADOW_ELIGIBLE;
        }
    }

    public record ActivatedRule(
        int generation,
        String candidateHash,
        String leftPattern,
        String rightPattern,
        String proofHash,
        String operatorRuleId,
        String operatorProvenanceHash
    ) {
        public ActivatedRule {
            if (generation < 0) {
                throw new IllegalArgumentException("generation must not be negative");
            }
            requireText(candidateHash, "candidateHash");
            requireText(leftPattern, "leftPattern");
            requireText(rightPattern, "rightPattern");
            requireText(proofHash, "proofHash");
            requireText(operatorRuleId, "operatorRuleId");
            requireText(operatorProvenanceHash, "operatorProvenanceHash");
        }

        String canonicalMaterial() {
            return generation + "|" + candidateHash + "|" + leftPattern + "|"
                + rightPattern + "|" + proofHash + "|" + operatorRuleId + "|"
                + operatorProvenanceHash;
        }
    }

    public record TaskReport(
        String taskId,
        String taskFingerprint,
        String runtimeStatus,
        String searchStatus,
        List<String> path,
        List<String> appliedRuleIds,
        List<String> assumptions,
        GoalMetrics metrics,
        boolean validationPassed,
        boolean holdoutsPassed,
        long materialAblations,
        String failureReason,
        CandidateAssessment assessment
    ) {
        public TaskReport {
            requireText(taskId, "taskId");
            requireText(taskFingerprint, "taskFingerprint");
            path = List.copyOf(path);
            appliedRuleIds = List.copyOf(appliedRuleIds);
            assumptions = List.copyOf(assumptions);
            Objects.requireNonNull(metrics, "metrics");
            failureReason = failureReason == null ? "" : failureReason;
            Objects.requireNonNull(assessment, "assessment");
        }

        public boolean reached() {
            return path.size() > 1;
        }
    }

    public record GenerationReport(
        int generation,
        String name,
        String executionSurface,
        String inputInventoryHash,
        String outputInventoryHash,
        boolean sameGenerationFeedbackBlocked,
        List<TaskReport> tasks,
        List<ActivatedRule> activatedRules
    ) {
        public GenerationReport {
            if (generation < 0) {
                throw new IllegalArgumentException("generation must not be negative");
            }
            requireText(name, "name");
            requireText(executionSurface, "executionSurface");
            requireText(inputInventoryHash, "inputInventoryHash");
            requireText(outputInventoryHash, "outputInventoryHash");
            tasks = List.copyOf(tasks);
            activatedRules = List.copyOf(activatedRules);
        }

        public int eligibleCandidates() {
            return (int) tasks.stream()
                .filter(task -> task.assessment().eligible())
                .count();
        }
    }

    public record ReachabilityComparison(
        String inputFingerprint,
        String targetFingerprint,
        String baselineInventoryHash,
        String accumulatedInventoryHash,
        boolean baselineReached,
        String baselineStatus,
        int baselineExploredStates,
        boolean accumulatedReached,
        String accumulatedStatus,
        int accumulatedExploredStates,
        List<String> accumulatedPath,
        List<String> accumulatedRuleIds,
        boolean newlyReachableUnderBudget
    ) {
        public ReachabilityComparison {
            accumulatedPath = List.copyOf(accumulatedPath);
            accumulatedRuleIds = List.copyOf(accumulatedRuleIds);
        }
    }

    public record CampaignReport(
        String schema,
        String campaignId,
        String repositoryRevision,
        String initialInventoryHash,
        String finalInventoryHash,
        List<GenerationReport> generations,
        List<ActivatedRule> finalRules,
        ReachabilityComparison reachability,
        String claimBoundary
    ) {
        public CampaignReport {
            requireText(schema, "schema");
            requireText(campaignId, "campaignId");
            requireText(repositoryRevision, "repositoryRevision");
            requireText(initialInventoryHash, "initialInventoryHash");
            requireText(finalInventoryHash, "finalInventoryHash");
            generations = List.copyOf(generations);
            finalRules = List.copyOf(finalRules);
            Objects.requireNonNull(reachability, "reachability");
            requireText(claimBoundary, "claimBoundary");
        }

        public int totalTasks() {
            return generations.stream().mapToInt(value -> value.tasks().size()).sum();
        }

        public int totalActivatedRules() {
            return finalRules.size();
        }

        public String toJson() {
            JsonWriter json = new JsonWriter().beginObject()
                .property("schema", schema)
                .property("campaignId", campaignId)
                .property("repositoryRevision", repositoryRevision)
                .property("claimBoundary", claimBoundary)
                .object("summary", summary -> summary
                    .property("generations", generations.size())
                    .property("tasks", totalTasks())
                    .property("activatedRules", totalActivatedRules())
                    .property("initialInventoryHash", initialInventoryHash)
                    .property("finalInventoryHash", finalInventoryHash)
                    .property("newlyReachableUnderBudget",
                        reachability.newlyReachableUnderBudget()))
                .array("generations", array -> generations.forEach(generation ->
                    array.objectValue(object -> writeGeneration(object, generation))))
                .array("finalInventory", array -> finalRules.forEach(rule ->
                    array.objectValue(object -> writeActivatedRule(object, rule))))
                .object("reachability", object -> writeReachability(object, reachability))
                .endObject();
            return json.toString();
        }

        private static void writeGeneration(
            JsonWriter json,
            GenerationReport generation
        ) {
            json.property("generation", generation.generation())
                .property("name", generation.name())
                .property("executionSurface", generation.executionSurface())
                .property("inputInventoryHash", generation.inputInventoryHash())
                .property("outputInventoryHash", generation.outputInventoryHash())
                .property("sameGenerationFeedbackBlocked",
                    generation.sameGenerationFeedbackBlocked())
                .property("tasks", generation.tasks().size())
                .property("eligibleCandidates", generation.eligibleCandidates())
                .property("activatedRules", generation.activatedRules().size())
                .array("taskResults", array -> generation.tasks().forEach(task ->
                    array.objectValue(object -> writeTask(object, task))))
                .array("inventoryAdditions", array ->
                    generation.activatedRules().forEach(rule ->
                        array.objectValue(object -> writeActivatedRule(object, rule))));
        }

        private static void writeTask(JsonWriter json, TaskReport task) {
            json.property("taskId", task.taskId())
                .property("taskFingerprint", task.taskFingerprint())
                .property("runtimeStatus", task.runtimeStatus())
                .property("searchStatus", task.searchStatus())
                .property("reached", task.reached())
                .property("validationPassed", task.validationPassed())
                .property("holdoutsPassed", task.holdoutsPassed())
                .property("materialAblations", task.materialAblations())
                .property("failureReason", task.failureReason())
                .stringArray("path", task.path())
                .stringArray("appliedRuleIds", task.appliedRuleIds())
                .stringArray("assumptions", task.assumptions())
                .object("metrics", metrics -> writeMetrics(metrics, task.metrics()))
                .object("candidate", candidate ->
                    writeAssessment(candidate, task.assessment()));
        }

        private static void writeMetrics(JsonWriter json, GoalMetrics metrics) {
            json.property("exploredStates", metrics.exploredStates())
                .property("expandedStates", metrics.expandedStates())
                .property("generatedTransformations", metrics.generatedTransformations())
                .property("enqueuedStates", metrics.enqueuedStates())
                .property("duplicatePrunes", metrics.duplicatePrunes())
                .property("transpositionPrunes", metrics.transpositionPrunes())
                .property("depthPrunes", metrics.depthPrunes())
                .property("candidateBudgetPrunes", metrics.candidateBudgetPrunes());
        }

        private static void writeAssessment(
            JsonWriter json,
            CandidateAssessment assessment
        ) {
            json.property("status", assessment.status().name())
                .property("eligible", assessment.eligible())
                .property("candidateHash", assessment.candidateHash())
                .property("leftPattern", assessment.leftPattern())
                .property("rightPattern", assessment.rightPattern())
                .stringArray("assumptions", assessment.assumptions())
                .property("exactProofStatus", assessment.exactProofStatus())
                .property("exactProofDetail", assessment.exactProofDetail())
                .property("proofHash", assessment.proofHash())
                .property("operatorRuleId", assessment.operatorRuleId())
                .property("operatorProvenanceHash",
                    assessment.operatorProvenanceHash())
                .property("reason", assessment.reason());
        }

        private static void writeActivatedRule(
            JsonWriter json,
            ActivatedRule rule
        ) {
            json.property("generation", rule.generation())
                .property("candidateHash", rule.candidateHash())
                .property("leftPattern", rule.leftPattern())
                .property("rightPattern", rule.rightPattern())
                .property("proofHash", rule.proofHash())
                .property("operatorRuleId", rule.operatorRuleId())
                .property("operatorProvenanceHash",
                    rule.operatorProvenanceHash());
        }

        private static void writeReachability(
            JsonWriter json,
            ReachabilityComparison reachability
        ) {
            json.property("inputFingerprint", reachability.inputFingerprint())
                .property("targetFingerprint", reachability.targetFingerprint())
                .property("baselineInventoryHash",
                    reachability.baselineInventoryHash())
                .property("accumulatedInventoryHash",
                    reachability.accumulatedInventoryHash())
                .property("baselineReached", reachability.baselineReached())
                .property("baselineStatus", reachability.baselineStatus())
                .property("baselineExploredStates",
                    reachability.baselineExploredStates())
                .property("accumulatedReached", reachability.accumulatedReached())
                .property("accumulatedStatus", reachability.accumulatedStatus())
                .property("accumulatedExploredStates",
                    reachability.accumulatedExploredStates())
                .property("newlyReachableUnderBudget",
                    reachability.newlyReachableUnderBudget())
                .stringArray("accumulatedPath", reachability.accumulatedPath())
                .stringArray("accumulatedRuleIds",
                    reachability.accumulatedRuleIds());
        }
    }
}
