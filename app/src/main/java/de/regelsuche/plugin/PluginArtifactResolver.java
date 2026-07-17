package de.regelsuche.plugin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.plugin.PluginArtifactIndex.ArtifactKind;
import de.regelsuche.plugin.PluginArtifactIndex.Dependency;
import de.regelsuche.plugin.PluginArtifactIndex.Entry;
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

    public ResolutionReceipt resolve(
        PluginArtifactIndex index,
        ResolutionRequest request
    ) {
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(request, "request");
        ResolutionContext context = new ResolutionContext(index, request);
        Entry root = selectRoot(context);
        if (root != null) {
            visit(root, context);
        }
        List<String> blockers = messages(context.blockers);
        List<String> warnings = messages(context.warnings);
        ResolutionStatus status = root != null && blockers.isEmpty()
            ? ResolutionStatus.RESOLVED
            : ResolutionStatus.UNRESOLVED;
        return ResolutionReceipt.create(
            index.contentHash(),
            request,
            status,
            status == ResolutionStatus.RESOLVED ? root.identityHash() : "",
            status == ResolutionStatus.RESOLVED ? numbered(context.ordered) : List.of(),
            blockers,
            warnings);
    }

    private static Entry selectRoot(ResolutionContext context) {
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
                .filter(item -> item.version().equals(request.requestedVersion()))
                .toList();
            if (versions.isEmpty()) {
                context.blockers.add("requested-version-not-published:"
                    + request.requestedVersion());
                return null;
            }
        }
        List<Entry> coreCompatible = versions.stream()
            .filter(item -> item.supportsCore(request.coreVersion()))
            .toList();
        if (coreCompatible.isEmpty()) {
            context.blockers.add("no-core-compatible-version:" + request.coreVersion());
            return null;
        }
        List<Entry> apiCompatible = coreCompatible.stream()
            .filter(item -> item.apiVersion().equals(request.apiVersion()))
            .toList();
        if (apiCompatible.isEmpty()) {
            context.blockers.add("no-api-compatible-version:" + request.apiVersion());
            return null;
        }
        List<Entry> capabilityCompatible = apiCompatible.stream()
            .filter(item -> item.capabilities().containsAll(request.requiredCapabilities()))
            .toList();
        if (capabilityCompatible.isEmpty()) {
            context.blockers.add("required-capabilities-unavailable:"
                + String.join(",", request.requiredCapabilities()));
            return null;
        }
        return capabilityCompatible.getFirst();
    }

    private static void visit(Entry entry, ResolutionContext context) {
        Coordinate current = new Coordinate(entry.kind(), entry.componentId());
        if (context.visiting.contains(current)) {
            context.blockers.add("dependency-cycle:" + cycle(context.stack, current));
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

        context.visiting.add(current);
        context.stack.addLast(current);
        context.selected.put(current, entry);
        for (Dependency dependency : entry.dependencies()) {
            Entry selected = selectDependency(dependency, entry, context);
            if (selected == null) {
                continue;
            }
            Coordinate dependencyCoordinate = new Coordinate(
                selected.kind(), selected.componentId());
            if (context.visiting.contains(dependencyCoordinate)) {
                String diagnostic = (dependency.optional()
                    ? "optional-dependency-cycle:"
                    : "dependency-cycle:")
                    + cycle(context.stack, dependencyCoordinate);
                if (dependency.optional()) {
                    context.warnings.add(diagnostic);
                } else {
                    context.blockers.add(diagnostic);
                }
                continue;
            }
            visit(selected, context);
        }
        context.stack.removeLast();
        context.visiting.remove(current);
        if (context.ordered.stream().noneMatch(item ->
                item.kind() == entry.kind()
                    && item.componentId().equals(entry.componentId()))) {
            context.ordered.add(entry);
        }
    }

    private static Entry selectDependency(
        Dependency dependency,
        Entry parent,
        ResolutionContext context
    ) {
        List<Entry> matching = context.index.componentVersions(
                dependency.kind(), dependency.componentId()).stream()
            .filter(item -> dependency.matches(item.version()))
            .filter(item -> item.supportsCore(context.request.coreVersion()))
            .filter(item -> item.apiVersion().equals(context.request.apiVersion()))
            .toList();
        if (!matching.isEmpty()) {
            return matching.getFirst();
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
        List<Coordinate> path = new ArrayList<>();
        boolean copy = false;
        for (Coordinate item : stack) {
            if (item.equals(repeated)) {
                copy = true;
            }
            if (copy) {
                path.add(item);
            }
        }
        path.add(repeated);
        return path.stream().map(Coordinate::toString)
            .collect(java.util.stream.Collectors.joining("->"));
    }

    private static String coordinate(ArtifactKind kind, String componentId) {
        return kind.name() + "/" + componentId;
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
            throw new IllegalStateException("Unable to serialize artifact resolution", exception);
        }
    }

    private static List<String> identifiers(List<String> values, String field) {
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

    private static List<String> messages(List<String> values) {
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
            requestId = PluginSignatureManifest.requireIdentifier(requestId, "requestId");
            Objects.requireNonNull(kind, "kind");
            componentId = PluginSignatureManifest.requireIdentifier(componentId, "componentId");
            Objects.requireNonNull(selectionMode, "selectionMode");
            requestedVersion = normalizeRequestedVersion(selectionMode, requestedVersion);
            coreVersion = PluginArtifactIndex.requireVersion(coreVersion, "coreVersion");
            apiVersion = PluginSignatureManifest.requireIdentifier(apiVersion, "apiVersion");
            requiredCapabilities = identifiers(
                requiredCapabilities, "required capability");
            contentHash = PluginSignatureManifest.requireSha256(contentHash, "contentHash");
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
            String normalizedRequest = PluginSignatureManifest.requireIdentifier(
                requestId, "requestId");
            Objects.requireNonNull(kind, "kind");
            String normalizedComponent = PluginSignatureManifest.requireIdentifier(
                componentId, "componentId");
            Objects.requireNonNull(selectionMode, "selectionMode");
            String normalizedVersion = normalizeRequestedVersion(
                selectionMode, requestedVersion);
            String normalizedCore = PluginArtifactIndex.requireVersion(
                coreVersion, "coreVersion");
            String normalizedApi = PluginSignatureManifest.requireIdentifier(
                apiVersion, "apiVersion");
            List<String> normalizedCapabilities = identifiers(
                requiredCapabilities, "required capability");
            String contentHash = hash(basePayload(
                normalizedRequest,
                kind,
                normalizedComponent,
                selectionMode,
                normalizedVersion,
                normalizedCore,
                normalizedApi,
                normalizedCapabilities));
            return new ResolutionRequest(
                REQUEST_SCHEMA,
                normalizedRequest,
                kind,
                normalizedComponent,
                selectionMode,
                normalizedVersion,
                normalizedCore,
                normalizedApi,
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

        private static String normalizeRequestedVersion(
            SelectionMode mode,
            String value
        ) {
            String normalized = value == null ? "" : value.trim();
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
                throw new IllegalArgumentException("resolution order must be positive");
            }
            artifactId = PluginSignatureManifest.requireIdentifier(artifactId, "artifactId");
            Objects.requireNonNull(kind, "kind");
            componentId = PluginSignatureManifest.requireIdentifier(componentId, "componentId");
            version = PluginArtifactIndex.requireVersion(version, "version");
            if (artifactFileName == null || artifactFileName.isBlank()) {
                throw new IllegalArgumentException("artifactFileName must not be blank");
            }
            artifactSha256 = PluginSignatureManifest.requireSha256(
                artifactSha256, "artifactSha256");
            if (artifactUri == null || artifactUri.isBlank()
                    || provenanceUri == null || provenanceUri.isBlank()) {
                throw new IllegalArgumentException(
                    "artifact and provenance URIs are required");
            }
            signatureManifestUri = signatureManifestUri == null ? "" : signatureManifestUri;
            publisherId = PluginSignatureManifest.requireIdentifier(publisherId, "publisherId");
            identityHash = PluginSignatureManifest.requireSha256(identityHash, "identityHash");
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
            blockers = messages(blockers);
            warnings = messages(warnings);
            requireStatus(networkAccessStatus, NOT_PERFORMED, "networkAccessStatus");
            requireStatus(installationStatus, NOT_PERFORMED, "installationStatus");
            requireStatus(trustVerificationStatus, NOT_EVALUATED, "trustVerificationStatus");
            validateDisposition(status, rootArtifactIdentityHash, plan, blockers);
            contentHash = PluginSignatureManifest.requireSha256(contentHash, "contentHash");
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
            String indexHash = PluginSignatureManifest.requireSha256(
                indexContentHash, "indexContentHash");
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(status, "status");
            String rootHash = optionalHash(
                rootArtifactIdentityHash, "rootArtifactIdentityHash");
            List<ResolutionStep> normalizedPlan = normalizePlan(plan);
            List<String> normalizedBlockers = messages(blockers);
            List<String> normalizedWarnings = messages(warnings);
            validateDisposition(status, rootHash, normalizedPlan, normalizedBlockers);
            String contentHash = hash(basePayload(
                indexHash,
                request,
                status,
                rootHash,
                normalizedPlan,
                normalizedBlockers,
                normalizedWarnings));
            return new ResolutionReceipt(
                RECEIPT_SCHEMA,
                indexHash,
                request,
                status,
                rootHash,
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
            payload.put("plan", plan.stream().map(ResolutionStep::payload).toList());
            payload.put("blockers", blockers);
            payload.put("warnings", warnings);
            payload.put("networkAccessStatus", NOT_PERFORMED);
            payload.put("installationStatus", NOT_PERFORMED);
            payload.put("trustVerificationStatus", NOT_EVALUATED);
            return payload;
        }

        private static List<ResolutionStep> normalizePlan(List<ResolutionStep> values) {
            if (values == null) {
                return List.of();
            }
            List<ResolutionStep> plan = values.stream()
                .map(value -> Objects.requireNonNull(value, "resolution step"))
                .sorted(Comparator.comparingInt(ResolutionStep::order))
                .toList();
            Set<String> coordinates = new HashSet<>();
            for (int index = 0; index < plan.size(); index++) {
                ResolutionStep step = plan.get(index);
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
            return List.copyOf(plan);
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
            } else if (!rootArtifactIdentityHash.isEmpty()
                    || !plan.isEmpty()
                    || blockers.isEmpty()) {
                throw new IllegalArgumentException(
                    "unresolved receipt requires blockers and no executable plan");
            }
        }

        private static String optionalHash(String value, String field) {
            if (value == null || value.isBlank()) {
                return "";
            }
            return PluginSignatureManifest.requireSha256(value, field);
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

    private static final class ResolutionContext {
        private final PluginArtifactIndex index;
        private final ResolutionRequest request;
        private final Map<Coordinate, Entry> selected = new TreeMap<>(Comparator
            .comparing((Coordinate item) -> item.kind().name())
            .thenComparing(Coordinate::componentId));
        private final Set<Coordinate> visiting = new HashSet<>();
        private final Deque<Coordinate> stack = new ArrayDeque<>();
        private final List<Entry> ordered = new ArrayList<>();
        private final List<String> blockers = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();

        private ResolutionContext(
            PluginArtifactIndex index,
            ResolutionRequest request
        ) {
            this.index = index;
            this.request = request;
        }
    }

    private record Coordinate(ArtifactKind kind, String componentId) {
        private Coordinate {
            Objects.requireNonNull(kind, "kind");
            componentId = PluginSignatureManifest.requireIdentifier(
                componentId, "componentId");
        }

        @Override
        public String toString() {
            return coordinate(kind, componentId);
        }
    }
}
