package de.regelsuche.plugin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.plugin.PluginArtifactIndex.ArtifactKind;
import de.regelsuche.plugin.PluginArtifactIndex.Dependency;
import de.regelsuche.plugin.PluginArtifactIndex.Entry;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Deterministically resolves one immutable index coordinate and its dependency
 * closure. Resolution performs no network access, installation or trust
 * verification; those remain separate explicit stages.
 */
public final class PluginArtifactResolver {
    public static final String REQUEST_SCHEMA =
        "regelsuche.plugin-artifact-resolution-request/v1";
    public static final String RECEIPT_SCHEMA =
        "regelsuche.plugin-artifact-resolution/v1";
    public static final String NOT_PERFORMED = "NOT_PERFORMED";
    public static final String NOT_EVALUATED = "NOT_EVALUATED";

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Comparator<Coordinate> COORDINATE_ORDER = Comparator
        .comparing((Coordinate value) -> value.kind().name())
        .thenComparing(Coordinate::componentId);

    public ResolutionReceipt resolve(
        PluginArtifactIndex index,
        ResolutionRequest request
    ) {
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(request, "request");
        Context context = new Context(index, request);
        Entry root = selectRoot(context);
        if (root != null) {
            visit(root, context);
        }
        List<String> blockers = normalizeMessages(context.blockers);
        List<String> warnings = normalizeMessages(context.warnings);
        boolean resolved = root != null && blockers.isEmpty();
        return ResolutionReceipt.create(
            index.contentHash(),
            request,
            resolved ? ResolutionStatus.RESOLVED : ResolutionStatus.UNRESOLVED,
            resolved ? root.identityHash() : "",
            resolved ? numbered(context.ordered) : List.of(),
            blockers,
            warnings);
    }

    private static Entry selectRoot(Context context) {
        ResolutionRequest request = context.request;
        List<Entry> versions = context.index.componentVersions(
            request.kind(), request.componentId());
        if (versions.isEmpty()) {
            context.blockers.add("component-not-published:"
                + coordinate(request.kind(), request.componentId()));
            return null;
        }
        if (request.selectionMode() == SelectionMode.EXACT) {
            versions = versions.stream()
                .filter(value -> value.version().equals(request.requestedVersion()))
                .toList();
            if (versions.isEmpty()) {
                context.blockers.add("requested-version-not-published:"
                    + request.requestedVersion());
                return null;
            }
        }
        versions = versions.stream()
            .filter(value -> value.supportsCore(request.coreVersion()))
            .toList();
        if (versions.isEmpty()) {
            context.blockers.add("no-core-compatible-version:" + request.coreVersion());
            return null;
        }
        versions = versions.stream()
            .filter(value -> value.apiVersion().equals(request.apiVersion()))
            .toList();
        if (versions.isEmpty()) {
            context.blockers.add("no-api-compatible-version:" + request.apiVersion());
            return null;
        }
        versions = versions.stream()
            .filter(value -> value.capabilities().containsAll(
                request.requiredCapabilities()))
            .toList();
        if (versions.isEmpty()) {
            context.blockers.add("required-capabilities-unavailable:"
                + String.join(",", request.requiredCapabilities()));
            return null;
        }
        return versions.getFirst();
    }

    private static void visit(Entry entry, Context context) {
        Coordinate current = Coordinate.from(entry);
        if (context.visiting.contains(current)) {
            context.blockers.add("dependency-cycle:"
                + cycle(context.stack, current));
            return;
        }
        Entry existing = context.selected.get(current);
        if (existing != null) {
            if (!existing.version().equals(entry.version())) {
                context.blockers.add("dependency-version-conflict:"
                    + current + ":" + existing.version() + "<>" + entry.version());
            }
            return;
        }

        context.selected.put(current, entry);
        context.visiting.add(current);
        context.stack.addLast(current);
        for (Dependency dependency : entry.dependencies()) {
            Entry candidate = selectDependency(dependency, entry, context);
            if (candidate == null) {
                continue;
            }
            if (dependency.optional()) {
                visitOptional(dependency, entry, candidate, context);
            } else {
                visit(candidate, context);
            }
        }
        context.stack.removeLast();
        context.visiting.remove(current);
        if (context.ordered.stream().noneMatch(value ->
                value.kind() == entry.kind()
                    && value.componentId().equals(entry.componentId()))) {
            context.ordered.add(entry);
        }
    }

