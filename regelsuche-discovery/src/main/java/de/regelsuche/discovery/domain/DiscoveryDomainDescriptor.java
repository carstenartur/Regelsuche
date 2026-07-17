package de.regelsuche.discovery.domain;

import de.regelsuche.json.JsonWriter;
import java.util.List;
import java.util.Objects;

/** Canonical declaration of one discovery domain's pluggable semantic roles. */
public final class DiscoveryDomainDescriptor {
    public static final String SCHEMA = "regelsuche.discovery-domain-descriptor/v1";
    private static final List<String> SEMANTIC_ROLES = List.of(
        "GENERATION",
        "CANONICALIZATION",
        "INVARIANT_CHECKING",
        "SEARCH",
        "CANDIDATE_FORMATION",
        "COUNTEREXAMPLE_SEARCH",
        "VALIDATION",
        "CERTIFICATE_RENDERING",
        "EVIDENCE_ADAPTATION"
    );

    private final String domainId;
    private final String revision;
    private final String stateType;
    private final String candidateType;
    private final String certificateType;
    private final String generatorId;
    private final String stateCodecId;
    private final List<String> operatorIds;
    private final List<String> invariantIds;
    private final String objectiveId;
    private final String candidateExtractorId;
    private final String candidateCodecId;
    private final String counterexampleGeneratorId;
    private final String evaluatorId;
    private final String certificateCodecId;
    private final String certificateRendererId;
    private final String evidenceAdapterId;
    private final boolean deterministic;
    private final String contentHash;

    private DiscoveryDomainDescriptor(
        String domainId,
        String revision,
        String stateType,
        String candidateType,
        String certificateType,
        String generatorId,
        String stateCodecId,
        List<String> operatorIds,
        List<String> invariantIds,
        String objectiveId,
        String candidateExtractorId,
        String candidateCodecId,
        String counterexampleGeneratorId,
        String evaluatorId,
        String certificateCodecId,
        String certificateRendererId,
        String evidenceAdapterId,
        boolean deterministic
    ) {
        this.domainId = DomainCanonical.requireIdentifier(domainId, "domainId");
        this.revision = DomainCanonical.requireIdentifier(revision, "revision");
        this.stateType = DomainCanonical.requireIdentifier(stateType, "stateType");
        this.candidateType = DomainCanonical.requireIdentifier(candidateType, "candidateType");
        this.certificateType = DomainCanonical.requireIdentifier(certificateType, "certificateType");
        this.generatorId = DomainCanonical.requireIdentifier(generatorId, "generatorId");
        this.stateCodecId = DomainCanonical.requireIdentifier(stateCodecId, "stateCodecId");
        this.operatorIds = DomainCanonical.sortedDistinct(operatorIds);
        this.invariantIds = DomainCanonical.sortedDistinct(invariantIds);
        this.objectiveId = DomainCanonical.requireIdentifier(objectiveId, "objectiveId");
        this.candidateExtractorId = DomainCanonical.requireIdentifier(
            candidateExtractorId, "candidateExtractorId");
        this.candidateCodecId = DomainCanonical.requireIdentifier(
            candidateCodecId, "candidateCodecId");
        this.counterexampleGeneratorId = DomainCanonical.requireIdentifier(
            counterexampleGeneratorId, "counterexampleGeneratorId");
        this.evaluatorId = DomainCanonical.requireIdentifier(evaluatorId, "evaluatorId");
        this.certificateCodecId = DomainCanonical.requireIdentifier(
            certificateCodecId, "certificateCodecId");
        this.certificateRendererId = DomainCanonical.requireIdentifier(
            certificateRendererId, "certificateRendererId");
        this.evidenceAdapterId = DomainCanonical.requireIdentifier(
            evidenceAdapterId, "evidenceAdapterId");
        this.deterministic = deterministic;
        if (this.operatorIds.isEmpty()) {
            throw new IllegalArgumentException("a discovery domain requires an operator");
        }
        if (this.invariantIds.isEmpty()) {
            throw new IllegalArgumentException("a discovery domain requires an invariant");
        }
        this.contentHash = DomainCanonical.sha256(render(false));
    }

