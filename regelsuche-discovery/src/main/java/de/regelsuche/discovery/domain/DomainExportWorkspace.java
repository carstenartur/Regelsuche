package de.regelsuche.discovery.domain;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.discovery.domain.DiscoveryDomain.*;
import de.regelsuche.discovery.domain.DomainDiscoveryEvidence.*;
import de.regelsuche.discovery.domain.DomainDiscoveryExport.ArtifactRole;
import de.regelsuche.discovery.domain.DomainDiscoveryExportVerifier.VerifiedDomainExport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * Supported application view over one existing verified export identity.
 * Source bytes are immutable; loading performs no search. The optional bounded
 * replay repeats the recorded execution and does not issue proof or novelty.
 */
public final class DomainExportWorkspace {
    public static final String SCHEMA = "regelsuche.domain-export-workspace/v1";
    public static final String REPLAY_SCHEMA = "regelsuche.domain-export-replay/v1";
    public static final int MAX_EXPORT_BYTES = 524_288;
    private static final DiscoveryBudget REPLAY_CEILING = new DiscoveryBudget(8, 256, 1024, 128, 128, 4096);
    static final JsonMapper JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final VerifiedDomainExport snapshot;
    private final DomainDiscoveryEvidence evidence;
    private final String canonicalJson;
    private final String contentHash;

    private DomainExportWorkspace(VerifiedDomainExport snapshot, DomainDiscoveryEvidence evidence) {
        this.snapshot = snapshot;
        this.evidence = evidence;
        ObjectNode view = JSON.createObjectNode();
        view.put("schema", SCHEMA).put("runId", runId()).put("domainId", evidence.descriptor().domainId())
            .put("domainRevision", evidence.descriptor().revision()).put("inputHash", evidence.seed().contentHash());
        view.set("manifest", read(snapshot.manifest().toCanonicalJson()));
        view.set("verification", read(snapshot.verification().toCanonicalJson()));
        view.set("evidence", read(evidence.toCanonicalJson()));
        view.set("lifecycleHandoff", read(snapshot.artifactBytes(ArtifactRole.LIFECYCLE_HANDOFF)));
        ArrayNode roles = view.putArray("artifacts");
        available(roles, "DOMAIN_DESCRIPTOR", DiscoveryDomainDescriptor.SCHEMA, snapshot.manifest().domainDescriptorHash());
        for (String role : List.of("SEARCH_GRAPH", "REPRESENTATION_CANDIDATES", "CANDIDATE_DOSSIERS", "PROGRESS_LEDGER")) {
            available(roles, role, DomainDiscoveryEvidence.SCHEMA, evidence.contentHash());
        }
        available(roles, "LIFECYCLE_HANDOFF", DiscoveryLifecycleHandoff.SCHEMA, snapshot.manifest().lifecycleHandoffHash());
        available(roles, "EXPORT_BUNDLE", DomainDiscoveryExport.SCHEMA, runId());
        if (replaySupported()) available(roles, "PATH_REPLAY", DomainDiscoveryEvidence.SCHEMA, evidence.contentHash());
        else unavailable(roles, "PATH_REPLAY", "SOURCE_EXCEEDS_BOUNDED_REPLAY_ADMISSION");
        for (String role : List.of("RULE_RADAR", "PROOF_OBLIGATIONS", "EXTERNAL_NOVELTY", "PROMOTION", "PUBLIC_EVIDENCE")) {
            unavailable(roles, role, "NOT_PRODUCED_BY_SOURCE_EXPORT");
        }
        view.put("replaySupported", replaySupported());
        view.put("claimBoundary", "Retained finite-sequence validation evidence and bounded deterministic replay; "
            + "not formal proof, external novelty, promotion, Public Evidence or domain-generic qualification.");
        this.contentHash = DomainCanonical.sha256(write(view));
        view.put("contentHash", contentHash);
        this.canonicalJson = write(view);
    }

