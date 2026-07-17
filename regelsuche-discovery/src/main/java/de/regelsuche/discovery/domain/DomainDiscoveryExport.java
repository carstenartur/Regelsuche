package de.regelsuche.discovery.domain;

import de.regelsuche.json.JsonWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Commits one generic domain-discovery run as a manifest-bound export.
 *
 * <p>The detailed domain evidence remains representation-specific. The stable
 * downstream entry point is the representation-free lifecycle handoff, bound
 * to the exact descriptor and evidence bytes by a manifest written last.</p>
 */
public final class DomainDiscoveryExport {
    public static final String SCHEMA = "regelsuche.domain-discovery-export/v1";
    public static final String COMMIT_PROTOCOL = "MANIFEST_LAST_ATOMIC_RENAME";
    public static final String MANIFEST_FILE_NAME = "export-manifest.json";

    public DomainExportManifest write(
        Path outputDirectory,
        DomainDiscoveryEvidence evidence
    ) {
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        Objects.requireNonNull(evidence, "evidence");
        DiscoveryLifecycleHandoff handoff = DiscoveryLifecycleHandoff.from(evidence);
        validateBinding(evidence, handoff);

        List<Payload> payloads = List.of(
            payload(
                ArtifactRole.DOMAIN_DESCRIPTOR,
                evidence.descriptor().contentHash(),
                evidence.descriptor().toCanonicalJson()),
            payload(
                ArtifactRole.DISCOVERY_EVIDENCE,
                evidence.contentHash(),
                evidence.toCanonicalJson()),
            payload(
                ArtifactRole.LIFECYCLE_HANDOFF,
                handoff.contentHash(),
                handoff.toCanonicalJson()));
        DomainExportManifest manifest = DomainExportManifest.create(
            evidence.campaignId(),
            evidence.descriptor().domainId(),
            evidence.descriptor().revision(),
            evidence.descriptor().contentHash(),
            evidence.contentHash(),
            handoff.contentHash(),
            payloads.stream().map(Payload::artifact).toList());

        Path directory = outputDirectory.toAbsolutePath().normalize();
        try {
            Files.createDirectories(directory);
            Files.deleteIfExists(directory.resolve(MANIFEST_FILE_NAME));
            for (Payload payload : payloads.stream()
                    .sorted(Comparator.comparing(item -> item.artifact().fileName()))
                    .toList()) {
                atomicWrite(
                    directory.resolve(payload.artifact().fileName()),
                    payload.content());
            }
            atomicWrite(
                directory.resolve(MANIFEST_FILE_NAME),
                manifest.toCanonicalJson());
            return manifest;
        } catch (IOException exception) {
            throw new UncheckedIOException(
                "Could not commit generic domain discovery export", exception);
        }
    }

    private static void validateBinding(
        DomainDiscoveryEvidence evidence,
        DiscoveryLifecycleHandoff handoff
    ) {
        if (handoff.sourceKind()
                != DiscoveryLifecycleHandoff.SourceKind.DOMAIN_DISCOVERY_EVIDENCE
                || handoff.stage()
                    != DiscoveryLifecycleHandoff.Stage.DISCOVERY_VALIDATION
                || !evidence.campaignId().equals(handoff.campaignId())
                || !evidence.descriptor().domainId().equals(handoff.domainId())
                || !evidence.descriptor().revision().equals(handoff.domainRevision())
                || !evidence.descriptor().contentHash().equals(
                    handoff.domainContractHash())
                || !evidence.seed().contentHash().equals(handoff.inputHash())
                || !evidence.contentHash().equals(handoff.sourceEvidenceHash())) {
            throw new IllegalStateException(
                "lifecycle handoff must bind the exported domain evidence");
        }
    }

    private static Payload payload(
        ArtifactRole role,
        String sourceContentHash,
        String content
    ) {
        Objects.requireNonNull(content, "content");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return new Payload(
            new ExportArtifact(
                role.fileName(),
                role,
                sourceContentHash,
                DomainCanonical.sha256(content),
                bytes.length),
            content);
    }

