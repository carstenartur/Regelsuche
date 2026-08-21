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
import de.regelsuche.knowledge.CoreRuleCatalog;
import de.regelsuche.knowledge.KnowledgePackRegistry;
import de.regelsuche.knowledge.KnowledgePackSelection;
import de.regelsuche.knowledge.RuleProfile;
import de.regelsuche.search.strategy.SearchStrategy;
import de.regelsuche.transform.Transformation;
import de.regelsuche.util.AtomicJsonFile;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.validation.SymPyOracleValidator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Executes the preregistered 6 x 4 x 6 target-free held-out matrix.
 *
 * <p>The plan and candidate freeze are created without opening the sealed
 * qualification resource. Qualification is read only after the complete
 * canonical candidate-freeze artifact has been written and reloaded.</p>
 */
public final class TargetFreeHeldOutMatrixRunner {
    public static final String PLAN_SCHEMA =
        "regelsuche.target-free-held-out-plan/v1";
    public static final String FREEZE_SCHEMA =
        "regelsuche.target-free-held-out-candidate-freeze/v1";
    public static final String QUALIFICATION_SCHEMA =
        "regelsuche.target-free-held-out-post-freeze-qualification/v1";
    public static final String PLAN_FILE_NAME =
        "target-free-held-out-plan.json";
    public static final String FREEZE_FILE_NAME =
        "target-free-held-out-candidate-freeze.json";
    public static final String QUALIFICATION_FILE_NAME =
        "target-free-held-out-post-freeze-qualification.json";
    public static final String PREREGISTRATION_RESOURCE =
        "/de/regelsuche/discovery/representation/"
            + "target-free-held-out-preregistration-v1.json";
    public static final long PREREGISTRATION_BYTE_LENGTH = 1512L;
    public static final String PREREGISTRATION_SHA256 =
        "sha256:9d79fe60a2a2cf3fda9255cc3b26577b4ac23d61b8359cd22b3a7a0dcdb7bd29";
    public static final String QUALIFICATION_NOT_DISCLOSED = "NOT_DISCLOSED";
    public static final String QUALIFICATION_DISCLOSED =
        "DISCLOSED_AFTER_COMPLETE_CANDIDATE_FREEZE";
    public static final String PLAN_STATUS = "FROZEN_NOT_EXECUTED";
    public static final String FREEZE_STATUS =
        "EXECUTED_CANDIDATES_FROZEN_QUALIFICATION_NOT_DISCLOSED";
    public static final String QUALIFICATION_STATUS =
        "FROZEN_CANDIDATES_QUALIFIED_POST_FREEZE";
    public static final String EXACT_CHECKPOINT = "EXACT_CHECKPOINT_REACHED";
    public static final String NOT_COMPARABLE =
        "EXHAUSTED_BEFORE_CHECKPOINT_NOT_COMPARABLE";
    public static final String POSITIVE_OUTCOME =
        "QUALIFY_AT_ONE_OR_MORE_MATCHED_WORK_CHECKPOINTS";
    public static final String NEGATIVE_OUTCOME = "NO_POLICY_QUALIFIES";
    public static final String CLAIM_BOUNDARY =
        "The runner executes only the frozen target-blind formation surface, "
            + "stops before admitting primitive work beyond each checkpoint, "
            + "and freezes candidate lineages, candidate sets and work ledgers "
            + "before opening qualification. Matched-work comparisons require "
            + "all four policies to have reached the exact checkpoint. The "
            + "result is bounded evidence, not external mathematical novelty, "
            + "global optimality, CPU-time equality or general superiority.";