    /**
     * Optional branches are transactional: if any required descendant of an
     * optional dependency fails, every selection made by that branch is rolled
     * back and the rejected branch is retained as one deterministic warning.
     */
    private static void visitOptional(
        Dependency dependency,
        Entry parent,
        Entry candidate,
        Context context
    ) {
        Snapshot before = context.snapshot();
        int blockerStart = context.blockers.size();
        int warningStart = context.warnings.size();
        visit(candidate, context);
        if (context.blockers.size() == blockerStart) {
            return;
        }

        List<String> branchBlockers = normalizeMessages(new ArrayList<>(
            context.blockers.subList(blockerStart, context.blockers.size())));
        List<String> branchWarnings = normalizeMessages(new ArrayList<>(
            context.warnings.subList(warningStart, context.warnings.size())));
        context.restore(before);

        StringBuilder warning = new StringBuilder("optional-dependency-rejected:")
            .append(parent.artifactId())
            .append("->")
            .append(coordinate(dependency.kind(), dependency.componentId()))
            .append("@")
            .append(dependency.versionConstraint())
            .append(":blockers=")
            .append(String.join("|", branchBlockers));
        if (!branchWarnings.isEmpty()) {
            warning.append(":warnings=").append(String.join("|", branchWarnings));
        }
        context.warnings.add(warning.toString());
    }

    private static Entry selectDependency(
        Dependency dependency,
        Entry parent,
        Context context
    ) {
        List<Entry> candidates = context.index.componentVersions(
                dependency.kind(), dependency.componentId()).stream()
            .filter(value -> dependency.matches(value.version()))
            .filter(value -> value.supportsCore(context.request.coreVersion()))
            .filter(value -> value.apiVersion().equals(context.request.apiVersion()))
            .toList();
        if (!candidates.isEmpty()) {
            return candidates.getFirst();
        }
        String diagnostic = (dependency.optional()
            ? "optional-dependency-unavailable:"
            : "required-dependency-unavailable:")
            + parent.artifactId() + "->"
            + coordinate(dependency.kind(), dependency.componentId())
            + "@" + dependency.versionConstraint();
        if (dependency.optional()) {
            context.warnings.add(diagnostic);
        } else {
            context.blockers.add(diagnostic);
        }
        return null;
    }