    private static void atomicWrite(Path target, String content) throws IOException {
        Path directory = target.getParent();
        if (directory == null) {
            throw new IOException("export target must have a parent directory");
        }
        Path temporary = Files.createTempFile(
            directory,
            "." + target.getFileName() + ".",
            ".tmp");
        try {
            Files.writeString(
                temporary,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
            try (FileChannel channel = FileChannel.open(
                    temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String manifestHash(
        String campaignId,
        String domainId,
        String domainRevision,
        String domainDescriptorHash,
        String discoveryEvidenceHash,
        String lifecycleHandoffHash,
        List<ExportArtifact> artifacts,
        String commitProtocol
    ) {
        return DomainCanonical.sha256(
            SCHEMA
                + "\ncampaignId=" + campaignId
                + "\ndomainId=" + domainId
                + "\ndomainRevision=" + domainRevision
                + "\ndomainDescriptorHash=" + domainDescriptorHash
                + "\ndiscoveryEvidenceHash=" + discoveryEvidenceHash
                + "\nlifecycleHandoffHash=" + lifecycleHandoffHash
                + "\nartifacts=" + artifacts.stream()
                    .map(ExportArtifact::canonicalMaterial).toList()
                + "\ncommitProtocol=" + commitProtocol);
    }

    private static List<ExportArtifact> normalizeArtifacts(
        List<ExportArtifact> values
    ) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("domain export requires artifacts");
        }
        List<ExportArtifact> normalized = new ArrayList<>();
        Set<String> fileNames = new HashSet<>();
        EnumSet<ArtifactRole> roles = EnumSet.noneOf(ArtifactRole.class);
        for (ExportArtifact value : values) {
            ExportArtifact artifact = Objects.requireNonNull(
                value, "export artifact");
            if (!fileNames.add(artifact.fileName())) {
                throw new IllegalArgumentException(
                    "duplicate domain export file: " + artifact.fileName());
            }
            if (!roles.add(artifact.role())) {
                throw new IllegalArgumentException(
                    "duplicate domain export role: " + artifact.role());
            }
            normalized.add(artifact);
        }
        if (!roles.equals(EnumSet.allOf(ArtifactRole.class))) {
            throw new IllegalArgumentException(
                "domain export must contain every required artifact role");
        }
        normalized.sort(Comparator.comparing(ExportArtifact::fileName));
        return List.copyOf(normalized);
    }

    public enum ArtifactRole {
        DOMAIN_DESCRIPTOR("domain.json"),
        DISCOVERY_EVIDENCE("evidence.json"),
        LIFECYCLE_HANDOFF("lifecycle-handoff.json");

        private final String fileName;

        ArtifactRole(String fileName) {
            this.fileName = fileName;
        }

        public String fileName() {
            return fileName;
        }
    }

    public record ExportArtifact(
        String fileName,
        ArtifactRole role,
        String sourceContentHash,
        String byteHash,
        long byteLength
    ) {
        public ExportArtifact {
            Objects.requireNonNull(role, "role");
            if (!role.fileName().equals(fileName)
                    || fileName.contains("/")
                    || fileName.contains("\\")
                    || MANIFEST_FILE_NAME.equals(fileName)) {
                throw new IllegalArgumentException(
                    "export file name must be the canonical role file name");
            }
            DomainCanonical.requireSha256(
                sourceContentHash, "sourceContentHash");
            DomainCanonical.requireSha256(byteHash, "byteHash");
            if (byteLength <= 0L) {
                throw new IllegalArgumentException(
                    "byteLength must be positive");
            }
        }

        String canonicalMaterial() {
            return fileName + '|' + role.name() + '|' + sourceContentHash
                + '|' + byteHash + '|' + byteLength;
        }
    }

    public record DomainExportManifest(
        String schema,
        String campaignId,
        String domainId,
        String domainRevision,
        String domainDescriptorHash,
        String discoveryEvidenceHash,
        String lifecycleHandoffHash,
        List<ExportArtifact> artifacts,
        String commitProtocol,
        String contentHash
    ) {
        public DomainExportManifest {
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported generic domain export schema");
            }
            campaignId = DomainCanonical.requireIdentifier(
                campaignId, "campaignId");
            domainId = DomainCanonical.requireIdentifier(domainId, "domainId");
            domainRevision = DomainCanonical.requireIdentifier(
                domainRevision, "domainRevision");
            DomainCanonical.requireSha256(
                domainDescriptorHash, "domainDescriptorHash");
            DomainCanonical.requireSha256(
                discoveryEvidenceHash, "discoveryEvidenceHash");
            DomainCanonical.requireSha256(
                lifecycleHandoffHash, "lifecycleHandoffHash");
            artifacts = normalizeArtifacts(artifacts);
            if (!COMMIT_PROTOCOL.equals(commitProtocol)) {
                throw new IllegalArgumentException(
                    "domain export must use the manifest-last commit protocol");
            }
            requireRoot(
                artifacts,
                ArtifactRole.DOMAIN_DESCRIPTOR,
                domainDescriptorHash);
            requireRoot(
                artifacts,
                ArtifactRole.DISCOVERY_EVIDENCE,
                discoveryEvidenceHash);
            requireRoot(
                artifacts,
                ArtifactRole.LIFECYCLE_HANDOFF,
                lifecycleHandoffHash);
            DomainCanonical.requireSha256(contentHash, "contentHash");
            String expected = manifestHash(
                campaignId,
                domainId,
                domainRevision,
                domainDescriptorHash,
                discoveryEvidenceHash,
                lifecycleHandoffHash,
                artifacts,
                commitProtocol);
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "generic domain export contentHash mismatch");
            }
        }

        public static DomainExportManifest create(
            String campaignId,
            String domainId,
            String domainRevision,
            String domainDescriptorHash,
            String discoveryEvidenceHash,
            String lifecycleHandoffHash,
            List<ExportArtifact> artifacts
        ) {
            String normalizedCampaign = DomainCanonical.requireIdentifier(
                campaignId, "campaignId");
            String normalizedDomain = DomainCanonical.requireIdentifier(
                domainId, "domainId");
            String normalizedRevision = DomainCanonical.requireIdentifier(
                domainRevision, "domainRevision");
            DomainCanonical.requireSha256(
                domainDescriptorHash, "domainDescriptorHash");
            DomainCanonical.requireSha256(
                discoveryEvidenceHash, "discoveryEvidenceHash");
            DomainCanonical.requireSha256(
                lifecycleHandoffHash, "lifecycleHandoffHash");
            List<ExportArtifact> normalizedArtifacts = normalizeArtifacts(artifacts);
            String hash = manifestHash(
                normalizedCampaign,
                normalizedDomain,
                normalizedRevision,
                domainDescriptorHash,
                discoveryEvidenceHash,
                lifecycleHandoffHash,
                normalizedArtifacts,
                COMMIT_PROTOCOL);
            return new DomainExportManifest(
                SCHEMA,
                normalizedCampaign,
                normalizedDomain,
                normalizedRevision,
                domainDescriptorHash,
                discoveryEvidenceHash,
                lifecycleHandoffHash,
                normalizedArtifacts,
                COMMIT_PROTOCOL,
                hash);
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("campaignId", campaignId)
                .property("domainId", domainId)
                .property("domainRevision", domainRevision)
                .property("domainDescriptorHash", domainDescriptorHash)
                .property("discoveryEvidenceHash", discoveryEvidenceHash)
                .property("lifecycleHandoffHash", lifecycleHandoffHash)
                .array("artifacts", array -> artifacts.forEach(artifact ->
                    array.objectValue(object -> object
                        .property("fileName", artifact.fileName())
                        .property("role", artifact.role().name())
                        .property("sourceContentHash", artifact.sourceContentHash())
                        .property("byteHash", artifact.byteHash())
                        .property("byteLength", artifact.byteLength()))))
                .property("commitProtocol", commitProtocol)
                .property("contentHash", contentHash)
                .endObject()
                .toString();
        }

        private static void requireRoot(
            List<ExportArtifact> artifacts,
            ArtifactRole role,
            String expectedHash
        ) {
            ExportArtifact artifact = artifacts.stream()
                .filter(item -> item.role() == role)
                .findFirst()
                .orElseThrow();
            if (!expectedHash.equals(artifact.sourceContentHash())) {
                throw new IllegalArgumentException(
                    "manifest root hash must match retained " + role);
            }
        }
    }

    private record Payload(ExportArtifact artifact, String content) {
        private Payload {
            Objects.requireNonNull(artifact, "artifact");
            Objects.requireNonNull(content, "content");
        }
    }
}
