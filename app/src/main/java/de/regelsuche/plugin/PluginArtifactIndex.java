package de.regelsuche.plugin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable, content-addressed catalog of externally published extension
 * artifacts. The index describes artifacts; it does not download or install
 * them and therefore cannot bypass the separate pre-classloading trust gate.
 */
public record PluginArtifactIndex(
    String schema,
    String indexId,
    String revision,
    String curatorId,
    List<Entry> entries,
    String contentHash
) {
    public static final String SCHEMA = "regelsuche.plugin-artifact-index/v1";

    private static final Pattern SEMVER = Pattern.compile(
        "0|[1-9][0-9]*\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)"
            + "(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?"
            + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?");
    private static final ObjectMapper JSON = new ObjectMapper()
        .findAndRegisterModules()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public PluginArtifactIndex {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("unsupported plugin artifact index schema");
        }
        indexId = PluginSignatureManifest.requireIdentifier(indexId, "indexId");
        revision = PluginSignatureManifest.requireIdentifier(revision, "revision");
        curatorId = PluginSignatureManifest.requireIdentifier(curatorId, "curatorId");
        entries = normalizeEntries(entries);
        validateUniqueEntries(entries);
        validateDependencies(entries);
        contentHash = PluginSignatureManifest.requireSha256(contentHash, "contentHash");
        String expected = hash(canonicalJson(
            indexId, revision, curatorId, entries, false, ""));
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException("plugin artifact index contentHash mismatch");
        }
    }

    public static PluginArtifactIndex create(
        String indexId,
        String revision,
        String curatorId,
        List<Entry> entries
    ) {
        String normalizedIndexId = PluginSignatureManifest.requireIdentifier(
            indexId, "indexId");
        String normalizedRevision = PluginSignatureManifest.requireIdentifier(
            revision, "revision");
        String normalizedCurator = PluginSignatureManifest.requireIdentifier(
            curatorId, "curatorId");
        List<Entry> normalizedEntries = normalizeEntries(entries);
        validateUniqueEntries(normalizedEntries);
        validateDependencies(normalizedEntries);
        String hash = hash(canonicalJson(
            normalizedIndexId,
            normalizedRevision,
            normalizedCurator,
            normalizedEntries,
            false,
            ""));
        return new PluginArtifactIndex(
            SCHEMA,
            normalizedIndexId,
            normalizedRevision,
            normalizedCurator,
            normalizedEntries,
            hash);
    }

    public static PluginArtifactIndex load(Path path) {
        Objects.requireNonNull(path, "path");
        try {
            IndexDto dto = JSON.readValue(
                Files.readString(path, StandardCharsets.UTF_8), IndexDto.class);
            if (dto == null) {
                throw new IllegalArgumentException("plugin artifact index must not be empty");
            }
            List<Entry> parsedEntries = values(dto.entries).stream()
                .map(PluginArtifactIndex::toEntry)
                .toList();
            return new PluginArtifactIndex(
                dto.schema,
                dto.indexId,
                dto.revision,
                dto.curatorId,
                parsedEntries,
                dto.contentHash);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid plugin artifact index: " + path, exception);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unable to read plugin artifact index: " + path, exception);
        }
    }

    public Path write(Path path) {
        Objects.requireNonNull(path, "path");
        try {
            Path parent = path.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, toCanonicalJson(), StandardCharsets.UTF_8);
            return path;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to write plugin artifact index", exception);
        }
    }

    public String toCanonicalJson() {
        return canonicalJson(indexId, revision, curatorId, entries, true, contentHash);
    }

    public List<Entry> componentVersions(ArtifactKind kind, String componentId) {
        Objects.requireNonNull(kind, "kind");
        String normalizedComponent = PluginSignatureManifest.requireIdentifier(
            componentId, "componentId");
        return entries.stream()
            .filter(entry -> entry.kind() == kind
                && entry.componentId().equals(normalizedComponent))
            .sorted(Comparator.comparing(Entry::version, PluginArtifactIndex::compareVersions)
                .reversed()
                .thenComparing(Entry::artifactId))
            .toList();
    }

    private static Entry toEntry(EntryDto dto) {
        Objects.requireNonNull(dto, "artifact index entry");
        ArtifactKind kind;
        try {
            kind = ArtifactKind.valueOf(dto.kind);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Unsupported artifact kind: " + dto.kind, exception);
        }
        List<Dependency> dependencies = values(dto.dependencies).stream()
            .map(item -> new Dependency(
                item.kind == null || item.kind.isBlank()
                    ? kind
                    : enumValue(ArtifactKind.class, item.kind, "dependency kind"),
                item.componentId,
                item.versionConstraint,
                item.optional))
            .toList();
        return new Entry(
            dto.artifactId,
            kind,
            dto.componentId,
            dto.version,
            dto.apiVersion,
            dto.minimumCoreVersion,
            dto.maximumCoreVersionExclusive,
            values(dto.capabilities),
            dependencies,
            dto.artifactFileName,
            dto.artifactSha256,
            dto.artifactUri,
            dto.signatureManifestUri,
            dto.provenanceUri,
            dto.publisherId,
            dto.identityHash);
    }

    private static String canonicalJson(
        String indexId,
        String revision,
        String curatorId,
        List<Entry> entries,
        boolean includeHash,
        String contentHash
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", SCHEMA);
        payload.put("indexId", indexId);
        payload.put("revision", revision);
        payload.put("curatorId", curatorId);
        payload.put("entries", entries.stream().map(Entry::payload).toList());
        if (includeHash) {
            payload.put("contentHash", contentHash);
        }
        try {
            return JSON.writeValueAsString(payload) + "\n";
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize plugin artifact index", exception);
        }
    }

    private static List<Entry> normalizeEntries(List<Entry> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("plugin artifact index requires an entry");
        }
        return values.stream()
            .map(value -> Objects.requireNonNull(value, "artifact index entry"))
            .sorted(Comparator
                .comparing((Entry entry) -> entry.kind().name())
                .thenComparing(Entry::componentId)
                .thenComparing(Entry::version, PluginArtifactIndex::compareVersions)
                .thenComparing(Entry::artifactId))
            .toList();
    }

    private static void validateUniqueEntries(List<Entry> entries) {
        Set<String> artifactIds = new HashSet<>();
        Set<String> coordinates = new HashSet<>();
        Set<String> hashes = new HashSet<>();
        for (Entry entry : entries) {
            if (!artifactIds.add(entry.artifactId())) {
                throw new IllegalArgumentException(
                    "duplicate artifact ID: " + entry.artifactId());
            }
            String coordinate = entry.kind().name() + "\u0000"
                + entry.componentId() + "\u0000" + entry.version();
            if (!coordinates.add(coordinate)) {
                throw new IllegalArgumentException(
                    "duplicate artifact coordinate: "
                        + entry.kind() + "/" + entry.componentId() + "/" + entry.version());
            }
            if (!hashes.add(entry.artifactSha256())) {
                throw new IllegalArgumentException(
                    "artifact bytes are published under multiple identities: "
                        + entry.artifactSha256());
            }
        }
    }

    private static void validateDependencies(List<Entry> entries) {
        for (Entry entry : entries) {
            for (Dependency dependency : entry.dependencies()) {
                if (dependency.optional()) {
                    continue;
                }
                boolean present = entries.stream().anyMatch(candidate ->
                    candidate.kind() == dependency.kind()
                        && candidate.componentId().equals(dependency.componentId())
                        && dependency.matches(candidate.version()));
                if (!present) {
                    throw new IllegalArgumentException(
                        "required dependency is not published: "
                            + dependency.kind() + "/" + dependency.componentId()
                            + " " + dependency.versionConstraint());
                }
            }
        }
    }

    static int compareVersions(String left, String right) {
        Version a = Version.parse(left);
        Version b = Version.parse(right);
        int core = Integer.compare(a.major(), b.major());
        if (core == 0) {
            core = Integer.compare(a.minor(), b.minor());
        }
        if (core == 0) {
            core = Integer.compare(a.patch(), b.patch());
        }
        if (core != 0) {
            return core;
        }
        if (a.prerelease().isEmpty() && b.prerelease().isEmpty()) {
            return 0;
        }
        if (a.prerelease().isEmpty()) {
            return 1;
        }
        if (b.prerelease().isEmpty()) {
            return -1;
        }
        return a.prerelease().compareTo(b.prerelease());
    }

    static String requireVersion(String value, String field) {
        if (value == null || !SEMVER.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be semantic version x.y.z");
        }
        return value;
    }

    private static String hash(String json) {
        return PluginArtifactVerifier.sha256(json.getBytes(StandardCharsets.UTF_8));
    }

    private static <E extends Enum<E>> E enumValue(
        Class<E> type,
        String value,
        String field
    ) {
        try {
            return Enum.valueOf(type, value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Unsupported " + field + ": " + value, exception);
        }
    }

    private static <T> List<T> values(List<T> items) {
        return items == null ? List.of() : List.copyOf(items);
    }

    public enum ArtifactKind {
        JAVA_PLUGIN,
        RULE_PACKAGE,
        KNOWLEDGE_PACK
    }

    public record Entry(
        String artifactId,
        ArtifactKind kind,
        String componentId,
        String version,
        String apiVersion,
        String minimumCoreVersion,
        String maximumCoreVersionExclusive,
        List<String> capabilities,
        List<Dependency> dependencies,
        String artifactFileName,
        String artifactSha256,
        String artifactUri,
        String signatureManifestUri,
        String provenanceUri,
        String publisherId,
        String identityHash
    ) {
        public Entry {
            artifactId = PluginSignatureManifest.requireIdentifier(artifactId, "artifactId");
            Objects.requireNonNull(kind, "kind");
            componentId = PluginSignatureManifest.requireIdentifier(componentId, "componentId");
            version = requireVersion(version, "version");
            apiVersion = PluginSignatureManifest.requireIdentifier(apiVersion, "apiVersion");
            minimumCoreVersion = requireVersion(minimumCoreVersion, "minimumCoreVersion");
            maximumCoreVersionExclusive = maximumCoreVersionExclusive == null
                ? ""
                : maximumCoreVersionExclusive.trim();
            if (!maximumCoreVersionExclusive.isEmpty()) {
                maximumCoreVersionExclusive = requireVersion(
                    maximumCoreVersionExclusive, "maximumCoreVersionExclusive");
                if (compareVersions(minimumCoreVersion, maximumCoreVersionExclusive) >= 0) {
                    throw new IllegalArgumentException(
                        "maximumCoreVersionExclusive must exceed minimumCoreVersion");
                }
            }
            capabilities = normalizeCapabilities(capabilities);
            dependencies = normalizeDependencies(dependencies);
            artifactFileName = requireArtifactFileName(artifactFileName, kind);
            artifactSha256 = PluginSignatureManifest.requireSha256(
                artifactSha256, "artifactSha256");
            artifactUri = requireUri(artifactUri, "artifactUri", false);
            signatureManifestUri = requireUri(
                signatureManifestUri, "signatureManifestUri", true);
            provenanceUri = requireUri(provenanceUri, "provenanceUri", false);
            publisherId = PluginSignatureManifest.requireIdentifier(
                publisherId, "publisherId");
            identityHash = PluginSignatureManifest.requireSha256(identityHash, "identityHash");
            String expected = identityHash(
                artifactId,
                kind,
                componentId,
                version,
                apiVersion,
                minimumCoreVersion,
                maximumCoreVersionExclusive,
                capabilities,
                dependencies,
                artifactFileName,
                artifactSha256,
                artifactUri,
                signatureManifestUri,
                provenanceUri,
                publisherId);
            if (!expected.equals(identityHash)) {
                throw new IllegalArgumentException("artifact identityHash mismatch");
            }
        }

        public static Entry create(
            String artifactId,
            ArtifactKind kind,
            String componentId,
            String version,
            String apiVersion,
            String minimumCoreVersion,
            String maximumCoreVersionExclusive,
            List<String> capabilities,
            List<Dependency> dependencies,
            String artifactFileName,
            String artifactSha256,
            String artifactUri,
            String signatureManifestUri,
            String provenanceUri,
            String publisherId
        ) {
            String normalizedArtifactId = PluginSignatureManifest.requireIdentifier(
                artifactId, "artifactId");
            Objects.requireNonNull(kind, "kind");
            String normalizedComponent = PluginSignatureManifest.requireIdentifier(
                componentId, "componentId");
            String normalizedVersion = requireVersion(version, "version");
            String normalizedApi = PluginSignatureManifest.requireIdentifier(
                apiVersion, "apiVersion");
            String normalizedMinimumCore = requireVersion(
                minimumCoreVersion, "minimumCoreVersion");
            String normalizedMaximumCore = maximumCoreVersionExclusive == null
                ? ""
                : maximumCoreVersionExclusive.trim();
            if (!normalizedMaximumCore.isEmpty()) {
                normalizedMaximumCore = requireVersion(
                    normalizedMaximumCore, "maximumCoreVersionExclusive");
            }
            List<String> normalizedCapabilities = normalizeCapabilities(capabilities);
            List<Dependency> normalizedDependencies = normalizeDependencies(dependencies);
            String normalizedFileName = requireArtifactFileName(artifactFileName, kind);
            String normalizedArtifactHash = PluginSignatureManifest.requireSha256(
                artifactSha256, "artifactSha256");
            String normalizedArtifactUri = requireUri(artifactUri, "artifactUri", false);
            String normalizedSignatureUri = requireUri(
                signatureManifestUri, "signatureManifestUri", true);
            String normalizedProvenance = requireUri(
                provenanceUri, "provenanceUri", false);
            String normalizedPublisher = PluginSignatureManifest.requireIdentifier(
                publisherId, "publisherId");
            String identityHash = identityHash(
                normalizedArtifactId,
                kind,
                normalizedComponent,
                normalizedVersion,
                normalizedApi,
                normalizedMinimumCore,
                normalizedMaximumCore,
                normalizedCapabilities,
                normalizedDependencies,
                normalizedFileName,
                normalizedArtifactHash,
                normalizedArtifactUri,
                normalizedSignatureUri,
                normalizedProvenance,
                normalizedPublisher);
            return new Entry(
                normalizedArtifactId,
                kind,
                normalizedComponent,
                normalizedVersion,
                normalizedApi,
                normalizedMinimumCore,
                normalizedMaximumCore,
                normalizedCapabilities,
                normalizedDependencies,
                normalizedFileName,
                normalizedArtifactHash,
                normalizedArtifactUri,
                normalizedSignatureUri,
                normalizedProvenance,
                normalizedPublisher,
                identityHash);
        }

        public boolean supportsCore(String coreVersion) {
            String normalizedCore = requireVersion(coreVersion, "coreVersion");
            return compareVersions(normalizedCore, minimumCoreVersion) >= 0
                && (maximumCoreVersionExclusive.isEmpty()
                    || compareVersions(normalizedCore, maximumCoreVersionExclusive) < 0);
        }

        Map<String, Object> payload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("artifactId", artifactId);
            payload.put("kind", kind.name());
            payload.put("componentId", componentId);
            payload.put("version", version);
            payload.put("apiVersion", apiVersion);
            payload.put("minimumCoreVersion", minimumCoreVersion);
            payload.put("maximumCoreVersionExclusive", maximumCoreVersionExclusive);
            payload.put("capabilities", capabilities);
            payload.put("dependencies", dependencies.stream()
                .map(Dependency::payload)
                .toList());
            payload.put("artifactFileName", artifactFileName);
            payload.put("artifactSha256", artifactSha256);
            payload.put("artifactUri", artifactUri);
            payload.put("signatureManifestUri", signatureManifestUri);
            payload.put("provenanceUri", provenanceUri);
            payload.put("publisherId", publisherId);
            payload.put("identityHash", identityHash);
            return payload;
        }

        private static String identityHash(
            String artifactId,
            ArtifactKind kind,
            String componentId,
            String version,
            String apiVersion,
            String minimumCoreVersion,
            String maximumCoreVersionExclusive,
            List<String> capabilities,
            List<Dependency> dependencies,
            String artifactFileName,
            String artifactSha256,
            String artifactUri,
            String signatureManifestUri,
            String provenanceUri,
            String publisherId
        ) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("artifactId", artifactId);
            payload.put("kind", kind.name());
            payload.put("componentId", componentId);
            payload.put("version", version);
            payload.put("apiVersion", apiVersion);
            payload.put("minimumCoreVersion", minimumCoreVersion);
            payload.put("maximumCoreVersionExclusive", maximumCoreVersionExclusive);
            payload.put("capabilities", capabilities);
            payload.put("dependencies", dependencies.stream()
                .map(Dependency::payload)
                .toList());
            payload.put("artifactFileName", artifactFileName);
            payload.put("artifactSha256", artifactSha256);
            payload.put("artifactUri", artifactUri);
            payload.put("signatureManifestUri", signatureManifestUri);
            payload.put("provenanceUri", provenanceUri);
            payload.put("publisherId", publisherId);
            try {
                return hash(JSON.writeValueAsString(payload));
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("Unable to hash artifact identity", exception);
            }
        }
    }

    public record Dependency(
        ArtifactKind kind,
        String componentId,
        String versionConstraint,
        boolean optional
    ) {
        public Dependency {
            Objects.requireNonNull(kind, "kind");
            componentId = PluginSignatureManifest.requireIdentifier(
                componentId, "dependency componentId");
            versionConstraint = requireConstraint(versionConstraint);
        }

        public boolean matches(String version) {
            String normalizedVersion = requireVersion(version, "dependency version");
            return "any".equals(versionConstraint)
                || normalizedVersion.equals(versionConstraint.substring(1));
        }

        Map<String, Object> payload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("kind", kind.name());
            payload.put("componentId", componentId);
            payload.put("versionConstraint", versionConstraint);
            payload.put("optional", optional);
            return payload;
        }

        private static String requireConstraint(String value) {
            if ("any".equals(value)) {
                return value;
            }
            if (value == null || !value.startsWith("=")) {
                throw new IllegalArgumentException(
                    "versionConstraint must be 'any' or '=x.y.z'");
            }
            return "=" + requireVersion(value.substring(1), "dependency versionConstraint");
        }
    }

    private static List<String> normalizeCapabilities(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .map(value -> PluginSignatureManifest.requireIdentifier(
                value.toLowerCase(Locale.ROOT), "capability"))
            .distinct()
            .sorted()
            .toList();
    }

    private static List<Dependency> normalizeDependencies(List<Dependency> values) {
        if (values == null) {
            return List.of();
        }
        Set<String> identities = new HashSet<>();
        List<Dependency> normalized = new ArrayList<>();
        for (Dependency dependency : values) {
            Dependency item = Objects.requireNonNull(dependency, "dependency");
            String identity = item.kind().name() + "\u0000" + item.componentId();
            if (!identities.add(identity)) {
                throw new IllegalArgumentException(
                    "duplicate dependency: " + item.kind() + "/" + item.componentId());
            }
            normalized.add(item);
        }
        normalized.sort(Comparator
            .comparing((Dependency item) -> item.kind().name())
            .thenComparing(Dependency::componentId));
        return List.copyOf(normalized);
    }

    private static String requireArtifactFileName(String value, ArtifactKind kind) {
        if (value == null || value.isBlank()
                || !Path.of(value).getFileName().toString().equals(value)
                || value.contains("/") || value.contains("\\")) {
            throw new IllegalArgumentException("artifactFileName must be a simple file name");
        }
        String lower = value.toLowerCase(Locale.ROOT);
        boolean supported = switch (kind) {
            case JAVA_PLUGIN -> lower.endsWith(".jar");
            case RULE_PACKAGE -> lower.endsWith(".regelsuche") || lower.endsWith(".rules");
            case KNOWLEDGE_PACK -> lower.endsWith(".json") || lower.endsWith(".zip")
                || lower.endsWith(".yaml") || lower.endsWith(".yml");
        };
        if (!supported) {
            throw new IllegalArgumentException(
                "artifactFileName extension does not match " + kind);
        }
        return value;
    }

    private static String requireUri(String value, String field, boolean optional) {
        if ((value == null || value.isBlank()) && optional) {
            return "";
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (scheme == null
                    || !("https".equalsIgnoreCase(scheme) || "file".equalsIgnoreCase(scheme))
                    || uri.getFragment() != null
                    || uri.getUserInfo() != null) {
                throw new IllegalArgumentException(
                    field + " must be an https or file URI without user info or fragment");
            }
            return uri.normalize().toString();
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException(field + " is not a valid URI", exception);
        }
    }

    private record Version(int major, int minor, int patch, String prerelease) {
        static Version parse(String value) {
            String normalized = requireVersion(value, "version");
            String withoutBuild = normalized.split("\\+", 2)[0];
            String[] releaseAndPre = withoutBuild.split("-", 2);
            String[] numbers = releaseAndPre[0].split("\\.");
            return new Version(
                Integer.parseInt(numbers[0]),
                Integer.parseInt(numbers[1]),
                Integer.parseInt(numbers[2]),
                releaseAndPre.length == 2 ? releaseAndPre[1] : "");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public static final class IndexDto {
        public String schema;
        public String indexId;
        public String revision;
        public String curatorId;
        public List<EntryDto> entries;
        public String contentHash;
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public static final class EntryDto {
        public String artifactId;
        public String kind;
        public String componentId;
        public String version;
        public String apiVersion;
        public String minimumCoreVersion;
        public String maximumCoreVersionExclusive;
        public List<String> capabilities;
        public List<DependencyDto> dependencies;
        public String artifactFileName;
        public String artifactSha256;
        public String artifactUri;
        public String signatureManifestUri;
        public String provenanceUri;
        public String publisherId;
        public String identityHash;
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public static final class DependencyDto {
        public String kind;
        public String componentId;
        public String versionConstraint;
        public boolean optional;
    }
}
