package de.regelsuche.discovery.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.discovery.domain.DiscoveryDomain.DiscoverySeed;
import de.regelsuche.discovery.domain.DomainDiscoveryExport.ArtifactRole;
import de.regelsuche.discovery.domain.DomainDiscoveryExport.DomainExportManifest;
import de.regelsuche.discovery.domain.DomainDiscoveryExport.ExportArtifact;
import de.regelsuche.json.JsonWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Verifies and snapshots one manifest-bound generic domain export.
 *
 * <p>The verifier never follows symbolic links and never exposes paths that a
 * consumer must read again. Every retained file is bounded, read once, checked
 * against the manifest and returned as an immutable byte snapshot. It validates
 * the cross-document identity bindings, but it does not upgrade discovery
 * validation to proof, novelty, promotion or Public Evidence.</p>
 */
public final class DomainDiscoveryExportVerifier {
    public static final long DEFAULT_MAX_MANIFEST_BYTES = 1_048_576L;
    public static final long DEFAULT_MAX_ARTIFACT_BYTES = 67_108_864L;

    private static final ObjectMapper JSON = new ObjectMapper(
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private static final Set<String> DESCRIPTOR_FIELDS = Set.of(
        "schema",
        "domainId",
        "revision",
        "stateType",
        "candidateType",
        "certificateType",
        "generatorId",
        "stateCodecId",
        "operatorIds",
        "invariantIds",
        "objectiveId",
        "candidateExtractorId",
        "candidateCodecId",
        "counterexampleGeneratorId",
        "evaluatorId",
        "certificateCodecId",
        "certificateRendererId",
        "evidenceAdapterId",
        "deterministic",
        "semanticRoles",
        "contentHash");
    private static final Set<String> EVIDENCE_FIELDS = Set.of(
        "schema",
        "campaignId",
        "domain",
        "seed",
        "budget",
        "outcome",
        "summary",
        "states",
        "transitions",
        "candidateAttempts",
        "resources",
        "selectedCandidateHash",
        "certificate",
        "domainEvidence",
        "proofStatus",
        "externalNoveltyStatus",
        "promotionStatus",
        "publicEvidenceStatus",
        "contentHash");
    private static final Set<String> SEED_FIELDS = Set.of(
        "schema",
        "seedId",
        "domainId",
        "payload",
        "sourceReference",
        "contentHash");
    private static final Set<String> HANDOFF_FIELDS = Set.of(
        "schema",
        "handoffId",
        "sourceKind",
        "campaignId",
        "domainId",
        "domainRevision",
        "domainContractHash",
        "inputHash",
        "sourceEvidenceHash",
        "stage",
        "disposition",
        "selectedCandidateHash",
        "certificateHash",
        "resources",
        "metadata",
        "proofStatus",
        "externalNoveltyStatus",
        "promotionStatus",
        "publicEvidenceStatus",
        "contentHash");

    private final long maxManifestBytes;
    private final long maxArtifactBytes;

    public DomainDiscoveryExportVerifier() {
        this(DEFAULT_MAX_MANIFEST_BYTES, DEFAULT_MAX_ARTIFACT_BYTES);
    }

    public DomainDiscoveryExportVerifier(
        long maxManifestBytes,
        long maxArtifactBytes
    ) {
        this.maxManifestBytes = boundedLimit(
            maxManifestBytes, "maxManifestBytes");
        this.maxArtifactBytes = boundedLimit(
            maxArtifactBytes, "maxArtifactBytes");
    }

    /**
     * Returns an authority-bearing immutable snapshot only after every check
     * succeeds. Invalid, incomplete, oversized, symlinked or changing exports
     * throw {@link ExportVerificationException}.
     */
    public VerifiedDomainExport requireVerified(Path exportDirectory) {
        Path supplied = Objects.requireNonNull(
            exportDirectory, "exportDirectory");
        Path directory = supplied.toAbsolutePath().normalize();
        try {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(directory)) {
                throw failure("export directory is not a local regular directory");
            }

            Path manifestPath = directory.resolve(
                DomainDiscoveryExport.MANIFEST_FILE_NAME);
            Snapshot firstManifest = readSnapshot(
                manifestPath,
                maxManifestBytes,
                "export manifest");
            DomainExportManifest manifest = parseManifest(firstManifest.bytes());
            Set<String> expectedEntries = expectedEntries(manifest);
            requireExactDirectory(directory, expectedEntries);

            EnumMap<ArtifactRole, Snapshot> snapshots =
                new EnumMap<>(ArtifactRole.class);
            for (ExportArtifact artifact : manifest.artifacts()) {
                Snapshot snapshot = readSnapshot(
                    directory.resolve(artifact.fileName()),
                    maxArtifactBytes,
                    artifact.role().name());
                if (snapshot.byteLength() != artifact.byteLength()) {
                    throw failure(
                        artifact.fileName() + " byte length does not match manifest");
                }
                if (!snapshot.byteHash().equals(artifact.byteHash())) {
                    throw failure(
                        artifact.fileName() + " byte hash does not match manifest");
                }
                snapshots.put(artifact.role(), snapshot);
            }

            validateIdentities(manifest, snapshots);

            Snapshot finalManifest = readSnapshot(
                manifestPath,
                maxManifestBytes,
                "export manifest");
            if (!Arrays.equals(firstManifest.bytes(), finalManifest.bytes())) {
                throw failure("export manifest changed during verification");
            }
            requireExactDirectory(directory, expectedEntries);

            Verification verification = Verification.create(
                manifest,
                firstManifest.byteHash(),
                snapshots);
            return new VerifiedDomainExport(manifest, verification, snapshots);
        } catch (ExportVerificationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new ExportVerificationException(
                "generic domain export verification failed for " + directory,
                exception);
        }
    }

