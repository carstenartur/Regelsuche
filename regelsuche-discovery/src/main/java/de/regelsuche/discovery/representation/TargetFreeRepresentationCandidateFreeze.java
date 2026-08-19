package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.requireSha256;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.requireText;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.knowledge.KnowledgePackSelection;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.search.strategy.SearchStrategy;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import de.regelsuche.util.AtomicJsonFile;
import de.regelsuche.validation.CandidateProofStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Executes and freezes every target-blind formation row before qualification. */
public final class TargetFreeRepresentationCandidateFreeze {
    public static final String SCHEMA =
        "regelsuche.target-free-representation-candidate-freeze/v1";
    public static final String FILE_NAME =
        "representation-discovery-candidate-freeze.json";
    public static final String EVIDENCE_STATUS =
        "EXECUTED_CANDIDATES_FROZEN_QUALIFICATION_NOT_DISCLOSED";
    public static final String QUALIFICATION_DISCLOSURE = "NOT_DISCLOSED";
    public static final String VISIBLE_KNOWLEDGE_POLICY =
        "MINIMAL_KERNEL_WITH_EXPLICIT_PACKS_AND_TRACK_SPECIFIC_WITHHOLDING_V1";
    public static final String CLAIM_BOUNDARY =
        "All configured policies receive only the frozen source, assumptions, "
            + "track, visible-inventory policy and finite formation budgets. "
            + "Candidate batches and mechanical work are frozen before any "
            + "post-freeze qualification resource is opened. This artifact "
            + "does not classify success, prove equivalence, establish novelty "
            + "or claim CPU-time or general search superiority.";

    private static final String EXECUTED = "EXECUTED";
    private static final String FRONTIER_EXHAUSTED = "FRONTIER_EXHAUSTED";
    private static final String BOUNDED_WITH_TRUNCATION =
        "BOUNDED_SEARCH_COMPLETED_WITH_TRUNCATION";
    private static final Comparator<Transformation> TRANSFORMATION_ORDER =
        Comparator.comparing(Transformation::rule)
            .thenComparing(Transformation::transformedExpression)
            .thenComparing(Transformation::applicationKey)
            .thenComparing(value -> String.join(
                "\u0001", value.primitiveRuleIds()));
    private static final Comparator<CandidateEvidence> CANDIDATE_ORDER =
        Comparator.comparing(CandidateEvidence::expression)
            .thenComparing(value -> String.join(
                "\u0001", value.assumptions()))
            .thenComparingInt(CandidateEvidence::depth)
            .thenComparing(value -> String.join(
                "\u0001", value.pathRuleIds()))
            .thenComparing(CandidateEvidence::candidateHash);
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .build();

    private TargetFreeRepresentationCandidateFreeze() {
    }

    public static FreezeArtifact run(String repositoryRevision) {
        TargetFreeRepresentationEvaluationPlan.EvaluationPlan plan =
            TargetFreeRepresentationEvaluationPlan.create(repositoryRevision);
        Map<String, TargetFreeRepresentationEvaluationPlan.CaseDefinition>
            cases = indexCases(plan.content().cases());
        Map<String, TargetFreeRepresentationEvaluationPlan.PolicyDefinition>
            policies = indexPolicies(plan.content().policies());
        List<ExecutionEntry> entries = plan.content().entries().stream()
            .map(entry -> execute(
                entry,
                requireCase(cases, entry.caseId()),
                requirePolicy(policies, entry.policyId())
            ))
            .toList();
        FreezeContent content = new FreezeContent(
            SCHEMA,
            EVIDENCE_STATUS,
            plan.content().repositoryRevision(),
            plan.contentHash(),
            plan.content().preregistrationHash(),
            plan.content().formationHash(),
            plan.content().qualificationHash(),
            plan.content().qualificationByteLength(),
            QUALIFICATION_DISCLOSURE,
            VISIBLE_KNOWLEDGE_POLICY,
            entries,
            Summary.derive(entries),
            CLAIM_BOUNDARY
        );
        return FreezeArtifact.create(content);
    }