    public static DiscoveryDomainDescriptor from(DiscoveryDomain<?, ?, ?> domain) {
        Objects.requireNonNull(domain, "domain");
        return new DiscoveryDomainDescriptor(
            domain.domainId(),
            domain.revision(),
            domain.stateType(),
            domain.candidateType(),
            domain.certificateType(),
            domain.generator().id(),
            domain.stateCodec().id(),
            domain.operators().stream()
                .map(DiscoveryDomain.TransitionOperator::id)
                .toList(),
            domain.invariants().stream()
                .map(DiscoveryDomain.Invariant::id)
                .toList(),
            domain.objective().id(),
            domain.candidateExtractor().id(),
            domain.candidateCodec().id(),
            domain.counterexampleGenerator().id(),
            domain.evaluator().id(),
            domain.certificateCodec().id(),
            domain.certificateRenderer().id(),
            domain.evidenceAdapter().id(),
            true
        );
    }

    public String schema() {
        return SCHEMA;
    }

    public String domainId() {
        return domainId;
    }

    public String revision() {
        return revision;
    }

    public String stateType() {
        return stateType;
    }

    public String candidateType() {
        return candidateType;
    }

    public String certificateType() {
        return certificateType;
    }

    public String generatorId() {
        return generatorId;
    }

    public String stateCodecId() {
        return stateCodecId;
    }

    public List<String> operatorIds() {
        return operatorIds;
    }

    public List<String> invariantIds() {
        return invariantIds;
    }

    public String objectiveId() {
        return objectiveId;
    }

    public String candidateExtractorId() {
        return candidateExtractorId;
    }

    public String candidateCodecId() {
        return candidateCodecId;
    }

    public String counterexampleGeneratorId() {
        return counterexampleGeneratorId;
    }

    public String evaluatorId() {
        return evaluatorId;
    }

    public String certificateCodecId() {
        return certificateCodecId;
    }

    public String certificateRendererId() {
        return certificateRendererId;
    }

    public String evidenceAdapterId() {
        return evidenceAdapterId;
    }

    public boolean deterministic() {
        return deterministic;
    }

    public List<String> semanticRoles() {
        return SEMANTIC_ROLES;
    }

    public String contentHash() {
        return contentHash;
    }

    public String toCanonicalJson() {
        return render(true);
    }

    void writeTo(JsonWriter json) {
        writeTo(json, true);
    }

    private void writeTo(JsonWriter json, boolean includeHash) {
        json.property("schema", SCHEMA)
            .property("domainId", domainId)
            .property("revision", revision)
            .property("stateType", stateType)
            .property("candidateType", candidateType)
            .property("certificateType", certificateType)
            .property("generatorId", generatorId)
            .property("stateCodecId", stateCodecId)
            .stringArray("operatorIds", operatorIds)
            .stringArray("invariantIds", invariantIds)
            .property("objectiveId", objectiveId)
            .property("candidateExtractorId", candidateExtractorId)
            .property("candidateCodecId", candidateCodecId)
            .property("counterexampleGeneratorId", counterexampleGeneratorId)
            .property("evaluatorId", evaluatorId)
            .property("certificateCodecId", certificateCodecId)
            .property("certificateRendererId", certificateRendererId)
            .property("evidenceAdapterId", evidenceAdapterId)
            .property("deterministic", deterministic)
            .stringArray("semanticRoles", SEMANTIC_ROLES);
        if (includeHash) {
            json.property("contentHash", contentHash);
        }
    }

    private String render(boolean includeHash) {
        JsonWriter json = new JsonWriter().beginObject();
        writeTo(json, includeHash);
        return json.endObject().toString();
    }
}
