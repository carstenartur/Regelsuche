package de.regelsuche.benchmark;

import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.AtlasReport;
import de.regelsuche.benchmark.HistoricalRediscoveryAtlas.WrittenArtifacts;
import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Corpus;
import de.regelsuche.json.JsonReader;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.util.AtomicJsonFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Commits and verifies the complete historical-rediscovery atlas as a
 * manifest-last, content-addressed diagnostic run.
 */
public final class HistoricalRediscoveryRunArtifact {
    public static final String SCHEMA =
        "regelsuche.historical-rediscovery-run/v1";
    public static final String EVIDENCE_STATUS = "EXECUTED_DIAGNOSTIC";
    public static final String COMMIT_PROTOCOL =
        "MANIFEST_LAST_ATOMIC_RENAME";
    public static final String MANIFEST_FILE_NAME =
        "historical-rediscovery-run.json";

    private static final long MAX_MANIFEST_BYTES = 64L * 1024L;
    private static final long MAX_PAYLOAD_BYTES = 32L * 1024L * 1024L;
    private static final Set<String> ROOT_KEYS = Set.of(
        "schema",
        "evidenceStatus",
        "corpusSchema",
        "corpusSha256",
        "atlasSchema",
        "inventoryRevision",
        "claimBoundary",
        "assessmentDecision",
        "caseCount",
        "artifacts",
        "commitProtocol",
        "contentHash"
    );
    private static final Set<String> ARTIFACT_KEYS = Set.of(
        "fileName",
        "role",
        "mediaType",
        "byteHash",
        "byteLength"
    );
    private static final Set<String> EXPECTED_FILES = Set.of(
        MANIFEST_FILE_NAME,
        ArtifactRole.ATLAS_JSON.fileName(),
        ArtifactRole.ATLAS_MARKDOWN.fileName()
    );

    private HistoricalRediscoveryRunArtifact() {
    }

    /**
     * Invalidates any previous authority-bearing run before payload generation.
     */
    public static void begin(Path outputDirectory) {
        Path directory = normalizedDirectory(outputDirectory);
        try {
            Files.createDirectories(directory);
            rejectSymbolicAncestry(directory);
            Files.deleteIfExists(directory.resolve(MANIFEST_FILE_NAME));
        } catch (IOException exception) {
            throw new UncheckedIOException(
                "Could not begin historical rediscovery run", exception);
        }
    }

    /**
     * Writes the manifest last and immediately verifies every retained byte.
     */
    public static VerifiedRun commit(
        Path outputDirectory,
        Corpus corpus,
        AtlasReport report,
        WrittenArtifacts writtenArtifacts
    ) {
        Path directory = normalizedDirectory(outputDirectory);
        Objects.requireNonNull(corpus, "corpus");
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(writtenArtifacts, "writtenArtifacts");
        requireManifestAbsent(directory);
        verifyBindings(directory, corpus, report, writtenArtifacts);

        List<Artifact> artifacts = List.of(
            describe(ArtifactRole.ATLAS_JSON, writtenArtifacts.json()),
            describe(ArtifactRole.ATLAS_MARKDOWN, writtenArtifacts.markdown())
        ).stream().sorted(Comparator.comparing(Artifact::fileName)).toList();
        Manifest manifest = Manifest.create(corpus, report, artifacts);
        try {
            AtomicJsonFile.writeUtf8(
                directory.resolve(MANIFEST_FILE_NAME),
                manifest.toCanonicalJson());
        } catch (IOException exception) {
            throw new UncheckedIOException(
                "Could not commit historical rediscovery run", exception);
        }
        return verify(directory);
    }

    /**
     * Loads a bounded immutable run and fails closed on any membership,
     * identity, length, hash or semantic-root mismatch.
     */
    public static VerifiedRun verify(Path outputDirectory) {
        Path directory = normalizedDirectory(outputDirectory);
        rejectSymbolicAncestry(directory);
        verifyDirectoryMembership(directory);
        Path manifestPath = directory.resolve(MANIFEST_FILE_NAME);
        Manifest manifest = Manifest.parse(readUtf8(
            manifestPath,
            MAX_MANIFEST_BYTES,
            "historical rediscovery manifest"));
        for (Artifact artifact : manifest.artifacts()) {
            Artifact actual = describe(
                artifact.role(),
                directory.resolve(artifact.fileName()));
            if (!artifact.equals(actual)) {
                throw new IllegalArgumentException(
                    "historical rediscovery artifact differs: "
                        + artifact.fileName());
            }
        }
        verifyAtlasIdentity(directory, manifest);
        return new VerifiedRun(directory, manifestPath, manifest);
    }