    private static DomainExportManifest parseManifest(byte[] bytes) {
        final ManifestDto dto;
        try {
            dto = JSON.readValue(bytes, ManifestDto.class);
        } catch (IOException exception) {
            throw new ExportVerificationException(
                "invalid generic domain export manifest", exception);
        }
        if (dto == null) {
            throw failure("generic domain export manifest must not be empty");
        }
        List<ExportArtifact> artifacts = requiredList(
            dto.artifacts, "artifacts").stream()
            .map(DomainDiscoveryExportVerifier::artifact)
            .toList();
        try {
            return new DomainExportManifest(
                dto.schema,
                dto.campaignId,
                dto.domainId,
                dto.domainRevision,
                dto.domainDescriptorHash,
                dto.discoveryEvidenceHash,
                dto.lifecycleHandoffHash,
                artifacts,
                dto.commitProtocol,
                dto.contentHash);
        } catch (RuntimeException exception) {
            throw new ExportVerificationException(
                "generic domain export manifest contract is invalid", exception);
        }
    }

    private static ExportArtifact artifact(ArtifactDto dto) {
        if (dto == null) {
            throw failure("manifest artifacts must not contain null");
        }
        final ArtifactRole role;
        try {
            role = ArtifactRole.valueOf(dto.role);
        } catch (RuntimeException exception) {
            throw new ExportVerificationException(
                "unsupported generic domain export artifact role: " + dto.role,
                exception);
        }
        if (dto.byteLength == null) {
            throw failure("manifest artifact byteLength must be present");
        }
        try {
            return new ExportArtifact(
                dto.fileName,
                role,
                dto.sourceContentHash,
                dto.byteHash,
                dto.byteLength);
        } catch (RuntimeException exception) {
            throw new ExportVerificationException(
                "invalid generic domain export artifact: " + role,
                exception);
        }
    }