    static final Comparator<Transformation> TRANSFORMATION_ORDER =
        Comparator.comparing(Transformation::rule)
            .thenComparing(Transformation::transformedExpression)
            .thenComparing(Transformation::applicationKey)
            .thenComparing(value -> String.join(
                "\u0001", value.primitiveRuleIds()))
            .thenComparing(value -> String.join(
                "\u0001", value.assumptions()));
    static final Comparator<CandidateEvidence> CANDIDATE_ORDER =
        Comparator.comparing(CandidateEvidence::expression)
            .thenComparing(value -> String.join(
                "\u0001", value.assumptions()))
            .thenComparingInt(CandidateEvidence::depth)
            .thenComparing(CandidateEvidence::temporaryComplexityIncrease)
            .thenComparing(value -> String.join(
                "\u0001", value.pathRuleIds()))
            .thenComparing(value -> String.join(
                "\u0001", value.pathExpressions()))
            .thenComparing(CandidateEvidence::candidateHash);
    static final JsonMapper JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .build();
    static final ExpressionCanonicalizer CANONICALIZER =
        new ExpressionCanonicalizer();

    private TargetFreeHeldOutMatrixRunner() {
    }

    public static RunArtifacts run(String repositoryRevision) {
        PlanArtifact plan = createPlan(repositoryRevision);
        FreezeArtifact freeze = freeze(plan);
        QualificationArtifact qualification = qualify(plan, freeze);
        return new RunArtifacts(plan, freeze, qualification);
    }