    private static void verifyBindings(
        Path directory,
        Corpus corpus,
        AtlasReport report,
        WrittenArtifacts writtenArtifacts
    ) {
        if (!corpus.schema().equals(report.corpusSchema())
                || !corpus.contentSha256().equals(report.corpusSha256())
                || !corpus.inventoryRevision().equals(report.inventoryRevision())
                || !corpus.claimBoundary().equals(report.claimBoundary())
                || corpus.cases().size() != report.cases().size()) {
            throw new IllegalArgumentException(
                "atlas report does not bind the supplied frozen corpus");
        }
        requireCanonicalPath(
            directory,
            writtenArtifacts.json(),
            ArtifactRole.ATLAS_JSON);
        requireCanonicalPath(
            directory,
            writtenArtifacts.markdown(),
            ArtifactRole.ATLAS_MARKDOWN);
        requireExactText(writtenArtifacts.json(), report.toJson());
        requireExactText(writtenArtifacts.markdown(), report.toMarkdown());
    }

    private static void requireCanonicalPath(
        Path directory,
        Path actual,
        ArtifactRole role
    ) {
        Path normalized = Objects.requireNonNull(actual, "artifact path")
            .toAbsolutePath().normalize();
        Path expected = directory.resolve(role.fileName());
        if (!expected.equals(normalized)) {
            throw new IllegalArgumentException(
                role + " must use canonical path " + expected);
        }
    }

