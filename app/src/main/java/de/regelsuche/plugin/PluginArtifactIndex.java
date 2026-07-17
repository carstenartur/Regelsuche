package de.regelsuche.plugin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigInteger;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable, content-addressed catalog of externally published extension
 * artifacts. The index describes artifacts; it never downloads or installs
 * them and cannot bypass the separate pre-classloading trust gate.
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

    private static final ObjectMapper JSON = new ObjectMapper()
        .findAndRegisterModules()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public PluginArtifactIndex {
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("unsupported plugin artifact index schema");
        }
        indexId = identifier(indexId, "indexId");
        revision = identifier(revision, "revision");
        curatorId = identifier(curatorId, "curatorId");
        entries = normalizeEntries(entries);
        validateUniqueEntries(entries);
        validateRequiredDependencies(entries);
        contentHash = sha256(contentHash, "contentHash");
        String expected = hash(render(indexId, revision, curatorId, entries, false, ""));
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
        String normalizedIndex = identifier(indexId, "indexId");
        String normalizedRevision = identifier(revision, "revision");
        String normalizedCurator = identifier(curatorId, "curatorId");
        List<Entry> normalizedEntries = normalizeEntries(entries);
        validateUniqueEntries(normalizedEntries);
        validateRequiredDependencies(normalizedEntries);
        String contentHash = hash(render(
            normalizedIndex,
            normalizedRevision,
            normalizedCurator,
            normalizedEntries,
            false,
            ""));
        return new PluginArtifactIndex(
            SCHEMA,
            normalizedIndex,
            normalizedRevision,
            normalizedCurator,
            normalizedEntries,
            contentHash);
    }

    public static PluginArtifactIndex load(Path path) {
        Objects.requireNonNull(path, "path");
        try {
            IndexDto dto = JSON.readValue(
                Files.readString(path, StandardCharsets.UTF_8), IndexDto.class);
            if (dto == null) {
                throw new IllegalArgumentException("plugin artifact index must not be empty");
            }
            return new PluginArtifactIndex(
                dto.schema,
                dto.indexId,
                dto.revision,
                dto.curatorId,
                requiredList(dto.entries, "entries").stream()
                    .map(PluginArtifactIndex::entry)
                    .toList(),
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
        return render(indexId, revision, curatorId, entries, true, contentHash);
    }

    public List<Entry> componentVersions(ArtifactKind kind, String componentId) {
        Objects.requireNonNull(kind, "kind");
        String component = identifier(componentId, "componentId");
        return entries.stream()
            .filter(item -> item.kind() == kind && item.componentId().equals(component))
            .sorted(Comparator.comparing(Entry::version, PluginArtifactIndex::compareVersions)
                .reversed()
                .thenComparing(Entry::artifactId))
            .toList();
    }

    static int compareVersions(String left, String right) {
        return SemanticVersion.parse(left).compareTo(SemanticVersion.parse(right));
    }

    static String requireVersion(String value, String field) {
        return SemanticVersion.parse(value, field).source();
    }

    private static Entry entry(EntryDto dto) {
        Objects.requireNonNull(dto, "artifact index entry");
        ArtifactKind kind = enumValue(ArtifactKind.class, dto.kind, "artifact kind");
        String maximumCoreVersionExclusive = requiredValue(
            dto.maximumCoreVersionExclusive, "maximumCoreVersionExclusive");
        String signatureManifestUri = requiredValue(
            dto.signatureManifestUri, "signatureManifestUri");
        List<String> rawCapabilities = requiredList(dto.capabilities, "capabilities");
        for (String capability : rawCapabilities) {
            if (capability == null) {
                throw new IllegalArgumentException("capabilities must not contain null");
            }
            identifier(capability, "capability");
        }
        List<Dependency> parsedDependencies = requiredList(
            dto.dependencies, "dependencies").stream()
            .map(item -> {
                if (item == null) {
                    throw new IllegalArgumentException(
                        "dependencies must not contain null");
                }
                return new Dependency(
                    enumValue(ArtifactKind.class, item.kind, "dependency kind"),
                    item.componentId,
                    item.versionConstraint,
                    requiredBoolean(item.optional, "dependency optional"));
            })
            .toList();
        return new Entry(
            dto.artifactId,
            kind,
            dto.componentId,
            dto.version,
            dto.apiVersion,
            dto.minimumCoreVersion,
            maximumCoreVersionExclusive,
            rawCapabilities,
            parsedDependencies,
            dto.artifactFileName,
            dto.artifactSha256,
            dto.artifactUri,
            signatureManifestUri,
            dto.provenanceUri,
            dto.publisherId,
            dto.identityHash);
    }

    private static String render(
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
        return json(payload) + "\n";
    }

    private static List<Entry> normalizeEntries(List<Entry> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("plugin artifact index requires an entry");
        }
        return values.stream()
            .map(value -> Objects.requireNonNull(value, "artifact index entry"))
            .sorted(Comparator
                .comparing((Entry item) -> item.kind().name())
                .thenComparing(Entry::componentId)
                .thenComparing(Entry::version, PluginArtifactIndex::compareVersions)
                .thenComparing(Entry::artifactId))
            .toList();
    }

    private static void validateUniqueEntries(List<Entry> entries) {
        Set<String> artifactIds = new HashSet<>();
        Set<String> coordinates = new HashSet<>();
        Set<String> hashes = new HashSet<>();
        for (Entry item : entries) {
            if (!artifactIds.add(item.artifactId())) {
                throw new IllegalArgumentException("duplicate artifact ID: " + item.artifactId());
            }
            String coordinate = item.kind() + "\u0000" + item.componentId()
                + "\u0000" + item.version();
            if (!coordinates.add(coordinate)) {
                throw new IllegalArgumentException(
                    "duplicate artifact coordinate: " + item.kind() + "/"
                        + item.componentId() + "/" + item.version());
            }
            if (!hashes.add(item.artifactSha256())) {
                throw new IllegalArgumentException(
                    "artifact bytes are published under multiple identities: "
                        + item.artifactSha256());
            }
        }
    }

    private static void validateRequiredDependencies(List<Entry> entries) {
        for (Entry item : entries) {
            for (Dependency dependency : item.dependencies()) {
                if (dependency.optional()) {
                    continue;
                }
                boolean published = entries.stream().anyMatch(candidate ->
                    candidate.kind() == dependency.kind()
                        && candidate.componentId().equals(dependency.componentId())
                        && dependency.matches(candidate.version()));
                if (!published) {
                    throw new IllegalArgumentException(
                        "required dependency is not published: " + dependency.kind()
                            + "/" + dependency.componentId() + " "
                            + dependency.versionConstraint());
                }
            }
        }
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize plugin artifact index", exception);
        }
    }

    private static String hash(String value) {
        return PluginArtifactVerifier.sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String identifier(String value, String field) {
        return PluginSignatureManifest.requireIdentifier(value, field);
    }

    private static String sha256(String value, String field) {
        return PluginSignatureManifest.requireSha256(value, field);
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

    private static <T> List<T> list(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static <T> List<T> requiredList(List<T> values, String field) {
        if (values == null) {
            throw new IllegalArgumentException(field + " must be present");
        }
        return values;
    }

    private static String requiredValue(String value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must be present");
        }
        return value;
    }

    private static boolean requiredBoolean(Boolean value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must be present");
        }
        return value;
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
            artifactId = identifier(artifactId, "artifactId");
            Objects.requireNonNull(kind, "kind");
            componentId = identifier(componentId, "componentId");
            version = requireVersion(version, "version");
            apiVersion = identifier(apiVersion, "apiVersion");
            minimumCoreVersion = requireVersion(minimumCoreVersion, "minimumCoreVersion");
            maximumCoreVersionExclusive = optionalVersion(
                maximumCoreVersionExclusive, "maximumCoreVersionExclusive");
            if (!maximumCoreVersionExclusive.isEmpty()
                    && compareVersions(minimumCoreVersion, maximumCoreVersionExclusive) >= 0) {
                throw new IllegalArgumentException(
                    "maximumCoreVersionExclusive must exceed minimumCoreVersion");
            }
            capabilities = PluginArtifactIndex.capabilities(capabilities);
            dependencies = PluginArtifactIndex.dependencies(dependencies);
            artifactFileName = fileName(artifactFileName, kind);
            artifactSha256 = sha256(artifactSha256, "artifactSha256");
            artifactUri = uri(artifactUri, "artifactUri", false);
            signatureManifestUri = uri(
                signatureManifestUri, "signatureManifestUri", kind != ArtifactKind.JAVA_PLUGIN);
            provenanceUri = uri(provenanceUri, "provenanceUri", false);
            publisherId = identifier(publisherId, "publisherId");
            identityHash = sha256(identityHash, "identityHash");
            String expected = identity(
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
            String normalizedArtifactId = identifier(artifactId, "artifactId");
            Objects.requireNonNull(kind, "kind");
            String normalizedComponent = identifier(componentId, "componentId");
            String normalizedVersion = requireVersion(version, "version");
            String normalizedApi = identifier(apiVersion, "apiVersion");
            String normalizedMinimum = requireVersion(
                minimumCoreVersion, "minimumCoreVersion");
            String normalizedMaximum = optionalVersion(
                maximumCoreVersionExclusive, "maximumCoreVersionExclusive");
            List<String> normalizedCapabilities =
                PluginArtifactIndex.capabilities(capabilities);
            List<Dependency> normalizedDependencies =
                PluginArtifactIndex.dependencies(dependencies);
            String normalizedFileName = fileName(artifactFileName, kind);
            String normalizedHash = sha256(artifactSha256, "artifactSha256");
            String normalizedArtifactUri = uri(artifactUri, "artifactUri", false);
            String normalizedSignatureUri = uri(
                signatureManifestUri, "signatureManifestUri", kind != ArtifactKind.JAVA_PLUGIN);
            String normalizedProvenance = uri(provenanceUri, "provenanceUri", false);
            String normalizedPublisher = identifier(publisherId, "publisherId");
            String identity = identity(
                normalizedArtifactId,
                kind,
                normalizedComponent,
                normalizedVersion,
                normalizedApi,
                normalizedMinimum,
                normalizedMaximum,
                normalizedCapabilities,
                normalizedDependencies,
                normalizedFileName,
                normalizedHash,
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
                normalizedMinimum,
                normalizedMaximum,
                normalizedCapabilities,
                normalizedDependencies,
                normalizedFileName,
                normalizedHash,
                normalizedArtifactUri,
                normalizedSignatureUri,
                normalizedProvenance,
                normalizedPublisher,
                identity);
        }

        public boolean supportsCore(String coreVersion) {
            String core = requireVersion(coreVersion, "coreVersion");
            return compareVersions(core, minimumCoreVersion) >= 0
                && (maximumCoreVersionExclusive.isEmpty()
                    || compareVersions(core, maximumCoreVersionExclusive) < 0);
        }

        Map<String, Object> payload() {
            Map<String, Object> payload = basePayload(
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
            payload.put("identityHash", identityHash);
            return payload;
        }

        private static String identity(
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
            return hash(json(basePayload(
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
                publisherId)));
        }

        private static Map<String, Object> basePayload(
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
            payload.put("dependencies", dependencies.stream().map(Dependency::payload).toList());
            payload.put("artifactFileName", artifactFileName);
            payload.put("artifactSha256", artifactSha256);
            payload.put("artifactUri", artifactUri);
            payload.put("signatureManifestUri", signatureManifestUri);
            payload.put("provenanceUri", provenanceUri);
            payload.put("publisherId", publisherId);
            return payload;
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
            componentId = identifier(componentId, "dependency componentId");
            versionConstraint = constraint(versionConstraint);
        }

        public boolean matches(String version) {
            String candidate = requireVersion(version, "dependency version");
            return "any".equals(versionConstraint)
                || candidate.equals(versionConstraint.substring(1));
        }

        Map<String, Object> payload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("kind", kind.name());
            payload.put("componentId", componentId);
            payload.put("versionConstraint", versionConstraint);
            payload.put("optional", optional);
            return payload;
        }
    }

    private static List<String> capabilities(List<String> values) {
        return list(values).stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .map(value -> identifier(value.toLowerCase(Locale.ROOT), "capability"))
            .distinct()
            .sorted()
            .toList();
    }

    private static List<Dependency> dependencies(List<Dependency> values) {
        Set<String> identities = new HashSet<>();
        List<Dependency> normalized = new ArrayList<>();
        for (Dependency item : list(values)) {
            Dependency dependency = Objects.requireNonNull(item, "dependency");
            String identity = dependency.kind() + "\u0000" + dependency.componentId();
            if (!identities.add(identity)) {
                throw new IllegalArgumentException(
                    "duplicate dependency: " + dependency.kind() + "/"
                        + dependency.componentId());
            }
            normalized.add(dependency);
        }
        normalized.sort(Comparator
            .comparing((Dependency item) -> item.kind().name())
            .thenComparing(Dependency::componentId));
        return List.copyOf(normalized);
    }

    private static String optionalVersion(String value, String field) {
        return value == null || value.isBlank() ? "" : requireVersion(value.trim(), field);
    }

    private static String constraint(String value) {
        if ("any".equals(value)) {
            return value;
        }
        if (value == null || !value.startsWith("=")) {
            throw new IllegalArgumentException(
                "versionConstraint must be 'any' or '=x.y.z'");
        }
        return "=" + requireVersion(value.substring(1), "dependency versionConstraint");
    }

    private static String fileName(String value, ArtifactKind kind) {
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

    private static String uri(String value, String field, boolean optional) {
        if ((value == null || value.isBlank()) && optional) {
            return "";
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        try {
            URI uri = new URI(value).normalize();
            String scheme = uri.getScheme();
            boolean https = "https".equalsIgnoreCase(scheme)
                && uri.getHost() != null && !uri.getHost().isBlank();
            boolean file = "file".equalsIgnoreCase(scheme)
                && uri.getPath() != null && uri.getPath().startsWith("/")
                && (uri.getHost() == null || uri.getHost().isBlank());
            if ((!https && !file) || uri.getFragment() != null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException(
                    field + " must be an absolute https or local file URI without user info or fragment");
            }
            return uri.toString();
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException(field + " is not a valid URI", exception);
        }
    }

    /** SemVer 2.0 ordering; build metadata is ignored for precedence. */
    private record SemanticVersion(
        String source,
        BigInteger major,
        BigInteger minor,
        BigInteger patch,
        List<String> prerelease
    ) implements Comparable<SemanticVersion> {
        private static final Pattern PATTERN = Pattern.compile(
            "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"
                + "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?"
                + "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$"
        );

        static SemanticVersion parse(String value) {
            return parse(value, "version");
        }

        static SemanticVersion parse(String value, String field) {
            if (value == null) {
                throw new IllegalArgumentException(field + " must be semantic version x.y.z");
            }
            Matcher matcher = PATTERN.matcher(value);
            if (!matcher.matches()) {
                throw new IllegalArgumentException(field + " must be semantic version x.y.z");
            }
            List<String> prerelease = matcher.group(4) == null
                ? List.of()
                : List.of(matcher.group(4).split("\\."));
            for (String prereleaseIdentifier : prerelease) {
                if (numeric(prereleaseIdentifier)
                        && prereleaseIdentifier.length() > 1
                        && prereleaseIdentifier.startsWith("0")) {
                    throw new IllegalArgumentException(
                        field + " has a numeric prerelease identifier with a leading zero");
                }
            }
            return new SemanticVersion(
                value,
                new BigInteger(matcher.group(1)),
                new BigInteger(matcher.group(2)),
                new BigInteger(matcher.group(3)),
                prerelease);
        }

        @Override
        public int compareTo(SemanticVersion other) {
            int result = major.compareTo(other.major);
            if (result == 0) {
                result = minor.compareTo(other.minor);
            }
            if (result == 0) {
                result = patch.compareTo(other.patch);
            }
            if (result != 0) {
                return result;
            }
            if (prerelease.isEmpty() && other.prerelease.isEmpty()) {
                return 0;
            }
            if (prerelease.isEmpty()) {
                return 1;
            }
            if (other.prerelease.isEmpty()) {
                return -1;
            }
            int length = Math.min(prerelease.size(), other.prerelease.size());
            for (int index = 0; index < length; index++) {
                String left = prerelease.get(index);
                String right = other.prerelease.get(index);
                boolean leftNumeric = numeric(left);
                boolean rightNumeric = numeric(right);
                if (leftNumeric && rightNumeric) {
                    result = compareNumeric(left, right);
                } else if (leftNumeric != rightNumeric) {
                    result = leftNumeric ? -1 : 1;
                } else {
                    result = left.compareTo(right);
                }
                if (result != 0) {
                    return result;
                }
            }
            return Integer.compare(prerelease.size(), other.prerelease.size());
        }

        private static boolean numeric(String value) {
            return value.chars().allMatch(Character::isDigit);
        }

        private static int compareNumeric(String left, String right) {
            int length = Integer.compare(left.length(), right.length());
            return length == 0 ? left.compareTo(right) : length;
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
        public Boolean optional;
    }
}
