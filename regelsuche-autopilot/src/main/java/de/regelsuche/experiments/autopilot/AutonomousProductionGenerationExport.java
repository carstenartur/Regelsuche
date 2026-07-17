package de.regelsuche.experiments.autopilot;

import de.regelsuche.discovery.domain.DiscoveryLifecycleHandoff;
import de.regelsuche.experiments.autopilot.AutonomousProductionGenerationRunner.GenerationRun;
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
 * Commits the target-free production generation evidence as a manifest-bound
 * export. Detailed, representation-specific artifacts remain separate while
 * the lifecycle entry point is the domain-neutral handoff.
 *
 * <p>The manifest is removed before any artifact replacement and written last
 * through an atomic rename. Consumers must treat a missing manifest as an
 * incomplete export and verify every retained byte hash before use.</p>
 */
public final class AutonomousProductionGenerationExport {
    public static final String SCHEMA =
        "regelsuche.autonomous-production-generation-export/v1";
    public static final String EXPORT_ID = "autonomous-production-generation";
    public static final String COMMIT_PROTOCOL = "MANIFEST_LAST_ATOMIC_RENAME";
    public static final String MANIFEST_FILE_NAME = "export-manifest.json";

    public GenerationExportManifest write(Path outputDirectory, GenerationRun run) {
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        Objects.requireNonNull(run, "run");
        DiscoveryLifecycleHandoff handoff =
            new AutonomousProductionDomainHandoffAdapter().adapt(run);
        if (!run.contentHash().equals(handoff.sourceEvidenceHash())) {
            throw new IllegalStateException(
                "lifecycle handoff must bind the exported generation run");
        }
        List<Payload> payloads = List.of(
            payload(
                ArtifactRole.RESEARCH_BRIEF,
                run.brief().contentHash(),
                run.brief().toCanonicalJson()),
            payload(
                ArtifactRole.SEED_CATALOG,
                run.seedCatalog().contentHash(),
                run.seedCatalog().toCanonicalJson()),
            payload(
                ArtifactRole.OBSERVATION_BUNDLE,
                run.observationBundle().contentHash(),
                run.observationBundle().toCanonicalJson()),
            payload(
                ArtifactRole.GENERATION_RECEIPT,
                run.receipt().contentHash(),
                run.receipt().toCanonicalJson()),
            payload(
                ArtifactRole.DISCOVERY_REPORT,
                run.discoveryReportHash(),
                run.discoveryReport().renderDeterministicJson()),
            payload(
                ArtifactRole.GENERATION_RUN,
                run.contentHash(),
                run.toCanonicalJson()),
            payload(
                ArtifactRole.LIFECYCLE_HANDOFF,
                handoff.contentHash(),
                handoff.toCanonicalJson()));
        GenerationExportManifest manifest = GenerationExportManifest.create(
            run.contentHash(),
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
                "Could not commit production generation export", exception);
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
                AutonomousResearchBrief.hash(content),
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

    private static void requireSha256(String value, String field) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be SHA-256");
        }
    }

    private static String manifestHash(
        String exportId,
        String generationRunHash,
        String lifecycleHandoffHash,
        List<ExportArtifact> artifacts,
        String commitProtocol
    ) {
        return AutonomousResearchBrief.hash(
            SCHEMA
                + "\nexportId=" + exportId
                + "\ngenerationRunHash=" + generationRunHash
                + "\nlifecycleHandoffHash=" + lifecycleHandoffHash
                + "\nartifacts=" + artifacts.stream()
                    .map(ExportArtifact::canonicalMaterial).toList()
                + "\ncommitProtocol=" + commitProtocol);
    }

    private static List<ExportArtifact> normalizeArtifacts(
        List<ExportArtifact> values
    ) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("generation export requires artifacts");
        }
        List<ExportArtifact> normalized = new ArrayList<>();
        Set<String> fileNames = new HashSet<>();
        EnumSet<ArtifactRole> roles = EnumSet.noneOf(ArtifactRole.class);
        for (ExportArtifact value : values) {
            ExportArtifact artifact = Objects.requireNonNull(value, "export artifact");
            if (!fileNames.add(artifact.fileName())) {
                throw new IllegalArgumentException(
                    "duplicate generation export file: " + artifact.fileName());
            }
            if (!roles.add(artifact.role())) {
                throw new IllegalArgumentException(
                    "duplicate generation export role: " + artifact.role());
            }
            normalized.add(artifact);
        }
        if (!roles.equals(EnumSet.allOf(ArtifactRole.class))) {
            throw new IllegalArgumentException(
                "generation export must contain every required artifact role");
        }
        normalized.sort(Comparator.comparing(ExportArtifact::fileName));
        return List.copyOf(normalized);
    }

    public enum ArtifactRole {
        RESEARCH_BRIEF("brief-v2.json"),
        SEED_CATALOG("seeds.json"),
        OBSERVATION_BUNDLE("observations.json"),
        GENERATION_RECEIPT("generation-receipt.json"),
        DISCOVERY_REPORT("discovery-report.json"),
        GENERATION_RUN("generation-run.json"),
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
            requireSha256(sourceContentHash, "sourceContentHash");
            requireSha256(byteHash, "byteHash");
            if (byteLength <= 0L) {
                throw new IllegalArgumentException("byteLength must be positive");
            }
        }

        String canonicalMaterial() {
            return fileName + '|' + role.name() + '|' + sourceContentHash
                + '|' + byteHash + '|' + byteLength;
        }
    }

    public record GenerationExportManifest(
        String schema,
        String exportId,
        String generationRunHash,
        String lifecycleHandoffHash,
        List<ExportArtifact> artifacts,
        String commitProtocol,
        String contentHash
    ) {
        public GenerationExportManifest {
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported production generation export schema");
            }
            if (!EXPORT_ID.equals(exportId)) {
                throw new IllegalArgumentException(
                    "unsupported production generation export identity");
            }
            requireSha256(generationRunHash, "generationRunHash");
            requireSha256(lifecycleHandoffHash, "lifecycleHandoffHash");
            artifacts = normalizeArtifacts(artifacts);
            if (!COMMIT_PROTOCOL.equals(commitProtocol)) {
                throw new IllegalArgumentException(
                    "generation export must use the manifest-last commit protocol");
            }
            ExportArtifact generation = artifacts.stream()
                .filter(item -> item.role() == ArtifactRole.GENERATION_RUN)
                .findFirst()
                .orElseThrow();
            ExportArtifact handoff = artifacts.stream()
                .filter(item -> item.role() == ArtifactRole.LIFECYCLE_HANDOFF)
                .findFirst()
                .orElseThrow();
            if (!generationRunHash.equals(generation.sourceContentHash())
                    || !lifecycleHandoffHash.equals(handoff.sourceContentHash())) {
                throw new IllegalArgumentException(
                    "manifest root hashes must match their retained artifacts");
            }
            requireSha256(contentHash, "contentHash");
            String expected = manifestHash(
                exportId,
                generationRunHash,
                lifecycleHandoffHash,
                artifacts,
                commitProtocol);
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "production generation export contentHash mismatch");
            }
        }

        public static GenerationExportManifest create(
            String generationRunHash,
            String lifecycleHandoffHash,
            List<ExportArtifact> artifacts
        ) {
            requireSha256(generationRunHash, "generationRunHash");
            requireSha256(lifecycleHandoffHash, "lifecycleHandoffHash");
            List<ExportArtifact> normalized = normalizeArtifacts(artifacts);
            String hash = manifestHash(
                EXPORT_ID,
                generationRunHash,
                lifecycleHandoffHash,
                normalized,
                COMMIT_PROTOCOL);
            return new GenerationExportManifest(
                SCHEMA,
                EXPORT_ID,
                generationRunHash,
                lifecycleHandoffHash,
                normalized,
                COMMIT_PROTOCOL,
                hash);
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("exportId", exportId)
                .property("generationRunHash", generationRunHash)
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
    }

    private record Payload(ExportArtifact artifact, String content) {
        private Payload {
            Objects.requireNonNull(artifact, "artifact");
            Objects.requireNonNull(content, "content");
        }
    }
}