    public static DomainExportWorkspace fromVerified(VerifiedDomainExport snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        long size = snapshot.manifestBytes().length;
        for (ArtifactRole role : ArtifactRole.values()) size += snapshot.artifactBytes(role).length;
        if (size > MAX_EXPORT_BYTES) throw new IllegalArgumentException("domain export exceeds 512 KiB application limit");
        if (!FiniteDifferenceSequenceDomain.DOMAIN_ID.equals(snapshot.manifest().domainId())
                || !FiniteDifferenceSequenceDomain.REVISION.equals(snapshot.manifest().domainRevision())) {
            throw new IllegalArgumentException("unsupported domain export schema or revision");
        }
        var domain = new FiniteDifferenceSequenceDomain();
        var descriptor = DiscoveryDomainDescriptor.from(domain);
        if (!read(descriptor.toCanonicalJson()).equals(read(snapshot.artifactBytes(ArtifactRole.DOMAIN_DESCRIPTOR)))) {
            throw new IllegalArgumentException("retained descriptor differs from the supported domain contract");
        }
        try {
            JsonNode raw = read(snapshot.artifactBytes(ArtifactRole.DISCOVERY_EVIDENCE));
            DomainDiscoveryEvidence evidence = new DomainDiscoveryEvidence(raw.path("campaignId").asText(), descriptor,
                JSON.treeToValue(raw.required("seed"), DiscoverySeed.class),
                JSON.treeToValue(raw.required("budget"), DiscoveryBudget.class), Outcome.valueOf(raw.required("outcome").asText()),
                records(raw.required("states"), StateTrace.class, "objectiveMetrics"),
                records(raw.required("transitions"), TransitionTrace.class, "metadata"),
                records(raw.required("candidateAttempts"), CandidateAttempt.class, "metrics"),
                records(raw.required("resources"), ResourceLine.class, null),
                raw.required("selectedCandidateHash").isNull() ? "" : raw.required("selectedCandidateHash").asText(),
                raw.required("certificate").isNull() ? null : JSON.treeToValue(raw.required("certificate"), RenderedCertificate.class),
                JSON.treeToValue(mapField(raw.required("domainEvidence"), "properties"), DomainPayload.class));
            if (!read(evidence.toCanonicalJson()).equals(raw)) {
                throw new IllegalArgumentException("retained evidence does not match its typed canonical content and source hash");
            }
            if (!read(DiscoveryLifecycleHandoff.from(evidence).toCanonicalJson())
                    .equals(read(snapshot.artifactBytes(ArtifactRole.LIFECYCLE_HANDOFF)))) {
                throw new IllegalArgumentException("retained lifecycle handoff differs from source evidence");
            }
            validateResources(evidence);
            return new DomainExportWorkspace(snapshot, evidence);
        } catch (IOException | NullPointerException exception) {
            throw new IllegalArgumentException("malformed supported domain evidence", exception);
        }
    }

    private static void validateResources(DomainDiscoveryEvidence evidence) {
        EnumSet<Resource> roles = EnumSet.noneOf(Resource.class);
        var budget = evidence.budget();
        for (ResourceLine line : evidence.resources()) {
            int configured = switch (line.resource()) {
                case EXPLORED_STATES -> budget.maxExploredStates();
                case GENERATED_SUCCESSORS -> budget.maxGeneratedSuccessors();
                case CANDIDATE_EVALUATIONS, CERTIFICATE_ATTEMPTS -> budget.maxCandidateAttempts();
                case COUNTEREXAMPLE_ATTEMPTS -> budget.maxCounterexampleAttempts();
            };
            if (!roles.add(line.resource()) || line.configured() != configured
                    || (long) line.executed() + line.skipped() + line.remaining() != configured) {
                throw new IllegalArgumentException("source resources do not match the recorded budget");
            }
        }
        if (!roles.equals(EnumSet.allOf(Resource.class))) throw new IllegalArgumentException("source resource dimensions are incomplete");
    }

