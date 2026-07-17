package de.regelsuche.discovery.domain;

import de.regelsuche.json.JsonWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Hash-only handoff from discovery execution into later lifecycle stages.
 * Domain objects remain in their schema-specific source evidence and are bound
 * through {@code sourceEvidenceHash} rather than copied into this contract.
 */
public record DiscoveryLifecycleHandoff(
    String schema,
    String handoffId,
    SourceKind sourceKind,
    String campaignId,
    String domainId,
    String domainRevision,
    String domainContractHash,
    String inputHash,
    String sourceEvidenceHash,
    Stage stage,
    Disposition disposition,
    String selectedCandidateHash,
    String certificateHash,
    List<ResourceAccount> resources,
    Map<String, String> metadata,
    String proofStatus,
    String externalNoveltyStatus,
    String promotionStatus,
    String publicEvidenceStatus,
    String contentHash
) {
    public static final String SCHEMA = "regelsuche.discovery-lifecycle-handoff/v1";
    public static final String NOT_EVALUATED = "NOT_EVALUATED";

    public DiscoveryLifecycleHandoff {
        requireSchema(schema);
        handoffId = DomainCanonical.requireIdentifier(handoffId, "handoffId");
        Objects.requireNonNull(sourceKind, "sourceKind");
        campaignId = DomainCanonical.requireIdentifier(campaignId, "campaignId");
        domainId = DomainCanonical.requireIdentifier(domainId, "domainId");
        domainRevision = DomainCanonical.requireIdentifier(
            domainRevision, "domainRevision");
        domainContractHash = DomainCanonical.requireSha256(
            domainContractHash, "domainContractHash");
        inputHash = DomainCanonical.requireSha256(inputHash, "inputHash");
        sourceEvidenceHash = DomainCanonical.requireSha256(
            sourceEvidenceHash, "sourceEvidenceHash");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(disposition, "disposition");
        selectedCandidateHash = optionalSha256(
            selectedCandidateHash, "selectedCandidateHash");
        certificateHash = optionalSha256(certificateHash, "certificateHash");
        resources = normalizeResources(resources);
        metadata = DomainCanonical.sortedMap(metadata);
        requireNotEvaluated(proofStatus, "proofStatus");
        requireNotEvaluated(externalNoveltyStatus, "externalNoveltyStatus");
        requireNotEvaluated(promotionStatus, "promotionStatus");
        requireNotEvaluated(publicEvidenceStatus, "publicEvidenceStatus");
        validateSemantics(
            sourceKind,
            stage,
            disposition,
            selectedCandidateHash,
            certificateHash,
            resources);
        contentHash = DomainCanonical.requireSha256(contentHash, "contentHash");
        String expected = contentHash(
            handoffId,
            sourceKind,
            campaignId,
            domainId,
            domainRevision,
            domainContractHash,
            inputHash,
            sourceEvidenceHash,
            stage,
            disposition,
            selectedCandidateHash,
            certificateHash,
            resources,
            metadata,
            proofStatus,
            externalNoveltyStatus,
            promotionStatus,
            publicEvidenceStatus);
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "discovery lifecycle handoff contentHash mismatch");
        }
    }

    /** Creates a representation-free handoff from generic domain evidence. */
    public static DiscoveryLifecycleHandoff from(DomainDiscoveryEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        DiscoveryDomainDescriptor descriptor = evidence.descriptor();
        List<ResourceAccount> accounts = evidence.resources().stream()
            .map(line -> new ResourceAccount(
                line.resource().name(),
                line.configured(),
                line.executed(),
                line.skipped(),
                line.remaining()))
            .toList();
        String retainedCertificate = evidence.certificate() == null
            ? ""
            : evidence.certificate().contentHash();
        String domainPayloadHash = DomainCanonical.sha256(
            DomainCanonical.canonicalMap(Map.of(
                "type", evidence.domainEvidence().type(),
                "properties", DomainCanonical.canonicalMap(
                    evidence.domainEvidence().properties()))));
        Map<String, String> metadata = Map.ofEntries(
            Map.entry("candidateAttemptCount",
                Integer.toString(evidence.candidateAttempts().size())),
            Map.entry("domainEvidenceHash", domainPayloadHash),
            Map.entry("domainEvidenceType", evidence.domainEvidence().type()),
            Map.entry("executionOutcome", evidence.outcome().name()),
            Map.entry("stateTraceCount", Integer.toString(evidence.states().size())),
            Map.entry("transitionTraceCount",
                Integer.toString(evidence.transitions().size())));
        return create(
            evidence.campaignId() + "-lifecycle-handoff",
            SourceKind.DOMAIN_DISCOVERY_EVIDENCE,
            evidence.campaignId(),
            descriptor.domainId(),
            descriptor.revision(),
            descriptor.contentHash(),
            evidence.seed().contentHash(),
            evidence.contentHash(),
            Stage.DISCOVERY_VALIDATION,
            disposition(evidence.outcome()),
            evidence.selectedCandidateHash(),
            retainedCertificate,
            accounts,
            metadata,
            evidence.proofStatus(),
            evidence.externalNoveltyStatus(),
            evidence.promotionStatus(),
            evidence.publicEvidenceStatus());
    }

    /** Creates a handoff for an existing immutable execution contract. */
    public static DiscoveryLifecycleHandoff create(
        String handoffId,
        SourceKind sourceKind,
        String campaignId,
        String domainId,
        String domainRevision,
        String domainContractHash,
        String inputHash,
        String sourceEvidenceHash,
        Stage stage,
        Disposition disposition,
        String selectedCandidateHash,
        String certificateHash,
        List<ResourceAccount> resources,
        Map<String, String> metadata,
        String proofStatus,
        String externalNoveltyStatus,
        String promotionStatus,
        String publicEvidenceStatus
    ) {
        String normalizedHandoffId = DomainCanonical.requireIdentifier(
            handoffId, "handoffId");
        Objects.requireNonNull(sourceKind, "sourceKind");
        String normalizedCampaignId = DomainCanonical.requireIdentifier(
            campaignId, "campaignId");
        String normalizedDomainId = DomainCanonical.requireIdentifier(
            domainId, "domainId");
        String normalizedRevision = DomainCanonical.requireIdentifier(
            domainRevision, "domainRevision");
        String normalizedContractHash = DomainCanonical.requireSha256(
            domainContractHash, "domainContractHash");
        String normalizedInputHash = DomainCanonical.requireSha256(
            inputHash, "inputHash");
        String normalizedSourceHash = DomainCanonical.requireSha256(
            sourceEvidenceHash, "sourceEvidenceHash");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(disposition, "disposition");
        String normalizedCandidateHash = optionalSha256(
            selectedCandidateHash, "selectedCandidateHash");
        String normalizedCertificateHash = optionalSha256(
            certificateHash, "certificateHash");
        List<ResourceAccount> normalizedResources = normalizeResources(resources);
        Map<String, String> normalizedMetadata = DomainCanonical.sortedMap(metadata);
        requireNotEvaluated(proofStatus, "proofStatus");
        requireNotEvaluated(externalNoveltyStatus, "externalNoveltyStatus");
        requireNotEvaluated(promotionStatus, "promotionStatus");
        requireNotEvaluated(publicEvidenceStatus, "publicEvidenceStatus");
        validateSemantics(
            sourceKind,
            stage,
            disposition,
            normalizedCandidateHash,
            normalizedCertificateHash,
            normalizedResources);
        String hash = contentHash(
            normalizedHandoffId,
            sourceKind,
            normalizedCampaignId,
            normalizedDomainId,
            normalizedRevision,
            normalizedContractHash,
            normalizedInputHash,
            normalizedSourceHash,
            stage,
            disposition,
            normalizedCandidateHash,
            normalizedCertificateHash,
            normalizedResources,
            normalizedMetadata,
            proofStatus,
            externalNoveltyStatus,
            promotionStatus,
            publicEvidenceStatus);
        return new DiscoveryLifecycleHandoff(
            SCHEMA,
            normalizedHandoffId,
            sourceKind,
            normalizedCampaignId,
            normalizedDomainId,
            normalizedRevision,
            normalizedContractHash,
            normalizedInputHash,
            normalizedSourceHash,
            stage,
            disposition,
            normalizedCandidateHash,
            normalizedCertificateHash,
            normalizedResources,
            normalizedMetadata,
            proofStatus,
            externalNoveltyStatus,
            promotionStatus,
            publicEvidenceStatus,
            hash);
    }

    public String toCanonicalJson() {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", schema)
            .property("handoffId", handoffId)
            .property("sourceKind", sourceKind.name())
            .property("campaignId", campaignId)
            .property("domainId", domainId)
            .property("domainRevision", domainRevision)
            .property("domainContractHash", domainContractHash)
            .property("inputHash", inputHash)
            .property("sourceEvidenceHash", sourceEvidenceHash)
            .property("stage", stage.name())
            .property("disposition", disposition.name())
            .property("selectedCandidateHash", selectedCandidateHash)
            .property("certificateHash", certificateHash)
            .array("resources", array -> resources.forEach(account ->
                array.objectValue(object -> object
                    .property("resource", account.resource())
                    .property("configured", account.configured())
                    .property("executed", account.executed())
                    .property("skipped", account.skipped())
                    .property("remaining", account.remaining()))))
            .array("metadata", array -> metadata.forEach((key, value) ->
                array.objectValue(object -> object
                    .property("key", key)
                    .property("value", value))))
            .property("proofStatus", proofStatus)
            .property("externalNoveltyStatus", externalNoveltyStatus)
            .property("promotionStatus", promotionStatus)
            .property("publicEvidenceStatus", publicEvidenceStatus)
            .property("contentHash", contentHash);
        return json.endObject().toString();
    }

    private static Disposition disposition(DomainDiscoveryEvidence.Outcome outcome) {
        return switch (outcome) {
            case CONFIRMED -> Disposition.CONFIRMED;
            case REFUTED -> Disposition.REFUTED;
            case INCONCLUSIVE -> Disposition.INCOMPLETE;
            case UNSUPPORTED -> Disposition.UNSUPPORTED;
            case BUDGET_EXHAUSTED -> Disposition.BUDGET_EXHAUSTED;
            case INVALID_SEED -> Disposition.INVALID_INPUT;
        };
    }

    private static String contentHash(
        String handoffId,
        SourceKind sourceKind,
        String campaignId,
        String domainId,
        String domainRevision,
        String domainContractHash,
        String inputHash,
        String sourceEvidenceHash,
        Stage stage,
        Disposition disposition,
        String selectedCandidateHash,
        String certificateHash,
        List<ResourceAccount> resources,
        Map<String, String> metadata,
        String proofStatus,
        String externalNoveltyStatus,
        String promotionStatus,
        String publicEvidenceStatus
    ) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("schema", SCHEMA);
        values.put("handoffId", handoffId);
        values.put("sourceKind", sourceKind.name());
        values.put("campaignId", campaignId);
        values.put("domainId", domainId);
        values.put("domainRevision", domainRevision);
        values.put("domainContractHash", domainContractHash);
        values.put("inputHash", inputHash);
        values.put("sourceEvidenceHash", sourceEvidenceHash);
        values.put("stage", stage.name());
        values.put("disposition", disposition.name());
        values.put("selectedCandidateHash", selectedCandidateHash);
        values.put("certificateHash", certificateHash);
        values.put("resources", DomainCanonical.canonicalList(resources.stream()
            .map(ResourceAccount::canonicalMaterial)
            .toList()));
        values.put("metadata", DomainCanonical.canonicalMap(metadata));
        values.put("proofStatus", proofStatus);
        values.put("externalNoveltyStatus", externalNoveltyStatus);
        values.put("promotionStatus", promotionStatus);
        values.put("publicEvidenceStatus", publicEvidenceStatus);
        return DomainCanonical.sha256(DomainCanonical.canonicalMap(values));
    }

    private static List<ResourceAccount> normalizeResources(
        List<ResourceAccount> values
    ) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(
                "lifecycle handoff requires resource accounting");
        }
        Set<String> names = new HashSet<>();
        List<ResourceAccount> normalized = new ArrayList<>();
        for (ResourceAccount value : values) {
            ResourceAccount account = Objects.requireNonNull(
                value, "resource account");
            if (!names.add(account.resource())) {
                throw new IllegalArgumentException(
                    "duplicate lifecycle resource: " + account.resource());
            }
            normalized.add(account);
        }
        normalized.sort(Comparator.comparing(ResourceAccount::resource));
        return List.copyOf(normalized);
    }

    private static void validateSemantics(
        SourceKind sourceKind,
        Stage stage,
        Disposition disposition,
        String selectedCandidateHash,
        String certificateHash,
        List<ResourceAccount> resources
    ) {
        resources.forEach(ResourceAccount::validateBalance);
        if (sourceKind == SourceKind.DOMAIN_DISCOVERY_EVIDENCE
                && stage != Stage.DISCOVERY_VALIDATION) {
            throw new IllegalArgumentException(
                "generic domain evidence must use DISCOVERY_VALIDATION stage");
        }
        if (sourceKind == SourceKind.PRODUCTION_GENERATION_RUN
                && stage != Stage.GENERATION) {
            throw new IllegalArgumentException(
                "production generation evidence must use GENERATION stage");
        }
        if (disposition == Disposition.COMPLETED && stage != Stage.GENERATION) {
            throw new IllegalArgumentException(
                "COMPLETED disposition is reserved for generation handoffs");
        }
        if (stage == Stage.GENERATION) {
            if (disposition != Disposition.COMPLETED
                    || !selectedCandidateHash.isEmpty()
                    || !certificateHash.isEmpty()) {
                throw new IllegalArgumentException(
                    "generation handoff cannot select or certify a candidate");
            }
            return;
        }
        if (disposition == Disposition.CONFIRMED) {
            if (selectedCandidateHash.isEmpty() || certificateHash.isEmpty()) {
                throw new IllegalArgumentException(
                    "confirmed discovery handoff requires candidate and certificate hashes");
            }
        } else if (!selectedCandidateHash.isEmpty() || !certificateHash.isEmpty()) {
            throw new IllegalArgumentException(
                "non-confirmed discovery handoff cannot retain selected evidence");
        }
    }

    private static String optionalSha256(String value, String field) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return DomainCanonical.requireSha256(value, field);
    }

    private static void requireSchema(String value) {
        if (!SCHEMA.equals(value)) {
            throw new IllegalArgumentException(
                "unsupported discovery lifecycle handoff schema");
        }
    }

    private static void requireNotEvaluated(String value, String field) {
        if (!NOT_EVALUATED.equals(value)) {
            throw new IllegalArgumentException(field + " must be NOT_EVALUATED");
        }
    }

    public enum SourceKind {
        DOMAIN_DISCOVERY_EVIDENCE,
        PRODUCTION_GENERATION_RUN
    }

    public enum Stage {
        GENERATION,
        DISCOVERY_VALIDATION
    }

    public enum Disposition {
        COMPLETED,
        CONFIRMED,
        REFUTED,
        INCOMPLETE,
        UNSUPPORTED,
        BUDGET_EXHAUSTED,
        INVALID_INPUT
    }

    public record ResourceAccount(
        String resource,
        long configured,
        long executed,
        long skipped,
        long remaining
    ) {
        public ResourceAccount {
            resource = DomainCanonical.requireIdentifier(resource, "resource");
            if (configured < 0L
                    || executed < 0L
                    || skipped < 0L
                    || remaining < 0L) {
                throw new IllegalArgumentException(
                    "resource counts must be non-negative");
            }
            validateBalance(configured, executed, skipped, remaining, resource);
        }

        public void validateBalance() {
            validateBalance(configured, executed, skipped, remaining, resource);
        }

        String canonicalMaterial() {
            return DomainCanonical.canonicalMap(Map.of(
                "resource", resource,
                "configured", Long.toString(configured),
                "executed", Long.toString(executed),
                "skipped", Long.toString(skipped),
                "remaining", Long.toString(remaining)));
        }

        private static void validateBalance(
            long configured,
            long executed,
            long skipped,
            long remaining,
            String resource
        ) {
            long accounted = Math.addExact(
                Math.addExact(executed, skipped), remaining);
            if (configured != accounted) {
                throw new IllegalArgumentException(
                    "unbalanced lifecycle resource " + resource);
            }
        }
    }
}