    private static void validateIdentities(
        DomainExportManifest manifest,
        Map<ArtifactRole, Snapshot> snapshots
    ) {
        JsonNode descriptor = parseObject(
            snapshots.get(ArtifactRole.DOMAIN_DESCRIPTOR),
            "domain descriptor");
        JsonNode evidence = parseObject(
            snapshots.get(ArtifactRole.DISCOVERY_EVIDENCE),
            "domain discovery evidence");
        JsonNode handoff = parseObject(
            snapshots.get(ArtifactRole.LIFECYCLE_HANDOFF),
            "discovery lifecycle handoff");

        requireExactFields(descriptor, DESCRIPTOR_FIELDS, "domain descriptor");
        requireTextEquals(
            descriptor,
            "schema",
            DiscoveryDomainDescriptor.SCHEMA,
            "domain descriptor");
        requireTextEquals(
            descriptor,
            "domainId",
            manifest.domainId(),
            "domain descriptor");
        requireTextEquals(
            descriptor,
            "revision",
            manifest.domainRevision(),
            "domain descriptor");
        requireTextEquals(
            descriptor,
            "contentHash",
            manifest.domainDescriptorHash(),
            "domain descriptor");

        requireExactFields(evidence, EVIDENCE_FIELDS, "domain discovery evidence");
        requireTextEquals(
            evidence,
            "schema",
            DomainDiscoveryEvidence.SCHEMA,
            "domain discovery evidence");
        requireTextEquals(
            evidence,
            "campaignId",
            manifest.campaignId(),
            "domain discovery evidence");
        requireTextEquals(
            evidence,
            "contentHash",
            manifest.discoveryEvidenceHash(),
            "domain discovery evidence");
        JsonNode evidenceDomain = requiredObject(
            evidence, "domain", "domain discovery evidence");
        if (!descriptor.equals(evidenceDomain)) {
            throw failure(
                "domain discovery evidence does not embed the retained descriptor");
        }
        JsonNode seed = requiredObject(
            evidence, "seed", "domain discovery evidence");
        requireExactFields(seed, SEED_FIELDS, "discovery seed");
        requireTextEquals(
            seed,
            "schema",
            DiscoverySeed.SCHEMA,
            "discovery seed");
        requireTextEquals(
            seed,
            "domainId",
            manifest.domainId(),
            "discovery seed");
        String seedHash = requiredText(seed, "contentHash", "discovery seed");
        DomainCanonical.requireSha256(seedHash, "seed contentHash");
        requireNotEvaluated(evidence, "proofStatus", "domain discovery evidence");
        requireNotEvaluated(
            evidence, "externalNoveltyStatus", "domain discovery evidence");
        requireNotEvaluated(
            evidence, "promotionStatus", "domain discovery evidence");
        requireNotEvaluated(
            evidence, "publicEvidenceStatus", "domain discovery evidence");

        requireExactFields(handoff, HANDOFF_FIELDS, "discovery lifecycle handoff");
        requireTextEquals(
            handoff,
            "schema",
            DiscoveryLifecycleHandoff.SCHEMA,
            "discovery lifecycle handoff");
        requireTextEquals(
            handoff,
            "sourceKind",
            DiscoveryLifecycleHandoff.SourceKind.DOMAIN_DISCOVERY_EVIDENCE.name(),
            "discovery lifecycle handoff");
        requireTextEquals(
            handoff,
            "stage",
            DiscoveryLifecycleHandoff.Stage.DISCOVERY_VALIDATION.name(),
            "discovery lifecycle handoff");
        requireTextEquals(
            handoff,
            "campaignId",
            manifest.campaignId(),
            "discovery lifecycle handoff");
        requireTextEquals(
            handoff,
            "domainId",
            manifest.domainId(),
            "discovery lifecycle handoff");
        requireTextEquals(
            handoff,
            "domainRevision",
            manifest.domainRevision(),
            "discovery lifecycle handoff");
        requireTextEquals(
            handoff,
            "domainContractHash",
            manifest.domainDescriptorHash(),
            "discovery lifecycle handoff");
        requireTextEquals(
            handoff,
            "inputHash",
            seedHash,
            "discovery lifecycle handoff");
        requireTextEquals(
            handoff,
            "sourceEvidenceHash",
            manifest.discoveryEvidenceHash(),
            "discovery lifecycle handoff");
        requireTextEquals(
            handoff,
            "contentHash",
            manifest.lifecycleHandoffHash(),
            "discovery lifecycle handoff");
        requireNotEvaluated(handoff, "proofStatus", "discovery lifecycle handoff");
        requireNotEvaluated(
            handoff, "externalNoveltyStatus", "discovery lifecycle handoff");
        requireNotEvaluated(
            handoff, "promotionStatus", "discovery lifecycle handoff");
        requireNotEvaluated(
            handoff, "publicEvidenceStatus", "discovery lifecycle handoff");
    }

    private static JsonNode parseObject(Snapshot snapshot, String label) {
        if (snapshot == null) {
            throw failure("missing retained " + label);
        }
        try {
            JsonNode node = JSON.readTree(snapshot.bytes());
            if (node == null || !node.isObject()) {
                throw failure(label + " must be a JSON object");
            }
            return node;
        } catch (IOException exception) {
            throw new ExportVerificationException(
                label + " is not strict JSON", exception);
        }
    }

