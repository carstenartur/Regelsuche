package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.requireSha256;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.requireText;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.sortedStrings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.regelsuche.discovery.representation.RepresentationDiscoveryInformationBoundary.Track;
import de.regelsuche.search.strategy.SearchStrategy;
import de.regelsuche.util.AtomicJsonFile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Generates the frozen, target-blind representation-discovery evaluation plan.
 *
 * <p>The generator loads the preregistration and candidate-formation resources,
 * but deliberately never opens the post-freeze qualification resource. The
 * resulting plan contains only its opaque byte hash and length.</p>
 */
public final class TargetFreeRepresentationEvaluationPlan {
    public static final String SCHEMA =
        "regelsuche.target-free-representation-evaluation-plan/v1";
    public static final String FILE_NAME =
        "representation-discovery-plan.json";
    public static final String PREREGISTRATION_RESOURCE =
        "/de/regelsuche/discovery/representation/"
            + "target-free-representation-preregistration-v1.json";
    public static final String FORMATION_SCHEMA =
        "regelsuche.target-free-representation-formation/v1";
    public static final String PREREGISTRATION_SCHEMA =
        "regelsuche.target-free-representation-preregistration/v1";
    public static final String EVIDENCE_STATUS = "FROZEN_NOT_EXECUTED";
    public static final String ENTRY_STATUS = "REMAINING";
    public static final String TERMINAL_REASON = "NOT_EXECUTED";
    public static final String QUALIFICATION_DISCLOSURE = "NOT_DISCLOSED";

    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .build();

    private TargetFreeRepresentationEvaluationPlan() {
    }

    public static EvaluationPlan create(String repositoryRevision) {
        String revision = requireRepositoryRevision(repositoryRevision);
        byte[] preregistrationBytes = readResource(PREREGISTRATION_RESOURCE);
        Preregistration preregistration = decode(
            preregistrationBytes,
            Preregistration.class,
            "target-free representation preregistration"
        );
        byte[] formationBytes = readResource(
            preregistration.formationResource());
        requireResourceBinding(
            formationBytes,
            preregistration.formationByteLength(),
            preregistration.formationSha256(),
            "formation"
        );
        Formation formation = decode(
            formationBytes,
            Formation.class,
            "target-free representation formation"
        );
        validateCounts(preregistration, formation);
        formation.policies().forEach(
            TargetFreeRepresentationEvaluationPlan::requireAdapter);

        String preregistrationHash = sha256(preregistrationBytes);
        List<PlanEntry> entries = new ArrayList<>();
        int sequence = 1;
        for (CaseDefinition benchmarkCase : formation.cases()) {
            for (PolicyDefinition policy : formation.policies()) {
                entries.add(new PlanEntry(
                    sequence++,
                    configurationId(
                        preregistrationHash,
                        benchmarkCase.id(),
                        policy.id()
                    ),
                    benchmarkCase.id(),
                    policy.id(),
                    ENTRY_STATUS,
                    TERMINAL_REASON
                ));
            }
        }
        PlanContent content = new PlanContent(
            SCHEMA,
            EVIDENCE_STATUS,
            revision,
            PREREGISTRATION_RESOURCE,
            preregistrationHash,
            preregistration.formationResource(),
            preregistration.formationSha256(),
            preregistration.formationByteLength(),
            preregistration.qualificationResource(),
            preregistration.qualificationSha256(),
            preregistration.qualificationByteLength(),
            QUALIFICATION_DISCLOSURE,
            formation.informationBoundary(),
            formation.cases(),
            formation.policies(),
            entries,
            List.of(new StatusCount(ENTRY_STATUS, entries.size())),
            preregistration.configuredCaseCount(),
            preregistration.configuredPolicyCount(),
            preregistration.configuredEntryCount(),
            preregistration.claimBoundary()
        );
        return EvaluationPlan.create(content);
    }