    public static RunArtifacts write(
        Path outputDirectory,
        String repositoryRevision
    ) throws IOException {
        Path root = Objects.requireNonNull(
            outputDirectory, "outputDirectory")
            .toAbsolutePath().normalize();
        Files.createDirectories(root);

        PlanArtifact plan = createPlan(repositoryRevision);
        writeCanonical(root.resolve(PLAN_FILE_NAME), plan.toCanonicalJson());

        FreezeArtifact freeze = freeze(plan);
        Path freezePath = root.resolve(FREEZE_FILE_NAME);
        writeCanonical(freezePath, freeze.toCanonicalJson());
        FreezeArtifact reloadedFreeze = FreezeArtifact.fromCanonicalJson(
            Files.readString(freezePath, StandardCharsets.UTF_8));
        if (!freeze.equals(reloadedFreeze)) {
            throw new IllegalStateException(
                "candidate freeze changed before qualification");
        }

        QualificationArtifact qualification = qualify(plan, reloadedFreeze);
        writeCanonical(
            root.resolve(QUALIFICATION_FILE_NAME),
            qualification.toCanonicalJson());
        return new RunArtifacts(plan, freeze, qualification);
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                "usage: <repository-commit> <output-directory>");
        }
        RunArtifacts artifacts = write(Path.of(args[1]), args[0]);
        System.out.println("targetFreeHeldOutPlanHash="
            + artifacts.plan().contentHash());
        System.out.println("targetFreeHeldOutCandidateFreezeHash="
            + artifacts.freeze().contentHash());
        System.out.println("targetFreeHeldOutQualificationHash="
            + artifacts.qualification().contentHash());
        System.out.println("targetFreeHeldOutRows="
            + artifacts.freeze().content().summary().configuredRows());
        System.out.println("targetFreeHeldOutExactRows="
            + artifacts.freeze().content().summary().exactCheckpointRows());
        System.out.println("targetFreeHeldOutEligibleComparisons="
            + artifacts.freeze().content().summary()
                .eligibleMatchedWorkGroups());
        System.out.println("targetFreeHeldOutQualifiedRows="
            + artifacts.qualification().content().summary()
                .qualifiedPositiveRows());
    }

    static PlanArtifact createPlan(String repositoryRevision) {
        String revision = requireRevision(repositoryRevision);
        byte[] preregistrationBytes = TargetFreeRepresentationEvaluationPlan
            .readResource(PREREGISTRATION_RESOURCE);
        requireBinding(
            preregistrationBytes,
            PREREGISTRATION_BYTE_LENGTH,
            PREREGISTRATION_SHA256,
            "held-out preregistration");
        Preregistration preregistration = decode(
            preregistrationBytes, Preregistration.class,
            "held-out preregistration");
        byte[] formationBytes = TargetFreeRepresentationEvaluationPlan
            .readResource(preregistration.formationResource());
        requireBinding(
            formationBytes,
            preregistration.formationByteLength(),
            preregistration.formationSha256(),
            "held-out formation");
        Formation formation = decode(
            formationBytes, Formation.class, "held-out formation");
        validatePlanResources(preregistration, formation);

        String preregistrationHash = TargetFreeRepresentationEvaluationPlan
            .sha256(preregistrationBytes);
        List<PlanRow> rows = new ArrayList<>();
        int sequence = 1;
        for (CaseSpec benchmarkCase : formation.cases()) {
            for (PolicySpec policy : formation.policies()) {
                for (int checkpoint : formation.workMatching().checkpoints()) {
                    rows.add(new PlanRow(
                        sequence++,
                        configurationId(
                            preregistrationHash,
                            benchmarkCase.id(),
                            policy.id(),
                            checkpoint),
                        benchmarkCase.id(),
                        policy.id(),
                        checkpoint,
                        "REMAINING",
                        "NOT_EXECUTED"));
                }
            }
        }
        PlanContent content = new PlanContent(
            PLAN_SCHEMA,
            PLAN_STATUS,
            revision,
            PREREGISTRATION_RESOURCE,
            preregistrationHash,
            preregistration.formationResource(),
            preregistration.formationSha256(),
            preregistration.formationByteLength(),
            preregistration.qualificationResource(),
            preregistration.qualificationSha256(),
            preregistration.qualificationByteLength(),
            QUALIFICATION_NOT_DISCLOSED,
            formation.informationBoundary(),
            formation.workMatching(),
            formation.cases(),
            formation.policies(),
            rows,
            preregistration.claimBoundary());
        return PlanArtifact.create(content);
    }

    static FreezeArtifact freeze(PlanArtifact plan) {
        Objects.requireNonNull(plan, "plan");
        Map<String, CaseSpec> cases = indexCases(plan.content().cases());
        Map<String, PolicySpec> policies = indexPolicies(
            plan.content().policies());
        List<FreezeRow> rows = plan.content().rows().stream()
            .map(row -> TargetFreeHeldOutFormationExecutor.executeRow(
                row,
                Objects.requireNonNull(cases.get(row.caseId())),
                Objects.requireNonNull(policies.get(row.policyId()))))
            .toList();
        List<MatchedWorkGroup> groups =
            TargetFreeHeldOutQualifier.matchedWorkGroups(
                plan.content(), rows);
        FreezeContent content = new FreezeContent(
            FREEZE_SCHEMA,
            FREEZE_STATUS,
            plan.content().repositoryRevision(),
            plan.contentHash(),
            plan.content().preregistrationHash(),
            plan.content().formationHash(),
            plan.content().qualificationHash(),
            plan.content().qualificationByteLength(),
            QUALIFICATION_NOT_DISCLOSED,
            rows,
            groups,
            FreezeSummary.derive(rows, groups),
            CLAIM_BOUNDARY);
        return FreezeArtifact.create(content);
    }

    static QualificationArtifact qualify(
        PlanArtifact plan,
        FreezeArtifact freeze
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(freeze, "freeze");
        requireFreezeAuthority(plan, freeze);

        byte[] qualificationBytes = TargetFreeRepresentationEvaluationPlan
            .readResource(plan.content().qualificationResource());
        requireBinding(
            qualificationBytes,
            plan.content().qualificationByteLength(),
            plan.content().qualificationHash(),
            "held-out qualification");
        QualificationSpec qualification = decode(
            qualificationBytes,
            QualificationSpec.class,
            "held-out qualification");
        validateQualification(plan, qualification);

        Map<String, CaseSpec> cases = indexCases(plan.content().cases());
        Map<String, CaseQualification> rules = qualification
            .caseQualifications().stream().collect(
                Collectors.toUnmodifiableMap(
                    CaseQualification::id, value -> value));
        SymPyOracleValidator oracle = new SymPyOracleValidator();
        List<QualificationRow> rows = freeze.content().rows().stream()
            .map(row -> TargetFreeHeldOutQualifier.qualifyRow(
                row,
                Objects.requireNonNull(cases.get(row.caseId())),
                Objects.requireNonNull(rules.get(row.caseId())),
                oracle))
            .toList();
        List<PolicyComparison> comparisons =
            TargetFreeHeldOutQualifier.policyComparisons(
                freeze.content().matchedWorkGroups(), rows, rules);
        QualificationContent content = new QualificationContent(
            QUALIFICATION_SCHEMA,
            QUALIFICATION_STATUS,
            plan.content().repositoryRevision(),
            plan.contentHash(),
            freeze.contentHash(),
            plan.content().qualificationResource(),
            TargetFreeRepresentationEvaluationPlan.sha256(
                qualificationBytes),
            qualificationBytes.length,
            QUALIFICATION_DISCLOSED,
            rows,
            comparisons,
            QualificationSummary.derive(rows, comparisons),
            qualification.qualificationBoundary(),
            CLAIM_BOUNDARY);
        return QualificationArtifact.create(content);
    }

    private static void requireFreezeAuthority(
        PlanArtifact plan,
        FreezeArtifact freeze
    ) {
        FreezeContent content = freeze.content();
        List<String> expected = plan.content().rows().stream()
            .map(PlanRow::configurationId).toList();
        List<String> actual = content.rows().stream()
            .map(FreezeRow::configurationId).toList();
        if (!content.repositoryRevision().equals(
                plan.content().repositoryRevision())
                || !content.planHash().equals(plan.contentHash())
                || !content.preregistrationHash().equals(
                    plan.content().preregistrationHash())
                || !content.formationHash().equals(
                    plan.content().formationHash())
                || !content.qualificationHash().equals(
                    plan.content().qualificationHash())
                || !QUALIFICATION_NOT_DISCLOSED.equals(
                    content.qualificationDisclosure())
                || !expected.equals(actual)
                || content.rows().size() != 144) {
            throw new IllegalArgumentException(
                "candidate freeze is not the complete frozen authority");
        }
    }

    static void requireBoundaryAuthority(
        FreezeRow frozen,
        RepresentationDiscoveryInformationBoundary boundary
    ) {
        if (!boundary.contentHash().equals(
                frozen.informationBoundaryHash())
                || !boundary.candidateFormationSelectionCommitment()
                    .equals(frozen.formationSelectionCommitment())
                || !boundary.candidateFormationRuleInventoryHash()
                    .equals(frozen.formationRuleInventoryHash())
                || !boundary.postFreezeCatalogCommitment()
                    .equals(frozen.postFreezeCatalogCommitment())) {
            throw new IllegalArgumentException(
                "information boundary changed before qualification");
        }
    }

    private static void validatePlanResources(
        Preregistration preregistration,
        Formation formation
    ) {
        if (!"regelsuche.target-free-held-out-preregistration/v1".equals(
                preregistration.schema())
                || !PLAN_STATUS.equals(preregistration.evidenceStatus())
                || !"regelsuche.target-free-held-out-formation/v1".equals(
                    formation.schema())
                || !PLAN_STATUS.equals(formation.evidenceStatus())) {
            throw new IllegalArgumentException(
                "unsupported held-out plan resources");
        }
        if (formation.cases().size()
                != preregistration.configuredCaseCount()
                || formation.policies().size()
                    != preregistration.configuredPolicyCount()
                || formation.workMatching().checkpoints().size()
                    != preregistration.configuredCheckpointCount()
                || formation.cases().size()
                    * formation.policies().size()
                    * formation.workMatching().checkpoints().size()
                    != preregistration.configuredEntryCount()) {
            throw new IllegalArgumentException(
                "held-out matrix counts do not balance");
        }
        if (!List.of(8, 16, 32, 64, 128, 256).equals(
                formation.workMatching().checkpoints())
                || !"ADMITTED_PRIMITIVE_STEPS".equals(
                    formation.workMatching().authority())
                || !"ALL_POLICIES_REACHED_EXACT_CHECKPOINT".equals(
                    formation.workMatching().comparisonEligibility())
                || !"STOP_BEFORE_ADMITTING_A_STEP_BEYOND_THE_CHECKPOINT"
                    .equals(formation.workMatching().stopSemantics())) {
            throw new IllegalArgumentException(
                "held-out work-matching contract changed");
        }
        Set<String> availablePacks = new HashSet<>(
            CoreRuleCatalog.packIds());
        new KnowledgePackRegistry().allPacks().forEach(pack ->
            availablePacks.add(pack.packId()));
        for (CaseSpec benchmarkCase : formation.cases()) {
            if (benchmarkCase.ruleProfile() != RuleProfile.MINIMAL_KERNEL
                    || benchmarkCase.distractorRulePackIds().isEmpty()
                    || !benchmarkCase.enabledRulePackIds().containsAll(
                        benchmarkCase.distractorRulePackIds())) {
                throw new IllegalArgumentException(
                    "case differs from frozen inventory contract: "
                        + benchmarkCase.id());
            }
            for (String pack : benchmarkCase.enabledRulePackIds()) {
                if (!availablePacks.contains(pack)) {
                    throw new IllegalArgumentException(
                        "unknown pack " + pack + " for "
                            + benchmarkCase.id());
                }
            }
        }
        formation.policies().forEach(
            TargetFreeHeldOutMatrixRunner::validatePolicyAdapter);
    }

    private static void validatePolicyAdapter(PolicySpec policy) {
        try {
            Class<?> adapter = Class.forName(
                policy.adapter(),
                false,
                TargetFreeHeldOutMatrixRunner.class.getClassLoader());
            if ("TARGET_FREE_REPRESENTATION_SEARCH".equals(
                    policy.adapterInterface())) {
                if (adapter != TargetFreeRepresentationSearch.class) {
                    throw new IllegalArgumentException(
                        "native enumeration adapter changed");
                }
            } else if ("SEARCH_STRATEGY".equals(
                    policy.adapterInterface())) {
                if (!SearchStrategy.class.isAssignableFrom(adapter)) {
                    throw new IllegalArgumentException(
                        "search adapter contract changed");
                }
            } else {
                throw new IllegalArgumentException(
                    "unknown adapter interface");
            }
            if ("NO_ARGUMENT".equals(policy.adapterConstructor())) {
                adapter.getConstructor();
            } else if ("LONG_SEED".equals(
                    policy.adapterConstructor())) {
                adapter.getConstructor(long.class);
            } else {
                throw new IllegalArgumentException(
                    "unknown adapter constructor");
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalArgumentException(
                "configured policy adapter is unavailable: "
                    + policy.adapter(), exception);
        }
    }

    private static void validateQualification(
        PlanArtifact plan,
        QualificationSpec qualification
    ) {
        if (!"regelsuche.target-free-held-out-qualification/v1".equals(
                qualification.schema())
                || !"SEALED_POST_FREEZE".equals(
                    qualification.evidenceStatus())) {
            throw new IllegalArgumentException(
                "unsupported sealed qualification");
        }
        List<String> expected = plan.content().cases().stream()
            .map(CaseSpec::id).toList();
        List<String> actual = qualification.caseQualifications().stream()
            .map(CaseQualification::id).toList();
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(
                "qualification cases differ from plan");
        }
        qualification.caseQualifications().forEach(value -> {
            if (!Set.of(POSITIVE_OUTCOME, NEGATIVE_OUTCOME).contains(
                    value.expectedOutcome())
                    || value.referenceExpressions().isEmpty()) {
                throw new IllegalArgumentException(
                    "incomplete qualification for " + value.id());
            }
            value.requiredCapabilities().forEach(
                TargetFreeHeldOutQualifier::capabilityRuleId);
        });
    }

    static KnowledgePackSelection visibleSelection(
        CaseSpec benchmarkCase
    ) {
        KnowledgePackSelection selection = KnowledgePackSelection.profile(
            benchmarkCase.ruleProfile());
        for (String pack : benchmarkCase.enabledRulePackIds()) {
            selection = selection.enablePack(pack);
        }
        return selection;
    }

    static List<RepresentationCandidateProposal> uniqueProposals(
        String source,
        List<CandidateEvidence> candidates
    ) {
        TreeMap<String, RepresentationCandidateProposal> byKey =
            new TreeMap<>();
        for (CandidateEvidence candidate : candidates) {
            RepresentationCandidateProposal proposal =
                RepresentationCandidateProposal.whole(
                    source,
                    candidate.expression(),
                    candidate.assumptions(),
                    CandidateProofStatus.OBSERVED);
            byKey.putIfAbsent(candidate.proposalKey(), proposal);
        }
        return List.copyOf(byKey.values());
    }

    static List<CandidateEvidence> canonicalCandidateLineages(
        List<CandidateEvidence> candidates
    ) {
        Map<String, CandidateEvidence> byHash = new TreeMap<>();
        for (CandidateEvidence candidate : candidates) {
            byHash.putIfAbsent(candidate.candidateHash(), candidate);
        }
        return byHash.values().stream().sorted(CANDIDATE_ORDER).toList();
    }

    private static Map<String, CaseSpec> indexCases(
        List<CaseSpec> cases
    ) {
        return cases.stream().collect(Collectors.toUnmodifiableMap(
            CaseSpec::id, value -> value));
    }

    private static Map<String, PolicySpec> indexPolicies(
        List<PolicySpec> policies
    ) {
        return policies.stream().collect(Collectors.toUnmodifiableMap(
            PolicySpec::id, value -> value));
    }

    static List<String> union(
        List<String> left,
        List<String> right
    ) {
        TreeSet<String> values = new TreeSet<>(left);
        values.addAll(right);
        return List.copyOf(values);
    }

    static String enumStateKey(EnumState state) {
        return CANONICALIZER.stableHash(state.expression())
            + "|" + String.join("\u0000", state.assumptions())
            + "|" + state.temporaryComplexityIncrease();
    }

    private static String configurationId(
        String preregistrationHash,
        String caseId,
        String policyId,
        int checkpoint
    ) {
        StringBuilder descriptor = new StringBuilder();
        KnownStructureCatalog.appendCanonicalField(
            descriptor, PLAN_SCHEMA + "/configuration");
        KnownStructureCatalog.appendCanonicalField(
            descriptor, requireSha256(
                preregistrationHash, "preregistrationHash"));
        KnownStructureCatalog.appendCanonicalField(
            descriptor, requireText(caseId, "caseId"));
        KnownStructureCatalog.appendCanonicalField(
            descriptor, requireText(policyId, "policyId"));
        KnownStructureCatalog.appendCanonicalField(
            descriptor, Integer.toString(checkpoint));
        return KnownStructureCatalog.sha256(descriptor.toString());
    }

    private static void requireBinding(
        byte[] bytes,
        long expectedLength,
        String expectedHash,
        String label
    ) {
        if (bytes.length != expectedLength
                || !TargetFreeRepresentationEvaluationPlan.sha256(bytes)
                    .equals(requireSha256(expectedHash, label + "Hash"))) {
            throw new IllegalArgumentException(
                label + " differs from its byte commitment");
        }
    }

    private static String requireRevision(String value) {
        String revision = requireText(value, "repositoryRevision");
        if (!revision.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException(
                "repositoryRevision must be a lowercase Git SHA");
        }
        return revision;
    }

    private static <T> T decode(
        byte[] bytes,
        Class<T> type,
        String label
    ) {
        try {
            return JSON.readValue(bytes, type);
        } catch (IOException exception) {
            throw new IllegalArgumentException("invalid " + label, exception);
        }
    }

    static String canonical(Object value) {
        try {
            return JSON.writeValueAsString(
                Objects.requireNonNull(value, "value"));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                "cannot render held-out evidence", exception);
        }
    }

    private static void writeCanonical(Path target, String value)
            throws IOException {
        AtomicJsonFile.writeUtf8(target, value);
        if (!Files.isRegularFile(target)
                || !value.equals(Files.readString(
                    target, StandardCharsets.UTF_8))) {
            throw new IllegalStateException(
                "canonical evidence changed while writing " + target);
        }
    }

    static void reject(
        Set<String> reasons,
        boolean condition,
        String reason
    ) {
        if (condition) {
            reasons.add(reason);
        }
    }
}