    private static List<ResolutionStep> numbered(List<Entry> entries) {
        List<ResolutionStep> steps = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            steps.add(ResolutionStep.from(index + 1, entries.get(index)));
        }
        return List.copyOf(steps);
    }

    private static String cycle(Deque<Coordinate> stack, Coordinate repeated) {
        List<Coordinate> cycle = new ArrayList<>();
        boolean copy = false;
        for (Coordinate value : stack) {
            if (value.equals(repeated)) {
                copy = true;
            }
            if (copy) {
                cycle.add(value);
            }
        }
        cycle.add(repeated);
        return cycle.stream()
            .map(Coordinate::toString)
            .collect(java.util.stream.Collectors.joining("->"));
    }

    private static String coordinate(ArtifactKind kind, String componentId) {
        return kind.name() + "/" + componentId;
    }

    private static List<String> normalizeIdentifiers(
        List<String> values,
        String field
    ) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .map(value -> PluginSignatureManifest.requireIdentifier(value, field))
            .distinct()
            .sorted()
            .toList();
    }

    private static List<String> normalizeMessages(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .distinct()
            .sorted()
            .toList();
    }

    private static String optionalHash(String value, String field) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return PluginSignatureManifest.requireSha256(value, field);
    }

    private static String hash(Object value) {
        try {
            return PluginArtifactVerifier.sha256(JSON.writeValueAsBytes(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to hash artifact resolution", exception);
        }
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value) + "\n";
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                "Unable to serialize artifact resolution", exception);
        }
    }

    private static String requireUri(String value, String field, boolean optional) {
        if ((value == null || value.isBlank()) && optional) {
            return "";
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        try {
            URI uri = new URI(value).normalize();
            boolean https = "https".equalsIgnoreCase(uri.getScheme())
                && uri.getHost() != null && !uri.getHost().isBlank();
            boolean file = "file".equalsIgnoreCase(uri.getScheme())
                && uri.getPath() != null && uri.getPath().startsWith("/")
                && (uri.getHost() == null || uri.getHost().isBlank());
            if ((!https && !file)
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException(
                    field + " must be an absolute https or local file URI");
            }
            return uri.toString();
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException(field + " is not a valid URI", exception);
        }
    }

    public enum SelectionMode {
        EXACT,
        LATEST_COMPATIBLE
    }

    public enum ResolutionStatus {
        RESOLVED,
        UNRESOLVED
    }

    public record ResolutionRequest(
        String schema,
        String requestId,
        ArtifactKind kind,
        String componentId,
        SelectionMode selectionMode,
        String requestedVersion,
        String coreVersion,
        String apiVersion,
        List<String> requiredCapabilities,
        String contentHash
    ) {
        public ResolutionRequest {
            if (!REQUEST_SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported artifact resolution request schema");
            }
            requestId = PluginSignatureManifest.requireIdentifier(
                requestId, "requestId");
            Objects.requireNonNull(kind, "kind");
            componentId = PluginSignatureManifest.requireIdentifier(
                componentId, "componentId");
            Objects.requireNonNull(selectionMode, "selectionMode");
            requestedVersion = normalizeVersionSelection(
                selectionMode, requestedVersion);
            coreVersion = PluginArtifactIndex.requireVersion(
                coreVersion, "coreVersion");
            apiVersion = PluginSignatureManifest.requireIdentifier(
                apiVersion, "apiVersion");
            requiredCapabilities = normalizeIdentifiers(
                requiredCapabilities, "required capability");
            contentHash = PluginSignatureManifest.requireSha256(
                contentHash, "contentHash");
            String expected = hash(basePayload(
                requestId,
                kind,
                componentId,
                selectionMode,
                requestedVersion,
                coreVersion,
                apiVersion,
                requiredCapabilities));
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "artifact resolution request contentHash mismatch");
            }
        }

        public static ResolutionRequest latestCompatible(
            String requestId,
            ArtifactKind kind,
            String componentId,
            String coreVersion,
            String apiVersion,
            List<String> requiredCapabilities
        ) {
            return create(
                requestId,
                kind,
                componentId,
                SelectionMode.LATEST_COMPATIBLE,
                "",
                coreVersion,
                apiVersion,
                requiredCapabilities);
        }

        public static ResolutionRequest exact(
            String requestId,
            ArtifactKind kind,
            String componentId,
            String requestedVersion,
            String coreVersion,
            String apiVersion,
            List<String> requiredCapabilities
        ) {
            return create(
                requestId,
                kind,
                componentId,
                SelectionMode.EXACT,
                requestedVersion,
                coreVersion,
                apiVersion,
                requiredCapabilities);
        }

        private static ResolutionRequest create(
            String requestId,
            ArtifactKind kind,
            String componentId,
            SelectionMode selectionMode,
            String requestedVersion,
            String coreVersion,
            String apiVersion,
            List<String> requiredCapabilities
        ) {
            String normalizedRequestId = PluginSignatureManifest.requireIdentifier(
                requestId, "requestId");
            Objects.requireNonNull(kind, "kind");
            String normalizedComponentId = PluginSignatureManifest.requireIdentifier(
                componentId, "componentId");
            Objects.requireNonNull(selectionMode, "selectionMode");
            String normalizedVersion = normalizeVersionSelection(
                selectionMode, requestedVersion);
            String normalizedCoreVersion = PluginArtifactIndex.requireVersion(
                coreVersion, "coreVersion");
            String normalizedApiVersion = PluginSignatureManifest.requireIdentifier(
                apiVersion, "apiVersion");
            List<String> normalizedCapabilities = normalizeIdentifiers(
                requiredCapabilities, "required capability");
            String contentHash = hash(basePayload(
                normalizedRequestId,
                kind,
                normalizedComponentId,
                selectionMode,
                normalizedVersion,
                normalizedCoreVersion,
                normalizedApiVersion,
                normalizedCapabilities));
            return new ResolutionRequest(
                REQUEST_SCHEMA,
                normalizedRequestId,
                kind,
                normalizedComponentId,
                selectionMode,
                normalizedVersion,
                normalizedCoreVersion,
                normalizedApiVersion,
                normalizedCapabilities,
                contentHash);
        }

        Map<String, Object> payload() {
            Map<String, Object> payload = basePayload(
                requestId,
                kind,
                componentId,
                selectionMode,
                requestedVersion,
                coreVersion,
                apiVersion,
                requiredCapabilities);
            payload.put("contentHash", contentHash);
            return payload;
        }

        private static Map<String, Object> basePayload(
            String requestId,
            ArtifactKind kind,
            String componentId,
            SelectionMode selectionMode,
            String requestedVersion,
            String coreVersion,
            String apiVersion,
            List<String> requiredCapabilities
        ) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("schema", REQUEST_SCHEMA);
            payload.put("requestId", requestId);
            payload.put("kind", kind.name());
            payload.put("componentId", componentId);
            payload.put("selectionMode", selectionMode.name());
            payload.put("requestedVersion", requestedVersion);
            payload.put("coreVersion", coreVersion);
            payload.put("apiVersion", apiVersion);
            payload.put("requiredCapabilities", requiredCapabilities);
            return payload;
        }

        private static String normalizeVersionSelection(
            SelectionMode mode,
            String version
        ) {
            String normalized = version == null ? "" : version.trim();
            if (mode == SelectionMode.EXACT) {
                return PluginArtifactIndex.requireVersion(
                    normalized, "requestedVersion");
            }
            if (!normalized.isEmpty()) {
                throw new IllegalArgumentException(
                    "LATEST_COMPATIBLE request must not provide requestedVersion");
            }
            return "";
        }
    }

    public record ResolutionStep(
        int order,
        String artifactId,
        ArtifactKind kind,
        String componentId,
        String version,
        String artifactFileName,
        String artifactSha256,
        String artifactUri,
        String signatureManifestUri,
        String provenanceUri,
        String publisherId,
        String identityHash
    ) {
        public ResolutionStep {
            if (order < 1) {
                throw new IllegalArgumentException(
                    "resolution order must be positive");
            }
            artifactId = PluginSignatureManifest.requireIdentifier(
                artifactId, "artifactId");
            Objects.requireNonNull(kind, "kind");
            componentId = PluginSignatureManifest.requireIdentifier(
                componentId, "componentId");
            version = PluginArtifactIndex.requireVersion(version, "version");
            if (artifactFileName == null
                    || artifactFileName.isBlank()
                    || artifactFileName.contains("/")
                    || artifactFileName.contains("\\")) {
                throw new IllegalArgumentException(
                    "artifactFileName must be a simple file name");
            }
            artifactSha256 = PluginSignatureManifest.requireSha256(
                artifactSha256, "artifactSha256");
            artifactUri = requireUri(artifactUri, "artifactUri", false);
            signatureManifestUri = requireUri(
                signatureManifestUri,
                "signatureManifestUri",
                kind != ArtifactKind.JAVA_PLUGIN);
            provenanceUri = requireUri(provenanceUri, "provenanceUri", false);
            publisherId = PluginSignatureManifest.requireIdentifier(
                publisherId, "publisherId");
            identityHash = PluginSignatureManifest.requireSha256(
                identityHash, "identityHash");
        }

        static ResolutionStep from(int order, Entry entry) {
            return new ResolutionStep(
                order,
                entry.artifactId(),
                entry.kind(),
                entry.componentId(),
                entry.version(),
                entry.artifactFileName(),
                entry.artifactSha256(),
                entry.artifactUri(),
                entry.signatureManifestUri(),
                entry.provenanceUri(),
                entry.publisherId(),
                entry.identityHash());
        }

        Map<String, Object> payload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("order", order);
            payload.put("artifactId", artifactId);
            payload.put("kind", kind.name());
            payload.put("componentId", componentId);
            payload.put("version", version);
            payload.put("artifactFileName", artifactFileName);
            payload.put("artifactSha256", artifactSha256);
            payload.put("artifactUri", artifactUri);
            payload.put("signatureManifestUri", signatureManifestUri);
            payload.put("provenanceUri", provenanceUri);
            payload.put("publisherId", publisherId);
            payload.put("identityHash", identityHash);
            return payload;
        }
    }

    public record ResolutionReceipt(
        String schema,
        String indexContentHash,
        ResolutionRequest request,
        ResolutionStatus status,
        String rootArtifactIdentityHash,
        List<ResolutionStep> plan,
        List<String> blockers,
        List<String> warnings,
        String networkAccessStatus,
        String installationStatus,
        String trustVerificationStatus,
        String contentHash
    ) {
        public ResolutionReceipt {
            if (!RECEIPT_SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported artifact resolution schema");
            }
            indexContentHash = PluginSignatureManifest.requireSha256(
                indexContentHash, "indexContentHash");
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(status, "status");
            rootArtifactIdentityHash = optionalHash(
                rootArtifactIdentityHash, "rootArtifactIdentityHash");
            plan = normalizePlan(plan);
            blockers = normalizeMessages(blockers);
            warnings = normalizeMessages(warnings);
            requireStatus(networkAccessStatus, NOT_PERFORMED, "networkAccessStatus");
            requireStatus(installationStatus, NOT_PERFORMED, "installationStatus");
            requireStatus(
                trustVerificationStatus,
                NOT_EVALUATED,
                "trustVerificationStatus");
            validateDisposition(status, rootArtifactIdentityHash, plan, blockers);
            contentHash = PluginSignatureManifest.requireSha256(
                contentHash, "contentHash");
            String expected = hash(basePayload(
                indexContentHash,
                request,
                status,
                rootArtifactIdentityHash,
                plan,
                blockers,
                warnings));
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "artifact resolution contentHash mismatch");
            }
        }

        static ResolutionReceipt create(
            String indexContentHash,
            ResolutionRequest request,
            ResolutionStatus status,
            String rootArtifactIdentityHash,
            List<ResolutionStep> plan,
            List<String> blockers,
            List<String> warnings
        ) {
            String normalizedIndexHash = PluginSignatureManifest.requireSha256(
                indexContentHash, "indexContentHash");
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(status, "status");
            String normalizedRootHash = optionalHash(
                rootArtifactIdentityHash, "rootArtifactIdentityHash");
            List<ResolutionStep> normalizedPlan = normalizePlan(plan);
            List<String> normalizedBlockers = normalizeMessages(blockers);
            List<String> normalizedWarnings = normalizeMessages(warnings);
            validateDisposition(
                status,
                normalizedRootHash,
                normalizedPlan,
                normalizedBlockers);
            String contentHash = hash(basePayload(
                normalizedIndexHash,
                request,
                status,
                normalizedRootHash,
                normalizedPlan,
                normalizedBlockers,
                normalizedWarnings));
            return new ResolutionReceipt(
                RECEIPT_SCHEMA,
                normalizedIndexHash,
                request,
                status,
                normalizedRootHash,
                normalizedPlan,
                normalizedBlockers,
                normalizedWarnings,
                NOT_PERFORMED,
                NOT_PERFORMED,
                NOT_EVALUATED,
                contentHash);
        }

        public String toCanonicalJson() {
            Map<String, Object> payload = basePayload(
                indexContentHash,
                request,
                status,
                rootArtifactIdentityHash,
                plan,
                blockers,
                warnings);
            payload.put("contentHash", contentHash);
            return json(payload);
        }

        private static Map<String, Object> basePayload(
            String indexContentHash,
            ResolutionRequest request,
            ResolutionStatus status,
            String rootArtifactIdentityHash,
            List<ResolutionStep> plan,
            List<String> blockers,
            List<String> warnings
        ) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("schema", RECEIPT_SCHEMA);
            payload.put("indexContentHash", indexContentHash);
            payload.put("request", request.payload());
            payload.put("status", status.name());
            payload.put("rootArtifactIdentityHash", rootArtifactIdentityHash);
            payload.put("plan", plan.stream()
                .map(ResolutionStep::payload)
                .toList());
            payload.put("blockers", blockers);
            payload.put("warnings", warnings);
            payload.put("networkAccessStatus", NOT_PERFORMED);
            payload.put("installationStatus", NOT_PERFORMED);
            payload.put("trustVerificationStatus", NOT_EVALUATED);
            return payload;
        }

        private static List<ResolutionStep> normalizePlan(
            List<ResolutionStep> values
        ) {
            if (values == null) {
                return List.of();
            }
            List<ResolutionStep> normalized = values.stream()
                .map(value -> Objects.requireNonNull(value, "resolution step"))
                .sorted(Comparator.comparingInt(ResolutionStep::order))
                .toList();
            Set<String> coordinates = new HashSet<>();
            for (int index = 0; index < normalized.size(); index++) {
                ResolutionStep step = normalized.get(index);
                if (step.order() != index + 1) {
                    throw new IllegalArgumentException(
                        "resolution plan order must be contiguous");
                }
                String coordinate = coordinate(step.kind(), step.componentId());
                if (!coordinates.add(coordinate)) {
                    throw new IllegalArgumentException(
                        "resolution plan selects a component more than once: "
                            + coordinate);
                }
            }
            return List.copyOf(normalized);
        }

        private static void validateDisposition(
            ResolutionStatus status,
            String rootArtifactIdentityHash,
            List<ResolutionStep> plan,
            List<String> blockers
        ) {
            if (status == ResolutionStatus.RESOLVED) {
                if (rootArtifactIdentityHash.isEmpty()
                        || plan.isEmpty()
                        || !blockers.isEmpty()) {
                    throw new IllegalArgumentException(
                        "resolved receipt requires root, plan and no blockers");
                }
                if (!plan.getLast().identityHash().equals(rootArtifactIdentityHash)) {
                    throw new IllegalArgumentException(
                        "resolution plan must end with the requested root artifact");
                }
                return;
            }
            if (!rootArtifactIdentityHash.isEmpty()
                    || !plan.isEmpty()
                    || blockers.isEmpty()) {
                throw new IllegalArgumentException(
                    "unresolved receipt requires blockers and no executable plan");
            }
        }

        private static void requireStatus(
            String value,
            String expected,
            String field
        ) {
            if (!expected.equals(value)) {
                throw new IllegalArgumentException(field + " must be " + expected);
            }
        }
    }

    private static final class Context {
        private final PluginArtifactIndex index;
        private final ResolutionRequest request;
        private final TreeMap<Coordinate, Entry> selected =
            new TreeMap<>(COORDINATE_ORDER);
        private final Set<Coordinate> visiting = new HashSet<>();
        private final Deque<Coordinate> stack = new ArrayDeque<>();
        private final List<Entry> ordered = new ArrayList<>();
        private final List<String> blockers = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();

        private Context(
            PluginArtifactIndex index,
            ResolutionRequest request
        ) {
            this.index = index;
            this.request = request;
        }

        private Snapshot snapshot() {
            TreeMap<Coordinate, Entry> selectedCopy =
                new TreeMap<>(COORDINATE_ORDER);
            selectedCopy.putAll(selected);
            return new Snapshot(
                selectedCopy,
                new HashSet<>(visiting),
                new ArrayDeque<>(stack),
                new ArrayList<>(ordered),
                new ArrayList<>(blockers),
                new ArrayList<>(warnings));
        }

        private void restore(Snapshot snapshot) {
            selected.clear();
            selected.putAll(snapshot.selected());
            visiting.clear();
            visiting.addAll(snapshot.visiting());
            stack.clear();
            stack.addAll(snapshot.stack());
            ordered.clear();
            ordered.addAll(snapshot.ordered());
            blockers.clear();
            blockers.addAll(snapshot.blockers());
            warnings.clear();
            warnings.addAll(snapshot.warnings());
        }
    }

    private record Snapshot(
        TreeMap<Coordinate, Entry> selected,
        Set<Coordinate> visiting,
        Deque<Coordinate> stack,
        List<Entry> ordered,
        List<String> blockers,
        List<String> warnings
    ) {
    }

    private record Coordinate(ArtifactKind kind, String componentId) {
        private Coordinate {
            Objects.requireNonNull(kind, "kind");
            componentId = PluginSignatureManifest.requireIdentifier(
                componentId, "componentId");
        }

        static Coordinate from(Entry entry) {
            return new Coordinate(entry.kind(), entry.componentId());
        }

        @Override
        public String toString() {
            return coordinate(kind, componentId);
        }
    }
}