    public static EvaluationPlan write(
        Path directory,
        String repositoryRevision
    ) throws IOException {
        Path root = Objects.requireNonNull(directory, "directory")
            .toAbsolutePath().normalize();
        Files.createDirectories(root);
        EvaluationPlan plan = create(repositoryRevision);
        String canonical = plan.toCanonicalJson();
        Path target = root.resolve(FILE_NAME);
        AtomicJsonFile.writeUtf8(target, canonical);
        if (!Files.isRegularFile(target)
                || !canonical.equals(Files.readString(
                    target,
                    StandardCharsets.UTF_8))) {
            throw new IllegalStateException(
                "written representation-discovery plan changed: " + target);
        }
        if (!plan.equals(EvaluationPlan.fromCanonicalJson(canonical))) {
            throw new IllegalStateException(
                "representation-discovery plan changed during round-trip");
        }
        return plan;
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                "usage: <repository-commit> <output-directory>");
        }
        EvaluationPlan plan = write(Path.of(args[1]), args[0]);
        System.out.println(
            "targetFreeRepresentationEvaluationPlanHash="
                + plan.contentHash()
        );
        System.out.println(
            "targetFreeRepresentationEvaluationConfigurations="
                + plan.content().configuredEntryCount()
        );
    }

    static byte[] readResource(String resource) {
        String path = requireResourcePath(resource, "resource");
        try (InputStream input =
                TargetFreeRepresentationEvaluationPlan.class
                    .getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException(
                    "missing representation-discovery resource " + path);
            }
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException(
                "cannot read representation-discovery resource " + path,
                exception
            );
        }
    }

    static String sha256(byte[] source) {
        Objects.requireNonNull(source, "source");
        try {
            return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(source));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static <T> T decode(
        byte[] source,
        Class<T> type,
        String label
    ) {
        try {
            return JSON.readValue(
                Objects.requireNonNull(source, "source"),
                Objects.requireNonNull(type, "type")
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid " + label, exception);
        }
    }

    private static String canonical(Object value) {
        try {
            return JSON.writeValueAsString(
                Objects.requireNonNull(value, "value"));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                "unable to render representation-discovery plan",
                exception
            );
        }
    }

    private static void validateCounts(
        Preregistration preregistration,
        Formation formation
    ) {
        if (formation.cases().size()
                != preregistration.configuredCaseCount()) {
            throw new IllegalArgumentException(
                "formation case count differs from preregistration");
        }
        if (formation.policies().size()
                != preregistration.configuredPolicyCount()) {
            throw new IllegalArgumentException(
                "formation policy count differs from preregistration");
        }
        int matrix = Math.multiplyExact(
            formation.cases().size(),
            formation.policies().size()
        );
        if (matrix != preregistration.configuredEntryCount()) {
            throw new IllegalArgumentException(
                "formation matrix differs from preregistration");
        }
    }

    private static void requireResourceBinding(
        byte[] bytes,
        long expectedLength,
        String expectedHash,
        String label
    ) {
        if (bytes.length != expectedLength) {
            throw new IllegalArgumentException(
                label + " byte length differs: expected=" + expectedLength
                    + ", actual=" + bytes.length);
        }
        String actualHash = sha256(bytes);
        if (!requireSha256(expectedHash, label + "Sha256")
                .equals(actualHash)) {
            throw new IllegalArgumentException(
                label + " byte hash differs: expected=" + expectedHash
                    + ", actual=" + actualHash);
        }
    }

    private static void requireAdapter(PolicyDefinition policy) {
        try {
            Class<?> adapter = Class.forName(
                policy.adapter(),
                false,
                TargetFreeRepresentationEvaluationPlan.class.getClassLoader()
            );
            requireAdapterInterface(policy, adapter);
            switch (policy.adapterConstructor()) {
                case NO_ARGUMENT -> adapter.getConstructor();
                case LONG_SEED -> adapter.getConstructor(long.class);
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalArgumentException(
                "configured target-blind policy adapter contract is "
                    + "unavailable: " + policy.adapter(),
                exception
            );
        }
    }

    private static void requireAdapterInterface(
        PolicyDefinition policy,
        Class<?> adapter
    ) {
        boolean matches = switch (policy.adapterInterface()) {
            case TARGET_FREE_REPRESENTATION_SEARCH ->
                adapter == TargetFreeRepresentationSearch.class;
            case SEARCH_STRATEGY ->
                SearchStrategy.class.isAssignableFrom(adapter);
        };
        if (!matches) {
            throw new IllegalArgumentException(
                "configured target-blind policy adapter has the wrong "
                    + "invocation interface: " + policy.adapter());
        }
    }

    private static String configurationId(
        String preregistrationHash,
        String caseId,
        String policyId
    ) {
        StringBuilder descriptor = new StringBuilder();
        KnownStructureCatalog.appendCanonicalField(
            descriptor,
            SCHEMA + "/configuration"
        );
        KnownStructureCatalog.appendCanonicalField(
            descriptor,
            requireSha256(
                preregistrationHash,
                "preregistrationHash"
            )
        );
        KnownStructureCatalog.appendCanonicalField(
            descriptor,
            requireText(caseId, "caseId")
        );
        KnownStructureCatalog.appendCanonicalField(
            descriptor,
            requireText(policyId, "policyId")
        );
        return KnownStructureCatalog.sha256(descriptor.toString());
    }

    private static String requireRepositoryRevision(String value) {
        String revision = requireText(value, "repositoryRevision");
        if (!revision.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException(
                "repositoryRevision must be a lowercase 40-character "
                    + "Git commit SHA");
        }
        return revision;
    }

    private static String requireResourcePath(
        String value,
        String field
    ) {
        String resource = requireText(value, field);
        if (!resource.startsWith("/") || !resource.endsWith(".json")
                || resource.contains("..")) {
            throw new IllegalArgumentException(
                field + " must be an absolute classpath JSON resource");
        }
        return resource;
    }

    private static List<CaseDefinition> canonicalCases(
        List<CaseDefinition> values
    ) {
        Objects.requireNonNull(values, "cases");
        List<CaseDefinition> sorted = values.stream()
            .map(value -> Objects.requireNonNull(value, "case"))
            .sorted(Comparator.comparing(CaseDefinition::id))
            .toList();
        requireUnique(
            sorted.stream().map(CaseDefinition::id).toList(),
            "case"
        );
        if (sorted.isEmpty()) {
            throw new IllegalArgumentException("cases must not be empty");
        }
        return sorted;
    }

    private static List<PolicyDefinition> canonicalPolicies(
        List<PolicyDefinition> values
    ) {
        Objects.requireNonNull(values, "policies");
        List<PolicyDefinition> sorted = values.stream()
            .map(value -> Objects.requireNonNull(value, "policy"))
            .sorted(Comparator.comparing(PolicyDefinition::id))
            .toList();
        requireUnique(
            sorted.stream().map(PolicyDefinition::id).toList(),
            "policy"
        );
        if (sorted.isEmpty()) {
            throw new IllegalArgumentException("policies must not be empty");
        }
        return sorted;
    }

    private static void requireUnique(
        List<String> values,
        String label
    ) {
        Set<String> identities = new HashSet<>();
        for (String value : values) {
            if (!identities.add(value)) {
                throw new IllegalArgumentException(
                    "duplicate " + label + " identity: " + value);
            }
        }
    }

    public record Preregistration(
        String schema,
        String evidenceStatus,
        String formationResource,
        String formationSha256,
        long formationByteLength,
        String qualificationResource,
        String qualificationSha256,
        long qualificationByteLength,
        int configuredCaseCount,
        int configuredPolicyCount,
        int configuredEntryCount,
        String requiredEntryStatus,
        String requiredTerminalReason,
        String claimBoundary
    ) {
        public Preregistration {
            schema = requireText(schema, "schema");
            if (!PREREGISTRATION_SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported preregistration schema: " + schema);
            }
            evidenceStatus = requireText(
                evidenceStatus, "evidenceStatus");
            if (!EVIDENCE_STATUS.equals(evidenceStatus)) {
                throw new IllegalArgumentException(
                    "preregistration must remain FROZEN_NOT_EXECUTED");
            }
            formationResource = requireResourcePath(
                formationResource, "formationResource");
            formationSha256 = requireSha256(
                formationSha256, "formationSha256");
            qualificationResource = requireResourcePath(
                qualificationResource, "qualificationResource");
            qualificationSha256 = requireSha256(
                qualificationSha256, "qualificationSha256");
            if (formationResource.equals(qualificationResource)) {
                throw new IllegalArgumentException(
                    "formation and qualification resources must be separate");
            }
            if (formationByteLength < 1 || qualificationByteLength < 1) {
                throw new IllegalArgumentException(
                    "resource byte lengths must be positive");
            }
            if (configuredCaseCount < 1 || configuredPolicyCount < 1
                    || configuredEntryCount
                        != Math.multiplyExact(
                            configuredCaseCount,
                            configuredPolicyCount)) {
                throw new IllegalArgumentException(
                    "configured matrix does not balance");
            }
            requiredEntryStatus = requireText(
                requiredEntryStatus, "requiredEntryStatus");
            requiredTerminalReason = requireText(
                requiredTerminalReason, "requiredTerminalReason");
            if (!ENTRY_STATUS.equals(requiredEntryStatus)
                    || !TERMINAL_REASON.equals(requiredTerminalReason)) {
                throw new IllegalArgumentException(
                    "preregistration may only describe unexecuted entries");
            }
            claimBoundary = requireText(claimBoundary, "claimBoundary");
        }
    }

    public record Formation(
        String schema,
        String evidenceStatus,
        String informationBoundary,
        List<CaseDefinition> cases,
        List<PolicyDefinition> policies
    ) {
        public Formation {
            schema = requireText(schema, "schema");
            if (!FORMATION_SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported formation schema: " + schema);
            }
            evidenceStatus = requireText(
                evidenceStatus, "evidenceStatus");
            if (!EVIDENCE_STATUS.equals(evidenceStatus)) {
                throw new IllegalArgumentException(
                    "formation must remain FROZEN_NOT_EXECUTED");
            }
            informationBoundary = requireText(
                informationBoundary, "informationBoundary");
            cases = canonicalCases(cases);
            policies = canonicalPolicies(policies);
        }
    }

    public record CaseDefinition(
        String id,
        String sourceExpression,
        List<String> assumptions,
        Track informationTrack,
        WorkBudget budget
    ) {
        public CaseDefinition {
            id = requireText(id, "case id");
            sourceExpression = requireText(
                sourceExpression, "sourceExpression");
            assumptions = sortedStrings(assumptions, "assumptions");
            informationTrack = Objects.requireNonNull(
                informationTrack, "informationTrack");
            if (informationTrack
                    != Track.R1_TARGET_FREE_COMPRESSION
                    && informationTrack
                        != Track.R2_CATALOG_BLIND_POST_HOC_BRIDGE) {
                throw new IllegalArgumentException(
                    "evaluation formation supports only R1 and R2");
            }
            budget = Objects.requireNonNull(budget, "budget");
        }
    }

    public record WorkBudget(
        int maxDepth,
        int maxExploredStates,
        int maxRetainedStates,
        int maxGeneratedTransitions,
        int maxCandidatesPerState,
        int maxAstSizeIncreasePerStep
    ) {
        public WorkBudget {
            if (maxDepth < 0
                    || maxExploredStates < 1
                    || maxRetainedStates < 1
                    || maxGeneratedTransitions < 1
                    || maxCandidatesPerState < 1
                    || maxAstSizeIncreasePerStep < 0) {
                throw new IllegalArgumentException(
                    "work budget is outside its declared finite range");
            }
        }
    }

    public record PolicyDefinition(
        String id,
        String adapter,
        AdapterConstructor adapterConstructor,
        AdapterInterface adapterInterface,
        long deterministicSeed,
        String selectionBoundary
    ) {
        public PolicyDefinition {
            id = requireText(id, "policy id");
            adapter = requireText(adapter, "adapter");
            adapterConstructor = Objects.requireNonNull(
                adapterConstructor,
                "adapterConstructor"
            );
            adapterInterface = Objects.requireNonNull(
                adapterInterface,
                "adapterInterface"
            );
            if (adapterConstructor == AdapterConstructor.NO_ARGUMENT
                    && deterministicSeed != 0) {
                throw new IllegalArgumentException(
                    "no-argument policies must use the zero seed sentinel");
            }
            selectionBoundary = requireText(
                selectionBoundary, "selectionBoundary");
        }
    }

    public record PlanEntry(
        int sequence,
        String configurationId,
        String caseId,
        String policyId,
        String status,
        String terminalReason
    ) {
        public PlanEntry {
            if (sequence < 1) {
                throw new IllegalArgumentException(
                    "entry sequence must be positive");
            }
            configurationId = requireSha256(
                configurationId, "configurationId");
            caseId = requireText(caseId, "caseId");
            policyId = requireText(policyId, "policyId");
            status = requireText(status, "status");
            terminalReason = requireText(
                terminalReason, "terminalReason");
            if (!ENTRY_STATUS.equals(status)
                    || !TERMINAL_REASON.equals(terminalReason)) {
                throw new IllegalArgumentException(
                    "evaluation-plan entries must remain unexecuted");
            }
        }
    }

    public record StatusCount(String status, int count) {
        public StatusCount {
            status = requireText(status, "status");
            if (!ENTRY_STATUS.equals(status) || count < 1) {
                throw new IllegalArgumentException(
                    "status count must describe remaining entries");
            }
        }
    }

    public record PlanContent(
        String schema,
        String evidenceStatus,
        String repositoryRevision,
        String preregistrationResource,
        String preregistrationHash,
        String formationResource,
        String formationHash,
        long formationByteLength,
        String qualificationResource,
        String qualificationHash,
        long qualificationByteLength,
        String qualificationDisclosure,
        String informationBoundary,
        List<CaseDefinition> cases,
        List<PolicyDefinition> policies,
        List<PlanEntry> entries,
        List<StatusCount> statusCounts,
        int configuredCaseCount,
        int configuredPolicyCount,
        int configuredEntryCount,
        String claimBoundary
    ) {
        public PlanContent {
            schema = requireText(schema, "schema");
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported evaluation-plan schema: " + schema);
            }
            evidenceStatus = requireText(
                evidenceStatus, "evidenceStatus");
            if (!EVIDENCE_STATUS.equals(evidenceStatus)) {
                throw new IllegalArgumentException(
                    "evaluation plan must remain FROZEN_NOT_EXECUTED");
            }
            repositoryRevision = requireRepositoryRevision(
                repositoryRevision);
            preregistrationResource = requireResourcePath(
                preregistrationResource, "preregistrationResource");
            if (!PREREGISTRATION_RESOURCE.equals(
                    preregistrationResource)) {
                throw new IllegalArgumentException(
                    "unexpected preregistration resource");
            }
            preregistrationHash = requireSha256(
                preregistrationHash, "preregistrationHash");
            formationResource = requireResourcePath(
                formationResource, "formationResource");
            formationHash = requireSha256(
                formationHash, "formationHash");
            qualificationResource = requireResourcePath(
                qualificationResource, "qualificationResource");
            qualificationHash = requireSha256(
                qualificationHash, "qualificationHash");
            if (formationResource.equals(qualificationResource)
                    || formationByteLength < 1
                    || qualificationByteLength < 1) {
                throw new IllegalArgumentException(
                    "resource bindings are invalid");
            }
            qualificationDisclosure = requireText(
                qualificationDisclosure, "qualificationDisclosure");
            if (!QUALIFICATION_DISCLOSURE.equals(
                    qualificationDisclosure)) {
                throw new IllegalArgumentException(
                    "qualification must remain undisclosed");
            }
            informationBoundary = requireText(
                informationBoundary, "informationBoundary");
            cases = canonicalCases(cases);
            policies = canonicalPolicies(policies);
            if (configuredCaseCount != cases.size()
                    || configuredPolicyCount != policies.size()
                    || configuredEntryCount
                        != Math.multiplyExact(
                            configuredCaseCount,
                            configuredPolicyCount)) {
                throw new IllegalArgumentException(
                    "evaluation-plan matrix counts do not balance");
            }
            entries = List.copyOf(
                Objects.requireNonNull(entries, "entries"));
            List<PlanEntry> expected = new ArrayList<>();
            int sequence = 1;
            for (CaseDefinition benchmarkCase : cases) {
                for (PolicyDefinition policy : policies) {
                    expected.add(new PlanEntry(
                        sequence++,
                        configurationId(
                            preregistrationHash,
                            benchmarkCase.id(),
                            policy.id()
                        ),
                        benchmarkCase.id(),
                        policy.id(),
                        ENTRY_STATUS,
                        TERMINAL_REASON
                    ));
                }
            }
            if (!expected.equals(entries)
                    || entries.size() != configuredEntryCount) {
                throw new IllegalArgumentException(
                    "evaluation-plan entries are not the exact matrix");
            }
            statusCounts = List.copyOf(
                Objects.requireNonNull(statusCounts, "statusCounts"));
            if (!statusCounts.equals(List.of(
                    new StatusCount(
                        ENTRY_STATUS,
                        configuredEntryCount)))) {
                throw new IllegalArgumentException(
                    "evaluation-plan status counts do not balance");
            }
            claimBoundary = requireText(
                claimBoundary, "claimBoundary");
        }
    }

    public record EvaluationPlan(
        PlanContent content,
        String contentHash
    ) {
        public EvaluationPlan {
            content = Objects.requireNonNull(content, "content");
            contentHash = requireSha256(contentHash, "contentHash");
            String expected = sha256(
                canonical(content).getBytes(StandardCharsets.UTF_8));
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "evaluation-plan content hash mismatch");
            }
        }

        public static EvaluationPlan create(PlanContent content) {
            Objects.requireNonNull(content, "content");
            return new EvaluationPlan(
                content,
                sha256(canonical(content).getBytes(
                    StandardCharsets.UTF_8))
            );
        }

        public String toCanonicalJson() {
            return canonical(this);
        }

        public static EvaluationPlan fromCanonicalJson(String source) {
            String json = Objects.requireNonNull(source, "source");
            try {
                EvaluationPlan plan = JSON.readValue(
                    json,
                    EvaluationPlan.class
                );
                if (!plan.toCanonicalJson().equals(json)) {
                    throw new IllegalArgumentException(
                        "evaluation-plan JSON is not canonical");
                }
                return plan;
            } catch (JsonProcessingException exception) {
                throw new IllegalArgumentException(
                    "invalid representation-discovery evaluation plan",
                    exception
                );
            }
        }
    }

    public enum AdapterConstructor {
        NO_ARGUMENT,
        LONG_SEED
    }

    public enum AdapterInterface {
        TARGET_FREE_REPRESENTATION_SEARCH,
        SEARCH_STRATEGY
    }
}