    private static void requireExactFields(
        JsonNode node,
        Set<String> expected,
        String label
    ) {
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            Set<String> missing = new HashSet<>(expected);
            missing.removeAll(actual);
            Set<String> unknown = new HashSet<>(actual);
            unknown.removeAll(expected);
            throw failure(
                label + " fields do not match contract; missing=" + missing
                    + ", unknown=" + unknown);
        }
    }

    private static JsonNode requiredObject(
        JsonNode node,
        String field,
        String label
    ) {
        JsonNode value = node.get(field);
        if (value == null || !value.isObject()) {
            throw failure(label + "." + field + " must be an object");
        }
        return value;
    }

    private static String requiredText(
        JsonNode node,
        String field,
        String label
    ) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw failure(label + "." + field + " must be text");
        }
        return value.textValue();
    }

    private static void requireTextEquals(
        JsonNode node,
        String field,
        String expected,
        String label
    ) {
        String actual = requiredText(node, field, label);
        if (!expected.equals(actual)) {
            throw failure(
                label + "." + field + " mismatch: expected " + expected
                    + " but found " + actual);
        }
    }

    private static void requireNotEvaluated(
        JsonNode node,
        String field,
        String label
    ) {
        requireTextEquals(
            node,
            field,
            DomainDiscoveryEvidence.NOT_EVALUATED,
            label);
    }

    private static Set<String> expectedEntries(DomainExportManifest manifest) {
        Set<String> expected = new HashSet<>();
        expected.add(DomainDiscoveryExport.MANIFEST_FILE_NAME);
        for (ExportArtifact artifact : manifest.artifacts()) {
            expected.add(artifact.fileName());
        }
        return Set.copyOf(expected);
    }

    private static void requireExactDirectory(
        Path directory,
        Set<String> expected
    ) throws IOException {
        Set<String> actual = new HashSet<>();
        try (var entries = Files.list(directory)) {
            for (Path entry : entries.toList()) {
                if (Files.isSymbolicLink(entry)
                        || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    throw failure(
                        "export contains a non-regular or symbolic-link entry: "
                            + entry.getFileName());
                }
                actual.add(entry.getFileName().toString());
            }
        }
        if (!actual.equals(expected)) {
            Set<String> missing = new HashSet<>(expected);
            missing.removeAll(actual);
            Set<String> unexpected = new HashSet<>(actual);
            unexpected.removeAll(expected);
            throw failure(
                "export directory entries do not match manifest; missing="
                    + missing + ", unexpected=" + unexpected);
        }
    }

    private static Snapshot readSnapshot(
        Path path,
        long maximumBytes,
        String label
    ) throws IOException {
        if (Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(label + " is missing, non-regular or a symbolic link");
        }
        long declaredSize = Files.size(path);
        if (declaredSize < 1L || declaredSize > maximumBytes) {
            throw failure(
                label + " byte length " + declaredSize
                    + " is outside the configured bound " + maximumBytes);
        }
        int initialCapacity = (int) Math.min(declaredSize, 8192L);
        ByteArrayOutputStream output = new ByteArrayOutputStream(initialCapacity);
        long total = 0L;
        try (SeekableByteChannel channel = Files.newByteChannel(
                path,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS);
             InputStream input = Channels.newInputStream(channel)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                total = Math.addExact(total, read);
                if (total > maximumBytes) {
                    throw failure(label + " exceeded its configured byte bound");
                }
                output.write(buffer, 0, read);
            }
        }
        if (total != declaredSize) {
            throw failure(label + " changed length while being read");
        }
        byte[] bytes = output.toByteArray();
        return new Snapshot(bytes, sha256(bytes), total);
    }

    private static long boundedLimit(long value, String field) {
        if (value < 1L || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                field + " must be in [1," + Integer.MAX_VALUE + "]");
        }
        return value;
    }

    private static String sha256(byte[] bytes) {
        try {
            return "sha256:" + java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static <T> List<T> requiredList(List<T> values, String field) {
        if (values == null) {
            throw failure(field + " must be present");
        }
        return values;
    }

    private static ExportVerificationException failure(String message) {
        return new ExportVerificationException(message);
    }

    public static final class ExportVerificationException extends SecurityException {
        public ExportVerificationException(String message) {
            super(message);
        }

        public ExportVerificationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Canonical receipt for the checks completed over one byte snapshot. */
    public record Verification(
        String schema,
        String manifestContentHash,
        String manifestByteHash,
        String campaignId,
        String domainId,
        String domainRevision,
        String domainDescriptorHash,
        String discoveryEvidenceHash,
        String lifecycleHandoffHash,
        String artifactSetHash,
        int verifiedArtifactCount,
        String identityBindingStatus,
        String mathematicalValidationStatus,
        String contentHash
    ) {
        public static final String SCHEMA =
            "regelsuche.domain-discovery-export-verification/v1";
        public static final String VERIFIED = "VERIFIED";
        public static final String NOT_EVALUATED = "NOT_EVALUATED";

        public Verification {
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported domain export verification schema");
            }
            DomainCanonical.requireSha256(
                manifestContentHash, "manifestContentHash");
            DomainCanonical.requireSha256(manifestByteHash, "manifestByteHash");
            campaignId = DomainCanonical.requireIdentifier(campaignId, "campaignId");
            domainId = DomainCanonical.requireIdentifier(domainId, "domainId");
            domainRevision = DomainCanonical.requireIdentifier(
                domainRevision, "domainRevision");
            DomainCanonical.requireSha256(
                domainDescriptorHash, "domainDescriptorHash");
            DomainCanonical.requireSha256(
                discoveryEvidenceHash, "discoveryEvidenceHash");
            DomainCanonical.requireSha256(
                lifecycleHandoffHash, "lifecycleHandoffHash");
            DomainCanonical.requireSha256(artifactSetHash, "artifactSetHash");
            if (verifiedArtifactCount != ArtifactRole.values().length) {
                throw new IllegalArgumentException(
                    "verification must cover every export artifact role");
            }
            if (!VERIFIED.equals(identityBindingStatus)) {
                throw new IllegalArgumentException(
                    "identityBindingStatus must be VERIFIED");
            }
            if (!NOT_EVALUATED.equals(mathematicalValidationStatus)) {
                throw new IllegalArgumentException(
                    "mathematicalValidationStatus must remain NOT_EVALUATED");
            }
            DomainCanonical.requireSha256(contentHash, "contentHash");
            String expected = verificationHash(
                manifestContentHash,
                manifestByteHash,
                campaignId,
                domainId,
                domainRevision,
                domainDescriptorHash,
                discoveryEvidenceHash,
                lifecycleHandoffHash,
                artifactSetHash,
                verifiedArtifactCount,
                identityBindingStatus,
                mathematicalValidationStatus);
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "domain export verification contentHash mismatch");
            }
        }

        private static Verification create(
            DomainExportManifest manifest,
            String manifestByteHash,
            Map<ArtifactRole, Snapshot> snapshots
        ) {
            List<String> artifactMaterial = snapshots.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey().name() + '|'
                    + entry.getValue().byteHash() + '|'
                    + entry.getValue().byteLength())
                .toList();
            String artifactSetHash = DomainCanonical.sha256(
                DomainCanonical.canonicalList(artifactMaterial));
            String hash = verificationHash(
                manifest.contentHash(),
                manifestByteHash,
                manifest.campaignId(),
                manifest.domainId(),
                manifest.domainRevision(),
                manifest.domainDescriptorHash(),
                manifest.discoveryEvidenceHash(),
                manifest.lifecycleHandoffHash(),
                artifactSetHash,
                snapshots.size(),
                VERIFIED,
                NOT_EVALUATED);
            return new Verification(
                SCHEMA,
                manifest.contentHash(),
                manifestByteHash,
                manifest.campaignId(),
                manifest.domainId(),
                manifest.domainRevision(),
                manifest.domainDescriptorHash(),
                manifest.discoveryEvidenceHash(),
                manifest.lifecycleHandoffHash(),
                artifactSetHash,
                snapshots.size(),
                VERIFIED,
                NOT_EVALUATED,
                hash);
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("manifestContentHash", manifestContentHash)
                .property("manifestByteHash", manifestByteHash)
                .property("campaignId", campaignId)
                .property("domainId", domainId)
                .property("domainRevision", domainRevision)
                .property("domainDescriptorHash", domainDescriptorHash)
                .property("discoveryEvidenceHash", discoveryEvidenceHash)
                .property("lifecycleHandoffHash", lifecycleHandoffHash)
                .property("artifactSetHash", artifactSetHash)
                .property("verifiedArtifactCount", verifiedArtifactCount)
                .property("identityBindingStatus", identityBindingStatus)
                .property("mathematicalValidationStatus", mathematicalValidationStatus)
                .property("contentHash", contentHash)
                .endObject()
                .toString();
        }

        private static String verificationHash(
            String manifestContentHash,
            String manifestByteHash,
            String campaignId,
            String domainId,
            String domainRevision,
            String domainDescriptorHash,
            String discoveryEvidenceHash,
            String lifecycleHandoffHash,
            String artifactSetHash,
            int verifiedArtifactCount,
            String identityBindingStatus,
            String mathematicalValidationStatus
        ) {
            return DomainCanonical.sha256(DomainCanonical.canonicalMap(Map.ofEntries(
                Map.entry("schema", SCHEMA),
                Map.entry("manifestContentHash", manifestContentHash),
                Map.entry("manifestByteHash", manifestByteHash),
                Map.entry("campaignId", campaignId),
                Map.entry("domainId", domainId),
                Map.entry("domainRevision", domainRevision),
                Map.entry("domainDescriptorHash", domainDescriptorHash),
                Map.entry("discoveryEvidenceHash", discoveryEvidenceHash),
                Map.entry("lifecycleHandoffHash", lifecycleHandoffHash),
                Map.entry("artifactSetHash", artifactSetHash),
                Map.entry("verifiedArtifactCount",
                    Integer.toString(verifiedArtifactCount)),
                Map.entry("identityBindingStatus", identityBindingStatus),
                Map.entry("mathematicalValidationStatus",
                    mathematicalValidationStatus))));
        }
    }

    /** Immutable verified byte snapshot; callers never need to re-read paths. */
    public static final class VerifiedDomainExport {
        private final DomainExportManifest manifest;
        private final Verification verification;
        private final EnumMap<ArtifactRole, byte[]> artifacts;

        private VerifiedDomainExport(
            DomainExportManifest manifest,
            Verification verification,
            Map<ArtifactRole, Snapshot> snapshots
        ) {
            this.manifest = Objects.requireNonNull(manifest, "manifest");
            this.verification = Objects.requireNonNull(
                verification, "verification");
            this.artifacts = new EnumMap<>(ArtifactRole.class);
            for (Map.Entry<ArtifactRole, Snapshot> entry : snapshots.entrySet()) {
                this.artifacts.put(entry.getKey(), entry.getValue().bytes());
            }
            if (this.artifacts.size() != ArtifactRole.values().length
                    || !verification.contentHash().matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                    "verified domain export requires complete trusted snapshots");
            }
        }

        public DomainExportManifest manifest() {
            return manifest;
        }

        public Verification verification() {
            return verification;
        }

        public byte[] artifactBytes(ArtifactRole role) {
            byte[] bytes = artifacts.get(Objects.requireNonNull(role, "role"));
            if (bytes == null) {
                throw new IllegalArgumentException("artifact role is not retained: " + role);
            }
            return bytes.clone();
        }
    }

    private record Snapshot(byte[] bytes, String byteHash, long byteLength) {
        private Snapshot {
            bytes = bytes == null ? new byte[0] : bytes.clone();
            DomainCanonical.requireSha256(byteHash, "byteHash");
            if (bytes.length != byteLength || byteLength < 1L) {
                throw new IllegalArgumentException(
                    "snapshot length must match retained bytes");
            }
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public static final class ManifestDto {
        public String schema;
        public String campaignId;
        public String domainId;
        public String domainRevision;
        public String domainDescriptorHash;
        public String discoveryEvidenceHash;
        public String lifecycleHandoffHash;
        public List<ArtifactDto> artifacts;
        public String commitProtocol;
        public String contentHash;
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public static final class ArtifactDto {
        public String fileName;
        public String role;
        public String sourceContentHash;
        public String byteHash;
        public Long byteLength;
    }
}