    private static void requireExactText(Path path, String expected) {
        String actual = readUtf8(path, MAX_PAYLOAD_BYTES, "atlas payload");
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(
                "written atlas payload differs from the in-memory report: "
                    + path.getFileName());
        }
    }

    private static Artifact describe(ArtifactRole role, Path path) {
        Objects.requireNonNull(role, "role");
        byte[] bytes = readBytes(path, MAX_PAYLOAD_BYTES, role.name());
        return new Artifact(
            role.fileName(),
            role,
            role.mediaType(),
            sha256(bytes),
            bytes.length
        );
    }

    private static void verifyAtlasIdentity(
        Path directory,
        Manifest manifest
    ) {
        Path atlasPath = directory.resolve(ArtifactRole.ATLAS_JSON.fileName());
        Map<String, Object> atlas = new JsonReader(readUtf8(
            atlasPath,
            MAX_PAYLOAD_BYTES,
            "historical rediscovery atlas")).readObject();
        requireEqual(manifest.atlasSchema(), string(atlas, "schema"), "atlas schema");
        requireEqual(
            manifest.corpusSchema(),
            string(atlas, "corpusSchema"),
            "corpus schema");
        requireEqual(
            manifest.corpusSha256(),
            string(atlas, "corpusSha256"),
            "corpus SHA-256");
        requireEqual(
            manifest.inventoryRevision(),
            string(atlas, "inventoryRevision"),
            "inventory revision");
        requireEqual(
            manifest.claimBoundary(),
            string(atlas, "claimBoundary"),
            "claim boundary");
        List<?> cases = list(atlas, "cases");
        if (cases.size() != manifest.caseCount()) {
            throw new IllegalArgumentException("atlas case count differs from manifest");
        }
        Map<String, Object> assessment = object(atlas, "assessment");
        requireEqual(
            manifest.assessmentDecision(),
            string(assessment, "decision"),
            "assessment decision");
    }

    private static void verifyDirectoryMembership(Path directory) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(
                "missing historical rediscovery run directory: " + directory);
        }
        try (var paths = Files.list(directory)) {
            List<String> names = paths.map(path -> {
                if (Files.isSymbolicLink(path)) {
                    throw new IllegalArgumentException(
                        "symbolic links are not accepted in run artifacts: " + path);
                }
                return path.getFileName().toString();
            }).sorted().toList();
            List<String> expected = EXPECTED_FILES.stream().sorted().toList();
            if (!expected.equals(names)) {
                throw new IllegalArgumentException(
                    "historical rediscovery run membership differs: expected="
                        + expected + ", actual=" + names);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(
                "Could not inspect historical rediscovery run", exception);
        }
    }

    private static void requireManifestAbsent(Path directory) {
        Path manifest = directory.resolve(MANIFEST_FILE_NAME);
        if (Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(
                "begin must invalidate the previous run manifest before commit");
        }
    }

    private static byte[] readBytes(Path path, long maximum, String label) {
        Path normalized = Objects.requireNonNull(path, "path")
            .toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized)
                || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(label + " must be a regular file");
        }
        try {
            long length = Files.size(normalized);
            if (length < 1L || length > maximum) {
                throw new IllegalArgumentException(
                    label + " size is outside the bounded range: " + length);
            }
            return Files.readAllBytes(normalized);
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read " + label, exception);
        }
    }

    private static String readUtf8(Path path, long maximum, String label) {
        return new String(readBytes(path, maximum, label), StandardCharsets.UTF_8);
    }

    private static Path normalizedDirectory(Path directory) {
        return Objects.requireNonNull(directory, "outputDirectory")
            .toAbsolutePath().normalize();
    }

    private static void rejectSymbolicAncestry(Path path) {
        for (Path current = path; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException(
                    "symbolic path ancestry is not accepted: " + current);
            }
        }
    }

    private static void requireEqual(
        String expected,
        String actual,
        String label
    ) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(
                label + " differs: expected=" + expected + ", actual=" + actual);
        }
    }

    private static Map<String, Object> stringKeyed(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (!(key instanceof String text)) {
                throw new IllegalArgumentException("JSON object key must be a string");
            }
            result.put(text, value);
        });
        return result;
    }

    private static Map<String, Object> object(
        Map<String, Object> values,
        String key
    ) {
        Object value = values.get(key);
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(key + " must be a JSON object");
        }
        return stringKeyed(map);
    }

    private static List<?> list(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof List<?> items)) {
            throw new IllegalArgumentException(key + " must be a JSON array");
        }
        return items;
    }

    private static String string(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(key + " must be a non-blank string");
        }
        return text;
    }

    private static int positiveInt(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(key + " must be numeric");
        }
        double decimal = number.doubleValue();
        int integer = number.intValue();
        if (!Double.isFinite(decimal) || decimal != integer || integer < 1) {
            throw new IllegalArgumentException(key + " must be a positive integer");
        }
        return integer;
    }

    private static void requireKeys(
        Map<String, Object> values,
        Set<String> expected,
        String label
    ) {
        if (!values.keySet().equals(expected)) {
            throw new IllegalArgumentException(
                label + " keys differ: expected=" + expected
                    + ", actual=" + values.keySet());
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }

    private static String requireRawSha256(String value, String label) {
        String text = requireText(value, label);
        if (!text.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                label + " must be lowercase hexadecimal SHA-256");
        }
        return text;
    }

    private static String requirePrefixedSha256(String value, String label) {
        String text = requireText(value, label);
        if (!text.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(label + " must be prefixed SHA-256");
        }
        return text;
    }

    private static String sha256(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String manifestHash(
        String corpusSchema,
        String corpusSha256,
        String atlasSchema,
        String inventoryRevision,
        String claimBoundary,
        String assessmentDecision,
        int caseCount,
        List<Artifact> artifacts
    ) {
        String material = SCHEMA
            + "\nevidenceStatus=" + EVIDENCE_STATUS
            + "\ncorpusSchema=" + corpusSchema
            + "\ncorpusSha256=" + corpusSha256
            + "\natlasSchema=" + atlasSchema
            + "\ninventoryRevision=" + inventoryRevision
            + "\nclaimBoundary=" + claimBoundary
            + "\nassessmentDecision=" + assessmentDecision
            + "\ncaseCount=" + caseCount
            + "\nartifacts=" + String.join(
                "\nartifact=",
                artifacts.stream().map(Artifact::canonicalMaterial).toList())
            + "\ncommitProtocol=" + COMMIT_PROTOCOL;
        return sha256(material.getBytes(StandardCharsets.UTF_8));
    }

    private static List<Artifact> normalizeArtifacts(List<Artifact> values) {
        if (values == null || values.size() != ArtifactRole.values().length) {
            throw new IllegalArgumentException(
                "historical rediscovery run requires exactly two artifacts");
        }
        List<Artifact> normalized = new ArrayList<>();
        EnumSet<ArtifactRole> roles = EnumSet.noneOf(ArtifactRole.class);
        for (Artifact value : values) {
            Artifact artifact = Objects.requireNonNull(value, "artifact");
            if (!roles.add(artifact.role())) {
                throw new IllegalArgumentException(
                    "duplicate historical rediscovery artifact role: "
                        + artifact.role());
            }
            normalized.add(artifact);
        }
        if (!roles.equals(EnumSet.allOf(ArtifactRole.class))) {
            throw new IllegalArgumentException(
                "historical rediscovery run is missing an artifact role");
        }
        normalized.sort(Comparator.comparing(Artifact::fileName));
        return List.copyOf(normalized);
    }

    public enum ArtifactRole {
        ATLAS_JSON("historical-rediscovery-atlas.json", "application/json"),
        ATLAS_MARKDOWN("historical-rediscovery-atlas.md", "text/markdown");

        private final String fileName;
        private final String mediaType;

        ArtifactRole(String fileName, String mediaType) {
            this.fileName = fileName;
            this.mediaType = mediaType;
        }

        public String fileName() {
            return fileName;
        }

        public String mediaType() {
            return mediaType;
        }
    }

    public record Artifact(
        String fileName,
        ArtifactRole role,
        String mediaType,
        String byteHash,
        long byteLength
    ) {
        public Artifact {
            Objects.requireNonNull(role, "role");
            if (!role.fileName().equals(fileName)
                    || !role.mediaType().equals(mediaType)) {
                throw new IllegalArgumentException(
                    "artifact name and media type must match its role");
            }
            byteHash = requirePrefixedSha256(byteHash, "byteHash");
            if (byteLength < 1L || byteLength > MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException(
                    "artifact byteLength is outside the bounded range");
            }
        }

        private String canonicalMaterial() {
            return fileName + '|' + role.name() + '|' + mediaType
                + '|' + byteHash + '|' + byteLength;
        }

        private static Artifact parse(Object raw) {
            if (!(raw instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException(
                    "run artifact entry must be a JSON object");
            }
            Map<String, Object> values = stringKeyed(map);
            requireKeys(values, ARTIFACT_KEYS, "run artifact");
            ArtifactRole role;
            try {
                role = ArtifactRole.valueOf(string(values, "role"));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                    "unsupported historical rediscovery artifact role",
                    exception);
            }
            return new Artifact(
                string(values, "fileName"),
                role,
                string(values, "mediaType"),
                string(values, "byteHash"),
                positiveInt(values, "byteLength")
            );
        }
    }

    public record Manifest(
        String schema,
        String evidenceStatus,
        String corpusSchema,
        String corpusSha256,
        String atlasSchema,
        String inventoryRevision,
        String claimBoundary,
        String assessmentDecision,
        int caseCount,
        List<Artifact> artifacts,
        String commitProtocol,
        String contentHash
    ) {
        public Manifest {
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported historical rediscovery run schema");
            }
            if (!EVIDENCE_STATUS.equals(evidenceStatus)) {
                throw new IllegalArgumentException(
                    "unexpected historical rediscovery evidence status");
            }
            if (!HistoricalRediscoveryCorpus.SCHEMA.equals(corpusSchema)) {
                throw new IllegalArgumentException(
                    "unsupported historical rediscovery corpus schema");
            }
            corpusSha256 = requireRawSha256(corpusSha256, "corpusSha256");
            if (!HistoricalRediscoveryAtlas.SCHEMA.equals(atlasSchema)) {
                throw new IllegalArgumentException(
                    "unsupported historical rediscovery atlas schema");
            }
            inventoryRevision = requireText(
                inventoryRevision,
                "inventoryRevision");
            claimBoundary = requireText(claimBoundary, "claimBoundary");
            assessmentDecision = requireText(
                assessmentDecision,
                "assessmentDecision");
            if (caseCount < 1) {
                throw new IllegalArgumentException("caseCount must be positive");
            }
            artifacts = normalizeArtifacts(artifacts);
            if (!COMMIT_PROTOCOL.equals(commitProtocol)) {
                throw new IllegalArgumentException(
                    "historical rediscovery run must use manifest-last commit");
            }
            contentHash = requirePrefixedSha256(contentHash, "contentHash");
            String expected = manifestHash(
                corpusSchema,
                corpusSha256,
                atlasSchema,
                inventoryRevision,
                claimBoundary,
                assessmentDecision,
                caseCount,
                artifacts);
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "historical rediscovery run contentHash mismatch");
            }
        }

        public static Manifest create(
            Corpus corpus,
            AtlasReport report,
            List<Artifact> artifacts
        ) {
            List<Artifact> normalized = normalizeArtifacts(artifacts);
            String decision = report.assessment().decision().name();
            String hash = manifestHash(
                corpus.schema(),
                corpus.contentSha256(),
                report.schema(),
                corpus.inventoryRevision(),
                corpus.claimBoundary(),
                decision,
                corpus.cases().size(),
                normalized);
            return new Manifest(
                SCHEMA,
                EVIDENCE_STATUS,
                corpus.schema(),
                corpus.contentSha256(),
                report.schema(),
                corpus.inventoryRevision(),
                corpus.claimBoundary(),
                decision,
                corpus.cases().size(),
                normalized,
                COMMIT_PROTOCOL,
                hash
            );
        }

        public static Manifest parse(String json) {
            Map<String, Object> values = new JsonReader(json).readObject();
            requireKeys(values, ROOT_KEYS, "historical rediscovery run");
            List<Artifact> parsedArtifacts = list(values, "artifacts").stream()
                .map(Artifact::parse)
                .toList();
            return new Manifest(
                string(values, "schema"),
                string(values, "evidenceStatus"),
                string(values, "corpusSchema"),
                string(values, "corpusSha256"),
                string(values, "atlasSchema"),
                string(values, "inventoryRevision"),
                string(values, "claimBoundary"),
                string(values, "assessmentDecision"),
                positiveInt(values, "caseCount"),
                parsedArtifacts,
                string(values, "commitProtocol"),
                string(values, "contentHash")
            );
        }

        public String toCanonicalJson() {
            JsonWriter writer = new JsonWriter().beginObject();
            writer.property("schema", schema);
            writer.property("evidenceStatus", evidenceStatus);
            writer.property("corpusSchema", corpusSchema);
            writer.property("corpusSha256", corpusSha256);
            writer.property("atlasSchema", atlasSchema);
            writer.property("inventoryRevision", inventoryRevision);
            writer.property("claimBoundary", claimBoundary);
            writer.property("assessmentDecision", assessmentDecision);
            writer.property("caseCount", caseCount);
            writer.array("artifacts", array -> artifacts.forEach(artifact ->
                array.objectValue(object -> {
                    object.property("fileName", artifact.fileName());
                    object.property("role", artifact.role().name());
                    object.property("mediaType", artifact.mediaType());
                    object.property("byteHash", artifact.byteHash());
                    object.property("byteLength", artifact.byteLength());
                })));
            writer.property("commitProtocol", commitProtocol);
            writer.property("contentHash", contentHash);
            return writer.endObject().toString();
        }
    }

    public record VerifiedRun(
        Path directory,
        Path manifestPath,
        Manifest manifest
    ) {
        public VerifiedRun {
            directory = Objects.requireNonNull(directory, "directory")
                .toAbsolutePath().normalize();
            manifestPath = Objects.requireNonNull(manifestPath, "manifestPath")
                .toAbsolutePath().normalize();
            Objects.requireNonNull(manifest, "manifest");
        }
    }
}