    public DomainDiscoveryEvidence replay() {
        if (!replaySupported()) throw new IllegalStateException("source exceeds bounded replay admission");
        DomainDiscoveryEvidence replayed = new DomainDiscoveryRunner().run(evidence.campaignId(),
            new FiniteDifferenceSequenceDomain(), evidence.seed(), evidence.budget()).evidence();
        if (!evidence.toCanonicalJson().equals(replayed.toCanonicalJson())) {
            throw new IllegalStateException("replay differs from the retained source evidence");
        }
        return replayed;
    }

    public boolean replaySupported() {
        var b = evidence.budget();
        return evidence.seed().payload().length() <= 8192 && b.maxDepth() <= REPLAY_CEILING.maxDepth()
            && b.maxExploredStates() <= REPLAY_CEILING.maxExploredStates()
            && b.maxGeneratedSuccessors() <= REPLAY_CEILING.maxGeneratedSuccessors()
            && b.maxCandidatesPerState() <= REPLAY_CEILING.maxCandidatesPerState()
            && b.maxCandidateAttempts() <= REPLAY_CEILING.maxCandidateAttempts()
            && b.maxCounterexampleAttempts() <= REPLAY_CEILING.maxCounterexampleAttempts();
    }

    public String runId() { return snapshot.manifest().contentHash(); }
    public String contentHash() { return contentHash; }
    public String toCanonicalJson() { return canonicalJson; }
    public DomainDiscoveryEvidence evidence() { return evidence; }
    public byte[] originalManifestBytes() { return snapshot.manifestBytes(); }
    public byte[] originalArtifactBytes(ArtifactRole role) { return snapshot.artifactBytes(role); }

    private static void available(ArrayNode roles, String role, String schema, String hash) {
        roles.addObject().put("role", role).put("status", "AVAILABLE").put("artifactSchema", schema).put("targetContentHash", hash).put("detail", "");
    }

    private static void unavailable(ArrayNode roles, String role, String reason) {
        roles.addObject().put("role", role).put("status", "NOT_PRODUCED").put("artifactSchema", "NOT_AVAILABLE").putNull("targetContentHash").put("detail", reason);
    }

    private static <T> List<T> records(JsonNode values, Class<T> type, String mapField) throws IOException {
        if (!values.isArray()) throw new IllegalArgumentException("expected an evidence array");
        List<T> records = new ArrayList<>();
        for (JsonNode item : values) records.add(JSON.treeToValue(mapField == null ? item : mapField(item, mapField), type));
        return List.copyOf(records);
    }

    private static ObjectNode mapField(JsonNode raw, String field) {
        if (!raw.isObject() || !raw.required(field).isArray()) throw new IllegalArgumentException("invalid evidence map");
        ObjectNode value = ((ObjectNode) raw).deepCopy(), map = JSON.createObjectNode();
        for (JsonNode entry : raw.required(field)) {
            if (!entry.isObject() || entry.size() != 2 || !entry.path("key").isTextual() || !entry.path("value").isTextual()
                    || map.has(entry.path("key").asText())) throw new IllegalArgumentException("invalid or duplicate evidence map entry");
            map.put(entry.path("key").asText(), entry.path("value").asText());
        }
        value.set(field, map);
        return value;
    }

    static JsonNode read(String json) { return read(json.getBytes(StandardCharsets.UTF_8)); }
    static JsonNode read(byte[] bytes) {
        try {
            JsonNode value = JSON.readTree(bytes);
            if (value == null || !value.isObject()) throw new IllegalArgumentException("expected a JSON object");
            return value;
        } catch (IOException exception) { throw new IllegalArgumentException("invalid domain export JSON", exception); }
    }
    static String write(JsonNode value) {
        try { return JSON.writeValueAsString(value); }
        catch (IOException exception) { throw new IllegalStateException("unable to render domain workspace", exception); }
    }
}