    public static FreezeArtifact write(
        Path directory,
        String repositoryRevision
    ) throws IOException {
        Path root = Objects.requireNonNull(directory, "directory")
            .toAbsolutePath().normalize();
        Files.createDirectories(root);
        FreezeArtifact artifact = run(repositoryRevision);
        String canonical = artifact.toCanonicalJson();
        Path target = root.resolve(FILE_NAME);
        AtomicJsonFile.writeUtf8(target, canonical);
        if (!Files.isRegularFile(target)
                || !canonical.equals(Files.readString(
                    target, StandardCharsets.UTF_8))) {
            throw new IllegalStateException(
                "written candidate-freeze artifact changed: " + target);
        }
        if (!artifact.equals(FreezeArtifact.fromCanonicalJson(canonical))) {
            throw new IllegalStateException(
                "candidate-freeze artifact changed during round-trip");
        }
        return artifact;
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                "usage: <repository-commit> <output-directory>");
        }
        FreezeArtifact artifact = write(Path.of(args[1]), args[0]);
        System.out.println(
            "targetFreeRepresentationCandidateFreezeHash="
                + artifact.contentHash());
        System.out.println(
            "targetFreeRepresentationExecutedConfigurations="
                + artifact.content().summary().executedEntryCount());
        System.out.println(
            "targetFreeRepresentationFrozenCandidates="
                + artifact.content().summary().candidateCount());
    }

    private static ExecutionEntry execute(
        TargetFreeRepresentationEvaluationPlan.PlanEntry planEntry,
        TargetFreeRepresentationEvaluationPlan.CaseDefinition benchmarkCase,
        TargetFreeRepresentationEvaluationPlan.PolicyDefinition policy
    ) {
        requireInitialAssumptionPolicy(policy);
        RepresentationDiscoveryInformationBoundary boundary =
            RepresentationDiscoveryInformationBoundary.fromKnowledgePacks(
                benchmarkCase.informationTrack(),
                visibleSelection(benchmarkCase)
            );
        ExecutionResult result = switch (policy.adapterInterface()) {
            case TARGET_FREE_REPRESENTATION_SEARCH ->
                executeEnumeration(benchmarkCase, policy, boundary);
            case SEARCH_STRATEGY ->
                executeSearchStrategy(benchmarkCase, policy, boundary);
        };
        List<RepresentationCandidateProposal> proposals = result.candidates()
            .stream()
            .map(candidate -> RepresentationCandidateProposal.whole(
                benchmarkCase.sourceExpression(),
                candidate.expression(),
                candidate.assumptions(),
                CandidateProofStatus.OBSERVED
            ))
            .toList();
        RepresentationDiscoveryInformationBoundary.CandidateFreezeReceipt
            receipt = boundary.freezeCandidates(proposals);
        if (receipt.candidateCount() != result.candidates().size()) {
            throw new IllegalStateException(
                "candidate receipt count differs for "
                    + planEntry.configurationId());
        }
        return ExecutionEntry.create(
            planEntry,
            benchmarkCase,
            policy,
            boundary,
            result,
            receipt
        );
    }

    private static KnowledgePackSelection visibleSelection(
        TargetFreeRepresentationEvaluationPlan.CaseDefinition benchmarkCase
    ) {
        KnowledgePackSelection selection = KnowledgePackSelection.profile(
            benchmarkCase.ruleProfile());
        for (String packId : benchmarkCase.enabledRulePackIds()) {
            selection = selection.enablePack(packId);
        }
        return selection;
    }

    private static ExecutionResult executeEnumeration(
        TargetFreeRepresentationEvaluationPlan.CaseDefinition benchmarkCase,
        TargetFreeRepresentationEvaluationPlan.PolicyDefinition policy,
        RepresentationDiscoveryInformationBoundary boundary
    ) {
        requireEnumerationInvocationContract(policy);
        TargetFreeRepresentationEvaluationPlan.WorkBudget configured =
            benchmarkCase.budget();
        TargetFreeRepresentationSearch.Budget budget =
            new TargetFreeRepresentationSearch.Budget(
                configured.maxDepth(),
                configured.maxExploredStates(),
                configured.maxRetainedStates(),
                configured.maxGeneratedTransitions(),
                configured.maxCandidatesPerState(),
                configured.maxAstSizeIncreasePerStep()
            );
        TargetFreeRepresentationSearch.SearchResult search =
            new TargetFreeRepresentationSearch().search(
                benchmarkCase.sourceExpression(),
                boundary.candidateFormationRules(),
                budget
            );
        TargetFreeRepresentationSearch.SearchContent content =
            search.content();
        List<CandidateEvidence> candidates = canonicalCandidates(
            content.candidateStates().stream()
                .map(state -> CandidateEvidence.create(
                    state.expression(),
                    union(benchmarkCase.assumptions(), state.assumptions()),
                    state.depth(),
                    state.pathRuleIds(),
                    state.primitiveRuleIds(),
                    "EXACT_PRIMITIVE_LINEAGE",
                    state.equivalencePreserving()
                ))
                .toList()
        );
        int engineCalls = (int) content.states().stream()
            .limit(content.exploredStateCount())
            .filter(state -> state.depth() < budget.maxDepth())
            .count();
        int primitiveSteps = content.transitions().stream()
            .mapToInt(transition -> transition.primitiveRuleIds().size())
            .sum();
        WorkLedger work = WorkLedger.create(
            policy.id(),
            engineCalls,
            content.exploredStateCount(),
            content.states().size(),
            content.generatedTransitionCount(),
            primitiveSteps
        );
        List<String> reasons = content.truncationReasons().stream()
            .map(Enum::name)
            .sorted()
            .toList();
        return new ExecutionResult(
            candidates,
            work,
            reasons.isEmpty() ? FRONTIER_EXHAUSTED
                : BOUNDED_WITH_TRUNCATION,
            reasons,
            search.contentHash()
        );
    }

    private static ExecutionResult executeSearchStrategy(
        TargetFreeRepresentationEvaluationPlan.CaseDefinition benchmarkCase,
        TargetFreeRepresentationEvaluationPlan.PolicyDefinition policy,
        RepresentationDiscoveryInformationBoundary boundary
    ) {
        TargetFreeRepresentationEvaluationPlan.WorkBudget configured =
            benchmarkCase.budget();
        int retainedLimit = Math.min(
            configured.maxExploredStates(),
            configured.maxRetainedStates()
        );
        BoundedLedgerEngine engine = new BoundedLedgerEngine(
            new AstRewriteTransformationEngine(
                boundary.candidateFormationRules(),
                configured.maxAstSizeIncreasePerStep(),
                configured.maxCandidatesPerState()
            ),
            configured.maxGeneratedTransitions(),
            configured.maxCandidatesPerState()
        );
        SearchProblem problem = new SearchProblem(
            benchmarkCase.sourceExpression(),
            engine,
            new ExpressionScorer(),
            new ExpressionCanonicalizer(),
            new SearchHeuristic(
                configured.maxDepth(),
                retainedLimit,
                configured.significantImprovementThreshold(),
                configured.maxExpandingSteps(),
                configured.maxCandidatesPerState(),
                configured.beamWidth()
            )
        );
        if (problem.target() != null) {
            throw new IllegalStateException(
                "target-free evaluation attached a target");
        }
        SearchStrategy strategy = instantiateSearchStrategy(policy);
        List<SearchState> states = List.copyOf(strategy.search(problem));
        if (states.size() > retainedLimit) {
            throw new IllegalStateException(
                "search strategy exceeded retained-state limit");
        }
        List<CandidateEvidence> candidates = canonicalCandidates(
            states.stream()
                .filter(state -> state.depth() > 0)
                .map(state -> CandidateEvidence.create(
                    state.expression(),
                    union(benchmarkCase.assumptions(), state.assumptions()),
                    state.depth(),
                    state.appliedRuleIds(),
                    List.of(),
                    "PRIMITIVE_IDS_NOT_RETAINED_BY_SEARCH_STATE",
                    state.equivalencePreservingFlags().stream()
                        .allMatch(Boolean.TRUE::equals)
                ))
                .toList()
        );
        TreeSet<String> reasons = new TreeSet<>();
        if (states.size() >= retainedLimit) {
            reasons.add("MAX_RETAINED_OR_EXPLORED_STATES");
        }
        if (engine.transitionBudgetReached()) {
            reasons.add("MAX_GENERATED_TRANSITIONS");
        }
        if (engine.candidateLimitObserved()) {
            reasons.add("MAX_CANDIDATES_PER_STATE");
        }
        if (states.stream().anyMatch(
                state -> state.depth() >= configured.maxDepth())) {
            reasons.add("MAX_DEPTH");
        }
        WorkLedger work = WorkLedger.create(
            policy.id(),
            engine.engineCalls(),
            states.size(),
            states.size(),
            engine.generatedTransitions(),
            engine.generatedPrimitiveSteps()
        );
        String workAuthorityHash = KnownStructureCatalog.sha256(
            canonical(work));
        return new ExecutionResult(
            candidates,
            work,
            reasons.isEmpty() ? FRONTIER_EXHAUSTED
                : BOUNDED_WITH_TRUNCATION,
            List.copyOf(reasons),
            workAuthorityHash
        );
    }

    static void requireEnumerationInvocationContract(
        TargetFreeRepresentationEvaluationPlan.PolicyDefinition policy
    ) {
        Objects.requireNonNull(policy, "policy");
        if (!TargetFreeRepresentationSearch.class.getName().equals(
                policy.adapter())
                || policy.adapterInterface()
                    != TargetFreeRepresentationEvaluationPlan
                        .AdapterInterface.TARGET_FREE_REPRESENTATION_SEARCH
                || policy.adapterConstructor()
                    != TargetFreeRepresentationEvaluationPlan
                        .AdapterConstructor.NO_ARGUMENT
                || policy.deterministicSeed() != 0L) {
            throw new IllegalArgumentException(
                "native target-free invocation differs from frozen plan");
        }
    }

    private static SearchStrategy instantiateSearchStrategy(
        TargetFreeRepresentationEvaluationPlan.PolicyDefinition policy
    ) {
        try {
            Class<?> type = Class.forName(policy.adapter());
            Object instance = switch (policy.adapterConstructor()) {
                case NO_ARGUMENT -> type.getConstructor().newInstance();
                case LONG_SEED -> type.getConstructor(long.class)
                    .newInstance(policy.deterministicSeed());
            };
            if (!(instance instanceof SearchStrategy strategy)) {
                throw new IllegalArgumentException(
                    "configured adapter is not a SearchStrategy: "
                        + policy.adapter());
            }
            return strategy;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalArgumentException(
                "cannot instantiate configured target-free policy: "
                    + policy.adapter(),
                exception
            );
        }
    }

    private static void requireInitialAssumptionPolicy(
        TargetFreeRepresentationEvaluationPlan.PolicyDefinition policy
    ) {
        if (policy.initialAssumptionPolicy()
                != TargetFreeRepresentationEvaluationPlan
                    .InitialAssumptionPolicy
                    .UNION_WITH_RETAINED_STATE_ASSUMPTIONS) {
            throw new IllegalArgumentException(
                "unsupported initial assumption policy");
        }
    }

    private static List<CandidateEvidence> canonicalCandidates(
        List<CandidateEvidence> candidates
    ) {
        TreeMap<String, CandidateEvidence> byProposal = new TreeMap<>();
        for (CandidateEvidence candidate : candidates) {
            byProposal.merge(
                candidate.proposalKey(),
                candidate,
                (left, right) -> CANDIDATE_ORDER.compare(left, right) <= 0
                    ? left : right
            );
        }
        return byProposal.values().stream()
            .sorted(CANDIDATE_ORDER)
            .toList();
    }

    private static List<String> union(
        List<String> left,
        List<String> right
    ) {
        TreeSet<String> result = new TreeSet<>(left);
        result.addAll(right);
        return List.copyOf(result);
    }

    private static Map<String,
            TargetFreeRepresentationEvaluationPlan.CaseDefinition> indexCases(
        List<TargetFreeRepresentationEvaluationPlan.CaseDefinition> cases
    ) {
        Map<String, TargetFreeRepresentationEvaluationPlan.CaseDefinition>
            result = new LinkedHashMap<>();
        cases.forEach(value -> result.put(value.id(), value));
        return Map.copyOf(result);
    }

    private static Map<String,
            TargetFreeRepresentationEvaluationPlan.PolicyDefinition>
            indexPolicies(
        List<TargetFreeRepresentationEvaluationPlan.PolicyDefinition> policies
    ) {
        Map<String, TargetFreeRepresentationEvaluationPlan.PolicyDefinition>
            result = new LinkedHashMap<>();
        policies.forEach(value -> result.put(value.id(), value));
        return Map.copyOf(result);
    }

    private static TargetFreeRepresentationEvaluationPlan.CaseDefinition
            requireCase(
        Map<String, TargetFreeRepresentationEvaluationPlan.CaseDefinition>
            cases,
        String id
    ) {
        return Objects.requireNonNull(cases.get(id), "case " + id);
    }

    private static TargetFreeRepresentationEvaluationPlan.PolicyDefinition
            requirePolicy(
        Map<String, TargetFreeRepresentationEvaluationPlan.PolicyDefinition>
            policies,
        String id
    ) {
        return Objects.requireNonNull(policies.get(id), "policy " + id);
    }

    private static String canonical(Object value) {
        try {
            return JSON.writeValueAsString(
                Objects.requireNonNull(value, "value"));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                "unable to render target-free candidate freeze",
                exception
            );
        }
    }

    private static String hashCandidates(List<CandidateEvidence> candidates) {
        return KnownStructureCatalog.sha256(canonical(candidates));
    }

    public record CandidateEvidence(
        String candidateHash,
        String expression,
        List<String> assumptions,
        int depth,
        List<String> pathRuleIds,
        List<String> primitiveRuleIds,
        String primitiveLineageStatus,
        boolean equivalencePreserving
    ) {
        public CandidateEvidence {
            expression = requireText(expression, "expression");
            assumptions = List.copyOf(new TreeSet<>(
                Objects.requireNonNull(assumptions, "assumptions")));
            pathRuleIds = List.copyOf(
                Objects.requireNonNull(pathRuleIds, "pathRuleIds"));
            primitiveRuleIds = List.copyOf(
                Objects.requireNonNull(
                    primitiveRuleIds, "primitiveRuleIds"));
            primitiveLineageStatus = requireText(
                primitiveLineageStatus, "primitiveLineageStatus");
            if (depth < 1 || pathRuleIds.isEmpty()) {
                throw new IllegalArgumentException(
                    "candidate evidence requires a non-root lineage");
            }
            candidateHash = requireSha256(
                candidateHash, "candidateHash");
            if (!candidateHash.equals(hashCandidate(
                    expression,
                    assumptions,
                    depth,
                    pathRuleIds,
                    primitiveRuleIds,
                    primitiveLineageStatus,
                    equivalencePreserving))) {
                throw new IllegalArgumentException(
                    "candidate evidence hash mismatch");
            }
        }

        static CandidateEvidence create(
            String expression,
            List<String> assumptions,
            int depth,
            List<String> pathRuleIds,
            List<String> primitiveRuleIds,
            String primitiveLineageStatus,
            boolean equivalencePreserving
        ) {
            List<String> normalizedAssumptions = List.copyOf(
                new TreeSet<>(assumptions));
            return new CandidateEvidence(
                hashCandidate(
                    expression,
                    normalizedAssumptions,
                    depth,
                    pathRuleIds,
                    primitiveRuleIds,
                    primitiveLineageStatus,
                    equivalencePreserving
                ),
                expression,
                normalizedAssumptions,
                depth,
                pathRuleIds,
                primitiveRuleIds,
                primitiveLineageStatus,
                equivalencePreserving
            );
        }

        String proposalKey() {
            StringBuilder descriptor = new StringBuilder();
            KnownStructureCatalog.appendCanonicalField(
                descriptor, expression);
            KnownStructureCatalog.appendCanonicalList(
                descriptor, assumptions);
            return descriptor.toString();
        }

        private static String hashCandidate(
            String expression,
            List<String> assumptions,
            int depth,
            List<String> pathRuleIds,
            List<String> primitiveRuleIds,
            String primitiveLineageStatus,
            boolean equivalencePreserving
        ) {
            StringBuilder descriptor = new StringBuilder();
            KnownStructureCatalog.appendCanonicalField(
                descriptor, SCHEMA + "/candidate");
            KnownStructureCatalog.appendCanonicalField(
                descriptor, requireText(expression, "expression"));
            KnownStructureCatalog.appendCanonicalList(
                descriptor, assumptions);
            KnownStructureCatalog.appendCanonicalField(
                descriptor, Integer.toString(depth));
            KnownStructureCatalog.appendCanonicalList(
                descriptor, pathRuleIds);
            KnownStructureCatalog.appendCanonicalList(
                descriptor, primitiveRuleIds);
            KnownStructureCatalog.appendCanonicalField(
                descriptor,
                requireText(
                    primitiveLineageStatus,
                    "primitiveLineageStatus"
                )
            );
            KnownStructureCatalog.appendCanonicalField(
                descriptor, Boolean.toString(equivalencePreserving));
            return KnownStructureCatalog.sha256(descriptor.toString());
        }
    }

    public record WorkLedger(
        String schema,
        String policyId,
        int engineCalls,
        int exploredStates,
        int retainedStates,
        int generatedTransitions,
        int generatedPrimitiveSteps,
        String contentHash
    ) {
        public WorkLedger {
            schema = requireText(schema, "schema");
            if (!(SCHEMA + "/work-ledger/v1").equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported work-ledger schema");
            }
            policyId = requireText(policyId, "policyId");
            if (engineCalls < 0 || exploredStates < 1
                    || retainedStates < 1
                    || generatedTransitions < 0
                    || generatedPrimitiveSteps < generatedTransitions) {
                throw new IllegalArgumentException(
                    "work-ledger counters do not balance");
            }
            contentHash = requireSha256(contentHash, "contentHash");
            if (!contentHash.equals(hashWork(
                    policyId,
                    engineCalls,
                    exploredStates,
                    retainedStates,
                    generatedTransitions,
                    generatedPrimitiveSteps))) {
                throw new IllegalArgumentException(
                    "work-ledger content hash mismatch");
            }
        }

        static WorkLedger create(
            String policyId,
            int engineCalls,
            int exploredStates,
            int retainedStates,
            int generatedTransitions,
            int generatedPrimitiveSteps
        ) {
            return new WorkLedger(
                SCHEMA + "/work-ledger/v1",
                policyId,
                engineCalls,
                exploredStates,
                retainedStates,
                generatedTransitions,
                generatedPrimitiveSteps,
                hashWork(
                    policyId,
                    engineCalls,
                    exploredStates,
                    retainedStates,
                    generatedTransitions,
                    generatedPrimitiveSteps
                )
            );
        }

        private static String hashWork(
            String policyId,
            int engineCalls,
            int exploredStates,
            int retainedStates,
            int generatedTransitions,
            int generatedPrimitiveSteps
        ) {
            StringBuilder descriptor = new StringBuilder();
            KnownStructureCatalog.appendCanonicalField(
                descriptor, SCHEMA + "/work-ledger/v1");
            KnownStructureCatalog.appendCanonicalField(
                descriptor, requireText(policyId, "policyId"));
            for (int value : List.of(
                    engineCalls,
                    exploredStates,
                    retainedStates,
                    generatedTransitions,
                    generatedPrimitiveSteps)) {
                KnownStructureCatalog.appendCanonicalField(
                    descriptor, Integer.toString(value));
            }
            return KnownStructureCatalog.sha256(descriptor.toString());
        }
    }

    public record ExecutionEntry(
        int sequence,
        String configurationId,
        String caseId,
        String policyId,
        String status,
        String terminalReason,
        List<String> truncationReasons,
        String informationTrack,
        String informationBoundaryHash,
        String formationSelectionCommitment,
        String formationRuleInventoryHash,
        String postFreezeCatalogCommitment,
        long deterministicSeed,
        TargetFreeRepresentationEvaluationPlan.WorkBudget configuredBudget,
        BudgetProjection appliedSearchBudget,
        List<CandidateEvidence> candidates,
        int candidateCount,
        String candidateBatchHash,
        String candidateSetHash,
        String candidateFreezeReceiptHash,
        WorkLedger work,
        String workAuthorityHash
    ) {
        public ExecutionEntry {
            if (sequence < 1) {
                throw new IllegalArgumentException(
                    "execution sequence must be positive");
            }
            configurationId = requireSha256(
                configurationId, "configurationId");
            caseId = requireText(caseId, "caseId");
            policyId = requireText(policyId, "policyId");
            status = requireText(status, "status");
            if (!EXECUTED.equals(status)) {
                throw new IllegalArgumentException(
                    "candidate freeze currently accepts executed rows only");
            }
            terminalReason = requireText(
                terminalReason, "terminalReason");
            truncationReasons = List.copyOf(new TreeSet<>(
                Objects.requireNonNull(
                    truncationReasons, "truncationReasons")));
            informationTrack = requireText(
                informationTrack, "informationTrack");
            informationBoundaryHash = requireSha256(
                informationBoundaryHash, "informationBoundaryHash");
            formationSelectionCommitment = requireSha256(
                formationSelectionCommitment,
                "formationSelectionCommitment"
            );
            formationRuleInventoryHash = requireSha256(
                formationRuleInventoryHash,
                "formationRuleInventoryHash"
            );
            postFreezeCatalogCommitment = requireSha256(
                postFreezeCatalogCommitment,
                "postFreezeCatalogCommitment"
            );
            configuredBudget = Objects.requireNonNull(
                configuredBudget, "configuredBudget");
            appliedSearchBudget = Objects.requireNonNull(
                appliedSearchBudget, "appliedSearchBudget");
            candidates = Objects.requireNonNull(candidates, "candidates")
                .stream().sorted(CANDIDATE_ORDER).toList();
            if (candidateCount != candidates.size()) {
                throw new IllegalArgumentException(
                    "candidate count differs from candidate evidence");
            }
            candidateBatchHash = requireSha256(
                candidateBatchHash, "candidateBatchHash");
            if (!candidateBatchHash.equals(hashCandidates(candidates))) {
                throw new IllegalArgumentException(
                    "candidate batch hash mismatch");
            }
            candidateSetHash = requireSha256(
                candidateSetHash, "candidateSetHash");
            candidateFreezeReceiptHash = requireSha256(
                candidateFreezeReceiptHash,
                "candidateFreezeReceiptHash"
            );
            work = Objects.requireNonNull(work, "work");
            workAuthorityHash = requireSha256(
                workAuthorityHash, "workAuthorityHash");
        }

        static ExecutionEntry create(
            TargetFreeRepresentationEvaluationPlan.PlanEntry planEntry,
            TargetFreeRepresentationEvaluationPlan.CaseDefinition benchmarkCase,
            TargetFreeRepresentationEvaluationPlan.PolicyDefinition policy,
            RepresentationDiscoveryInformationBoundary boundary,
            ExecutionResult result,
            RepresentationDiscoveryInformationBoundary.CandidateFreezeReceipt
                receipt
        ) {
            return new ExecutionEntry(
                planEntry.sequence(),
                planEntry.configurationId(),
                benchmarkCase.id(),
                policy.id(),
                EXECUTED,
                result.terminalReason(),
                result.truncationReasons(),
                benchmarkCase.informationTrack().name(),
                boundary.contentHash(),
                boundary.candidateFormationSelectionCommitment(),
                boundary.candidateFormationRuleInventoryHash(),
                boundary.postFreezeCatalogCommitment(),
                policy.deterministicSeed(),
                benchmarkCase.budget(),
                BudgetProjection.from(benchmarkCase.budget()),
                result.candidates(),
                result.candidates().size(),
                hashCandidates(result.candidates()),
                receipt.candidateSetHash(),
                receipt.contentHash(),
                result.work(),
                result.workAuthorityHash()
            );
        }
    }

    public record BudgetProjection(
        int maxDepth,
        int maxRetainedOrExploredStates,
        int maxGeneratedTransitions,
        int maxCandidatesPerState,
        int maxAstSizeIncreasePerStep,
        int significantImprovementThreshold,
        int maxExpandingSteps,
        int beamWidth
    ) {
        public BudgetProjection {
            if (maxDepth < 0 || maxRetainedOrExploredStates < 1
                    || maxGeneratedTransitions < 1
                    || maxCandidatesPerState < 1
                    || maxAstSizeIncreasePerStep < 0
                    || significantImprovementThreshold < 1
                    || maxExpandingSteps < 0
                    || maxExpandingSteps > maxDepth
                    || beamWidth < 1
                    || beamWidth > maxRetainedOrExploredStates) {
                throw new IllegalArgumentException(
                    "applied search budget is invalid");
            }
        }

        static BudgetProjection from(
            TargetFreeRepresentationEvaluationPlan.WorkBudget value
        ) {
            int retainedOrExplored = Math.min(
                value.maxExploredStates(),
                value.maxRetainedStates()
            );
            return new BudgetProjection(
                value.maxDepth(),
                retainedOrExplored,
                value.maxGeneratedTransitions(),
                value.maxCandidatesPerState(),
                value.maxAstSizeIncreasePerStep(),
                value.significantImprovementThreshold(),
                value.maxExpandingSteps(),
                value.beamWidth()
            );
        }
    }

    public record Summary(
        int configuredEntryCount,
        int executedEntryCount,
        int zeroCandidateEntryCount,
        int truncatedEntryCount,
        int candidateCount,
        int engineCalls,
        int exploredStates,
        int generatedTransitions,
        int generatedPrimitiveSteps
    ) {
        public Summary {
            if (configuredEntryCount < 1
                    || executedEntryCount != configuredEntryCount
                    || zeroCandidateEntryCount < 0
                    || truncatedEntryCount < 0
                    || candidateCount < 0
                    || engineCalls < 0
                    || exploredStates < configuredEntryCount
                    || generatedTransitions < 0
                    || generatedPrimitiveSteps < generatedTransitions) {
                throw new IllegalArgumentException(
                    "candidate-freeze summary does not balance");
            }
        }

        static Summary derive(List<ExecutionEntry> entries) {
            return new Summary(
                entries.size(),
                (int) entries.stream()
                    .filter(value -> EXECUTED.equals(value.status())).count(),
                (int) entries.stream()
                    .filter(value -> value.candidateCount() == 0).count(),
                (int) entries.stream()
                    .filter(value -> !value.truncationReasons().isEmpty())
                    .count(),
                entries.stream().mapToInt(ExecutionEntry::candidateCount).sum(),
                entries.stream().mapToInt(value ->
                    value.work().engineCalls()).sum(),
                entries.stream().mapToInt(value ->
                    value.work().exploredStates()).sum(),
                entries.stream().mapToInt(value ->
                    value.work().generatedTransitions()).sum(),
                entries.stream().mapToInt(value ->
                    value.work().generatedPrimitiveSteps()).sum()
            );
        }
    }

    public record FreezeContent(
        String schema,
        String evidenceStatus,
        String repositoryRevision,
        String evaluationPlanHash,
        String preregistrationHash,
        String formationHash,
        String qualificationHash,
        long qualificationByteLength,
        String qualificationDisclosure,
        String visibleKnowledgePolicy,
        List<ExecutionEntry> entries,
        Summary summary,
        String claimBoundary
    ) {
        public FreezeContent {
            schema = requireText(schema, "schema");
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported candidate-freeze schema");
            }
            evidenceStatus = requireText(
                evidenceStatus, "evidenceStatus");
            if (!EVIDENCE_STATUS.equals(evidenceStatus)) {
                throw new IllegalArgumentException(
                    "candidate-freeze evidence status differs");
            }
            repositoryRevision = requireText(
                repositoryRevision, "repositoryRevision");
            if (!repositoryRevision.matches("[0-9a-f]{40}")) {
                throw new IllegalArgumentException(
                    "repositoryRevision must be a commit SHA");
            }
            evaluationPlanHash = requireSha256(
                evaluationPlanHash, "evaluationPlanHash");
            preregistrationHash = requireSha256(
                preregistrationHash, "preregistrationHash");
            formationHash = requireSha256(
                formationHash, "formationHash");
            qualificationHash = requireSha256(
                qualificationHash, "qualificationHash");
            if (qualificationByteLength < 1) {
                throw new IllegalArgumentException(
                    "qualification byte length must be positive");
            }
            qualificationDisclosure = requireText(
                qualificationDisclosure, "qualificationDisclosure");
            if (!QUALIFICATION_DISCLOSURE.equals(
                    qualificationDisclosure)) {
                throw new IllegalArgumentException(
                    "qualification must remain undisclosed");
            }
            visibleKnowledgePolicy = requireText(
                visibleKnowledgePolicy, "visibleKnowledgePolicy");
            entries = List.copyOf(
                Objects.requireNonNull(entries, "entries"));
            if (entries.isEmpty()) {
                throw new IllegalArgumentException(
                    "candidate-freeze entries must not be empty");
            }
            for (int index = 0; index < entries.size(); index++) {
                if (entries.get(index).sequence() != index + 1) {
                    throw new IllegalArgumentException(
                        "candidate-freeze sequence is not canonical");
                }
            }
            if (entries.stream().map(ExecutionEntry::configurationId)
                    .distinct().count() != entries.size()) {
                throw new IllegalArgumentException(
                    "candidate-freeze configuration IDs are not unique");
            }
            summary = Objects.requireNonNull(summary, "summary");
            if (!summary.equals(Summary.derive(entries))) {
                throw new IllegalArgumentException(
                    "candidate-freeze summary differs from entries");
            }
            claimBoundary = requireText(
                claimBoundary, "claimBoundary");
        }
    }

    public record FreezeArtifact(
        FreezeContent content,
        String contentHash
    ) {
        public FreezeArtifact {
            content = Objects.requireNonNull(content, "content");
            contentHash = requireSha256(contentHash, "contentHash");
            String expected = KnownStructureCatalog.sha256(
                canonical(content));
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "candidate-freeze content hash mismatch");
            }
        }

        static FreezeArtifact create(FreezeContent content) {
            return new FreezeArtifact(
                content,
                KnownStructureCatalog.sha256(canonical(content))
            );
        }

        public String toCanonicalJson() {
            return canonical(this);
        }

        public static FreezeArtifact fromCanonicalJson(String source) {
            String json = Objects.requireNonNull(source, "source");
            try {
                FreezeArtifact artifact = JSON.readValue(
                    json, FreezeArtifact.class);
                if (!artifact.toCanonicalJson().equals(json)) {
                    throw new IllegalArgumentException(
                        "candidate-freeze JSON is not canonical");
                }
                return artifact;
            } catch (JsonProcessingException exception) {
                throw new IllegalArgumentException(
                    "invalid target-free candidate-freeze JSON",
                    exception
                );
            }
        }
    }

    private record ExecutionResult(
        List<CandidateEvidence> candidates,
        WorkLedger work,
        String terminalReason,
        List<String> truncationReasons,
        String workAuthorityHash
    ) {
        private ExecutionResult {
            candidates = List.copyOf(candidates);
            work = Objects.requireNonNull(work, "work");
            terminalReason = requireText(
                terminalReason, "terminalReason");
            truncationReasons = List.copyOf(new TreeSet<>(
                truncationReasons));
            workAuthorityHash = requireSha256(
                workAuthorityHash, "workAuthorityHash");
        }
    }

    private static final class BoundedLedgerEngine
            implements TransformationEngine {
        private final TransformationEngine delegate;
        private final int maxGeneratedTransitions;
        private final int maxCandidatesPerState;
        private int engineCalls;
        private int generatedTransitions;
        private int generatedPrimitiveSteps;
        private boolean candidateLimitObserved;

        private BoundedLedgerEngine(
            TransformationEngine delegate,
            int maxGeneratedTransitions,
            int maxCandidatesPerState
        ) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            if (maxGeneratedTransitions < 1 || maxCandidatesPerState < 1) {
                throw new IllegalArgumentException(
                    "ledger-engine budgets must be positive");
            }
            this.maxGeneratedTransitions = maxGeneratedTransitions;
            this.maxCandidatesPerState = maxCandidatesPerState;
        }

        @Override
        public List<Transformation> transform(String expression) {
            if (transitionBudgetReached()) {
                return List.of();
            }
            engineCalls++;
            List<Transformation> generated = new ArrayList<>(
                delegate.transform(expression));
            generated.sort(TRANSFORMATION_ORDER);
            candidateLimitObserved |=
                generated.size() >= maxCandidatesPerState;
            int remaining = maxGeneratedTransitions - generatedTransitions;
            List<Transformation> admitted = generated.stream()
                .limit(remaining)
                .toList();
            generatedTransitions += admitted.size();
            generatedPrimitiveSteps += admitted.stream()
                .mapToInt(Transformation::primitiveStepCount)
                .sum();
            return admitted;
        }

        int engineCalls() {
            return engineCalls;
        }

        int generatedTransitions() {
            return generatedTransitions;
        }

        int generatedPrimitiveSteps() {
            return generatedPrimitiveSteps;
        }

        boolean transitionBudgetReached() {
            return generatedTransitions >= maxGeneratedTransitions;
        }

        boolean candidateLimitObserved() {
            return candidateLimitObserved;
        }
    }
}
