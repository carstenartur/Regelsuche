package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.WORKSPACE_SCHEMA;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.append;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.optionalSha256;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.optionalText;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.requireSha256;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.requireText;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.sha256;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.regelsuche.discovery.representation.RepresentationDiscoveryArtifactReference.ArtifactRole;
import de.regelsuche.discovery.representation.RepresentationDiscoveryArtifactReference.ArtifactStatus;
import de.regelsuche.discovery.representation.RepresentationDiscoveryRunOutcome.TerminalState;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable, content-addressed backend contract for one representation-
 * discovery run workspace.
 *
 * <p>The workspace binds input, information boundary, executable inventories,
 * search controls, backend identities, terminal accounting, every product
 * artifact role and code revisions. Artifact absence is explicit; consumers
 * cannot silently combine a graph, candidate dossier or proof object from a
 * different run.</p>
 */
public record RepresentationDiscoveryRunWorkspace(
    String schema,
    String runId,
    RunRelation relation,
    String parentRunId,
    String parentPlanHash,
    String changedPlanParameter,
    RepresentationDiscoveryRunInput input,
    RepresentationDiscoveryRunPlan plan,
    RepresentationDiscoveryRunOutcome outcome,
    List<RepresentationDiscoveryArtifactReference> artifacts,
    RepresentationDiscoveryRevisionEvidence revisions,
    String claimBoundary,
    String contentHash
) {
    public static final String SCHEMA = WORKSPACE_SCHEMA;
    public static final String CLAIM_BOUNDARY =
        "Immutable run correlation, artifact availability and reproduction "
            + "evidence; not mathematical truth, proof, novelty, "
            + "interestingness or search superiority.";

    public static final int DEFAULT_MAX_RETAINED_WORKSPACE_BYTES =
        2_000_000;
    public static final int DEFAULT_MAX_RETAINED_RUNS = 10_000;

    private static final Pattern RETAINED_FILE_NAME = Pattern.compile(
        "([0-9a-f]{64})\\.json"
    );
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build();

    public RepresentationDiscoveryRunWorkspace {
        schema = requireText(schema, "schema");
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException(
                "unsupported run-workspace schema: " + schema);
        }
        runId = requireSha256(runId, "runId");
        relation = Objects.requireNonNull(relation, "relation");
        parentRunId = optionalSha256(parentRunId, "parentRunId");
        parentPlanHash = optionalSha256(parentPlanHash, "parentPlanHash");
        changedPlanParameter = optionalText(
            changedPlanParameter, "changedPlanParameter");
        validateRelation(
            relation,
            runId,
            parentRunId,
            parentPlanHash,
            changedPlanParameter
        );
        input = Objects.requireNonNull(input, "input");
        plan = Objects.requireNonNull(plan, "plan");
        outcome = Objects.requireNonNull(outcome, "outcome");
        artifacts = completeArtifacts(artifacts);
        revisions = Objects.requireNonNull(revisions, "revisions");
        claimBoundary = requireText(claimBoundary, "claimBoundary");
        if (!CLAIM_BOUNDARY.equals(claimBoundary)) {
            throw new IllegalArgumentException(
                "unsupported run-workspace claim boundary");
        }
        contentHash = requireSha256(contentHash, "contentHash");
        String expected = workspaceHash(
            relation,
            parentRunId,
            parentPlanHash,
            changedPlanParameter,
            input,
            plan,
            outcome,
            artifacts,
            revisions
        );
        if (!expected.equals(contentHash) || !runId.equals(contentHash)) {
            throw new IllegalArgumentException(
                "runId/contentHash does not match workspace content");
        }
    }

    /** Creates an independent root run. */
    public static RepresentationDiscoveryRunWorkspace create(
        RepresentationDiscoveryRunInput input,
        RepresentationDiscoveryRunPlan plan,
        RepresentationDiscoveryRunOutcome outcome,
        List<RepresentationDiscoveryArtifactReference> artifacts,
        RepresentationDiscoveryRevisionEvidence revisions
    ) {
        return createInternal(
            RunRelation.ROOT,
            "",
            "",
            "",
            input,
            plan,
            outcome,
            artifacts,
            revisions
        );
    }

    /**
     * Duplicates a run while permitting exactly one visible plan parameter to
     * change. The new run starts with zero consumed work and every artifact role
     * explicitly marked {@link ArtifactStatus#NOT_PRODUCED}.
     */
    public static RepresentationDiscoveryRunWorkspace duplicateWithOnePlanChange(
        RepresentationDiscoveryRunWorkspace parent,
        RepresentationDiscoveryRunPlan revisedPlan,
        RepresentationDiscoveryRevisionEvidence revisions
    ) {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(revisedPlan, "revisedPlan");
        String changed = parent.plan().singleChangedParameter(revisedPlan);
        return createInternal(
            RunRelation.DUPLICATED_ONE_PARAMETER,
            parent.runId(),
            parent.plan().contentHash(),
            changed,
            parent.input(),
            revisedPlan,
            RepresentationDiscoveryRunOutcome.created(),
            notProducedArtifacts(),
            revisions
        );
    }

    /**
     * Creates an immutable continuation revision with the same input and plan.
     * The parent remains unchanged and the continuation receives its own Run ID.
     */
    public static RepresentationDiscoveryRunWorkspace continueFrom(
        RepresentationDiscoveryRunWorkspace parent,
        RepresentationDiscoveryRunOutcome outcome,
        List<RepresentationDiscoveryArtifactReference> artifacts,
        RepresentationDiscoveryRevisionEvidence revisions
    ) {
        Objects.requireNonNull(parent, "parent");
        if (Objects.requireNonNull(outcome, "outcome").state()
                == TerminalState.CREATED) {
            throw new IllegalArgumentException(
                "a continuation must advance beyond CREATED");
        }
        return createInternal(
            RunRelation.CONTINUATION,
            parent.runId(),
            parent.plan().contentHash(),
            "",
            parent.input(),
            parent.plan(),
            outcome,
            artifacts,
            revisions
        );
    }

    /** Returns an available same-run artifact or fails visibly. */
    public RepresentationDiscoveryArtifactReference requireArtifact(
        ArtifactRole role,
        String expectedSchema
    ) {
        Objects.requireNonNull(role, "role");
        String schemaName = requireText(expectedSchema, "expectedSchema");
        RepresentationDiscoveryArtifactReference reference = artifacts.stream()
            .filter(candidate -> candidate.role() == role)
            .findFirst()
            .orElseThrow();
        if (reference.status() != ArtifactStatus.AVAILABLE) {
            throw new IllegalStateException(
                "artifact " + role + " is " + reference.status()
                    + ": " + reference.detail());
        }
        if (!reference.artifactSchema().equals(schemaName)) {
            throw new IllegalStateException(
                "artifact " + role + " has schema "
                    + reference.artifactSchema() + ", expected "
                    + schemaName);
        }
        return reference;
    }

    /** Creates an immutable selection correlated to this exact Run ID. */
    public RepresentationDiscoveryRunSelection selection(
        String candidateId,
        String stateId,
        String edgeId,
        String occurrencePath,
        String proofObligationId
    ) {
        return RepresentationDiscoveryRunSelection.create(
            runId,
            candidateId,
            stateId,
            edgeId,
            occurrencePath,
            proofObligationId
        );
    }

    public String toCanonicalJson() {
        try {
            return JSON.writeValueAsString(this);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                "Unable to render representation-discovery workspace",
                exception
            );
        }
    }


    /** Decodes only the exact canonical JSON representation. */
    public static RepresentationDiscoveryRunWorkspace fromCanonicalJson(
        String source
    ) {
        Objects.requireNonNull(source, "source");
        try {
            RepresentationDiscoveryRunWorkspace workspace = JSON.readValue(
                source,
                RepresentationDiscoveryRunWorkspace.class
            );
            if (!workspace.toCanonicalJson().equals(source)) {
                throw new IllegalArgumentException(
                    "run workspace JSON is not canonical");
            }
            return workspace;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                "invalid representation-discovery run workspace JSON",
                exception
            );
        }
    }

    /** Decodes canonical JSON while rejecting malformed UTF-8. */
    public static RepresentationDiscoveryRunWorkspace fromCanonicalBytes(
        byte[] source
    ) {
        Objects.requireNonNull(source, "source");
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(source))
                .toString();
            return fromCanonicalJson(text);
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException(
                "run workspace is not valid UTF-8",
                exception
            );
        }
    }

    /** Retains canonical bytes under the immutable Run ID. */
    public static RepresentationDiscoveryRunWorkspace retain(
        Path directory,
        RepresentationDiscoveryRunWorkspace workspace
    ) {
        return retain(
            directory,
            DEFAULT_MAX_RETAINED_WORKSPACE_BYTES,
            DEFAULT_MAX_RETAINED_RUNS,
            workspace
        );
    }

    /** Retains a workspace under explicit finite repository limits. */
    public static synchronized RepresentationDiscoveryRunWorkspace retain(
        Path directory,
        int maxWorkspaceBytes,
        int maxRuns,
        RepresentationDiscoveryRunWorkspace workspace
    ) {
        validateRetainedLimits(maxWorkspaceBytes, maxRuns);
        Objects.requireNonNull(workspace, "workspace");
        byte[] canonical = workspace.toCanonicalJson().getBytes(
            StandardCharsets.UTF_8);
        requireWithinRetainedByteLimit(canonical.length, maxWorkspaceBytes);
        Path root = ensureRetainedDirectory(directory);
        Path target = retainedPath(root, workspace.runId());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return requireIdenticalRetained(
                target,
                canonical,
                workspace.runId(),
                maxWorkspaceBytes
            );
        }
        if (validatedRetainedEntries(root, maxRuns).size() >= maxRuns) {
            throw new IllegalStateException(
                "representation-discovery run repository limit exceeded: "
                    + maxRuns);
        }

        Path temporary = null;
        try {
            temporary = Files.createTempFile(root, ".run-workspace-", ".tmp");
            if (Files.isSymbolicLink(temporary)) {
                throw new IllegalStateException(
                    "temporary run-workspace file is symbolic: " + temporary);
            }
            Files.write(
                temporary,
                canonical,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            );
            moveRetainedWithoutReplacement(temporary, target);
            temporary = null;
            return workspace;
        } catch (FileAlreadyExistsException exception) {
            return requireIdenticalRetained(
                target,
                canonical,
                workspace.runId(),
                maxWorkspaceBytes
            );
        } catch (IOException exception) {
            throw new IllegalStateException(
                "unable to retain representation-discovery run "
                    + workspace.runId(),
                exception
            );
        } finally {
            deleteTemporaryRetainedFile(temporary);
        }
    }

    /** Finds one retained workspace using the default finite limits. */
    public static Optional<RepresentationDiscoveryRunWorkspace> findRetained(
        Path directory,
        String runId
    ) {
        return findRetained(
            directory,
            DEFAULT_MAX_RETAINED_WORKSPACE_BYTES,
            DEFAULT_MAX_RETAINED_RUNS,
            runId
        );
    }

    /** Finds one retained workspace using explicit finite limits. */
    public static synchronized Optional<RepresentationDiscoveryRunWorkspace>
            findRetained(
        Path directory,
        int maxWorkspaceBytes,
        int maxRuns,
        String runId
    ) {
        validateRetainedLimits(maxWorkspaceBytes, maxRuns);
        String normalized = requireSha256(runId, "runId");
        Path root = ensureRetainedDirectory(directory);
        Path target = retainedPath(root, normalized);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        return Optional.of(loadRetained(
            target,
            normalized,
            maxWorkspaceBytes
        ));
    }

    /** Lists retained workspaces in deterministic Run-ID order. */
    public static List<RepresentationDiscoveryRunWorkspace> listRetained(
        Path directory
    ) {
        return listRetained(
            directory,
            DEFAULT_MAX_RETAINED_WORKSPACE_BYTES,
            DEFAULT_MAX_RETAINED_RUNS
        );
    }

    /** Lists retained workspaces under explicit finite limits. */
    public static synchronized List<RepresentationDiscoveryRunWorkspace>
            listRetained(
        Path directory,
        int maxWorkspaceBytes,
        int maxRuns
    ) {
        validateRetainedLimits(maxWorkspaceBytes, maxRuns);
        Path root = ensureRetainedDirectory(directory);
        return validatedRetainedEntries(root, maxRuns).stream()
            .map(path -> loadRetained(
                path,
                retainedRunId(path),
                maxWorkspaceBytes
            ))
            .toList();
    }

    private static RepresentationDiscoveryRunWorkspace requireIdenticalRetained(
        Path target,
        byte[] expected,
        String runId,
        int maxWorkspaceBytes
    ) {
        RepresentationDiscoveryRunWorkspace retained = loadRetained(
            target,
            runId,
            maxWorkspaceBytes
        );
        byte[] actual = readRetainedBytes(target, maxWorkspaceBytes);
        if (!Arrays.equals(actual, expected)) {
            throw new IllegalStateException(
                "immutable run identity already contains different bytes: "
                    + runId);
        }
        return retained;
    }

    private static RepresentationDiscoveryRunWorkspace loadRetained(
        Path path,
        String expectedRunId,
        int maxWorkspaceBytes
    ) {
        requireRegularRetainedFile(path);
        RepresentationDiscoveryRunWorkspace workspace = fromCanonicalBytes(
            readRetainedBytes(path, maxWorkspaceBytes)
        );
        if (!expectedRunId.equals(workspace.runId())) {
            throw new IllegalStateException(
                "run-workspace filename and Run ID differ: " + path);
        }
        return workspace;
    }

    private static byte[] readRetainedBytes(
        Path path,
        int maxWorkspaceBytes
    ) {
        requireRegularRetainedFile(path);
        try {
            long size = Files.size(path);
            requireWithinRetainedByteLimit(size, maxWorkspaceBytes);
            byte[] bytes = Files.readAllBytes(path);
            requireWithinRetainedByteLimit(bytes.length, maxWorkspaceBytes);
            return bytes;
        } catch (IOException exception) {
            throw new IllegalStateException(
                "unable to read retained run workspace: " + path,
                exception
            );
        }
    }

    private static List<Path> validatedRetainedEntries(
        Path root,
        int maxRuns
    ) {
        try (var stream = Files.list(root)) {
            List<Path> entries = stream
                .sorted(Comparator.comparing(path ->
                    path.getFileName().toString()))
                .toList();
            if (entries.size() > maxRuns) {
                throw new IllegalStateException(
                    "representation-discovery run repository limit exceeded: "
                        + entries.size() + " > " + maxRuns);
            }
            for (Path entry : entries) {
                requireRegularRetainedFile(entry);
                if (!RETAINED_FILE_NAME.matcher(
                        entry.getFileName().toString()).matches()) {
                    throw new IllegalStateException(
                        "unexpected run repository entry: " + entry);
                }
            }
            return entries;
        } catch (IOException exception) {
            throw new IllegalStateException(
                "unable to list representation-discovery runs",
                exception
            );
        }
    }

    private static Path ensureRetainedDirectory(Path directory) {
        Path absolute = Objects.requireNonNull(directory, "directory")
            .toAbsolutePath()
            .normalize();
        rejectSymbolicRetainedComponents(absolute);
        try {
            if (!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(absolute);
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                "unable to create run repository directory: " + absolute,
                exception
            );
        }
        rejectSymbolicRetainedComponents(absolute);
        if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(
                "run repository path is not a real directory: " + absolute);
        }
        return absolute;
    }

    private static void rejectSymbolicRetainedComponents(Path absolute) {
        Path current = absolute.getRoot();
        for (Path component : absolute) {
            current = current == null
                ? component
                : current.resolve(component);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && Files.isSymbolicLink(current)) {
                throw new IllegalStateException(
                    "run repository contains symbolic path component: "
                        + current);
            }
        }
    }

    private static void requireRegularRetainedFile(Path path) {
        if (Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(
                "run repository entry is not a real regular file: " + path);
        }
    }

    private static Path retainedPath(Path root, String runId) {
        String normalized = requireSha256(runId, "runId");
        String digest = normalized.substring("sha256:".length());
        Path path = root.resolve(digest + ".json").normalize();
        if (!root.equals(path.getParent())) {
            throw new IllegalArgumentException(
                "run workspace path escapes repository directory");
        }
        return path;
    }

    private static String retainedRunId(Path path) {
        Matcher matcher = RETAINED_FILE_NAME.matcher(
            path.getFileName().toString());
        if (!matcher.matches()) {
            throw new IllegalStateException(
                "invalid run repository filename: " + path);
        }
        return "sha256:" + matcher.group(1);
    }

    private static void validateRetainedLimits(
        int maxWorkspaceBytes,
        int maxRuns
    ) {
        if (maxWorkspaceBytes < 1) {
            throw new IllegalArgumentException(
                "maxWorkspaceBytes must be positive");
        }
        if (maxRuns < 1) {
            throw new IllegalArgumentException("maxRuns must be positive");
        }
    }

    private static void requireWithinRetainedByteLimit(
        long length,
        int maxWorkspaceBytes
    ) {
        if (length > maxWorkspaceBytes) {
            throw new IllegalStateException(
                "run workspace exceeds byte limit " + maxWorkspaceBytes
                    + ": " + length);
        }
    }

    private static void moveRetainedWithoutReplacement(
        Path source,
        Path target
    ) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private static void deleteTemporaryRetainedFile(Path temporary) {
        if (temporary == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException exception) {
            // A leftover non-canonical entry is rejected by list/find.
        }
    }

    public static List<RepresentationDiscoveryArtifactReference>
            notProducedArtifacts() {
        return EnumSet.allOf(ArtifactRole.class).stream()
            .map(RepresentationDiscoveryArtifactReference::notProduced)
            .toList();
    }

    private static RepresentationDiscoveryRunWorkspace createInternal(
        RunRelation relation,
        String parentRunId,
        String parentPlanHash,
        String changedPlanParameter,
        RepresentationDiscoveryRunInput input,
        RepresentationDiscoveryRunPlan plan,
        RepresentationDiscoveryRunOutcome outcome,
        List<RepresentationDiscoveryArtifactReference> artifacts,
        RepresentationDiscoveryRevisionEvidence revisions
    ) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(outcome, "outcome");
        List<RepresentationDiscoveryArtifactReference> complete =
            completeArtifacts(artifacts);
        Objects.requireNonNull(revisions, "revisions");
        String hash = workspaceHash(
            relation,
            parentRunId,
            parentPlanHash,
            changedPlanParameter,
            input,
            plan,
            outcome,
            complete,
            revisions
        );
        return new RepresentationDiscoveryRunWorkspace(
            SCHEMA,
            hash,
            relation,
            parentRunId,
            parentPlanHash,
            changedPlanParameter,
            input,
            plan,
            outcome,
            complete,
            revisions,
            CLAIM_BOUNDARY,
            hash
        );
    }

    private static String workspaceHash(
        RunRelation relation,
        String parentRunId,
        String parentPlanHash,
        String changedPlanParameter,
        RepresentationDiscoveryRunInput input,
        RepresentationDiscoveryRunPlan plan,
        RepresentationDiscoveryRunOutcome outcome,
        List<RepresentationDiscoveryArtifactReference> artifacts,
        RepresentationDiscoveryRevisionEvidence revisions
    ) {
        StringBuilder descriptor = new StringBuilder();
        append(descriptor, SCHEMA);
        append(descriptor, relation.name());
        append(descriptor, parentRunId);
        append(descriptor, parentPlanHash);
        append(descriptor, changedPlanParameter);
        append(descriptor, input.contentHash());
        append(descriptor, plan.contentHash());
        append(descriptor, outcome.contentHash());
        append(descriptor, Integer.toString(artifacts.size()));
        artifacts.forEach(reference ->
            append(descriptor, reference.contentHash()));
        append(descriptor, revisions.contentHash());
        append(descriptor, CLAIM_BOUNDARY);
        return sha256(descriptor.toString());
    }

    private static void validateRelation(
        RunRelation relation,
        String runId,
        String parentRunId,
        String parentPlanHash,
        String changedPlanParameter
    ) {
        if (!parentRunId.isEmpty() && parentRunId.equals(runId)) {
            throw new IllegalArgumentException(
                "a workspace cannot be its own parent");
        }
        switch (relation) {
            case ROOT -> validateRootRelation(
                parentRunId,
                parentPlanHash,
                changedPlanParameter
            );
            case CONTINUATION -> validateContinuationRelation(
                parentRunId,
                parentPlanHash,
                changedPlanParameter
            );
            case DUPLICATED_ONE_PARAMETER -> validateDuplicationRelation(
                parentRunId,
                parentPlanHash,
                changedPlanParameter
            );
        }
    }

    private static void validateRootRelation(
        String parentRunId,
        String parentPlanHash,
        String changedPlanParameter
    ) {
        if (!parentRunId.isEmpty()
                || !parentPlanHash.isEmpty()
                || !changedPlanParameter.isEmpty()) {
            throw new IllegalArgumentException(
                "a root run cannot carry parent evidence");
        }
    }

    private static void validateContinuationRelation(
        String parentRunId,
        String parentPlanHash,
        String changedPlanParameter
    ) {
        requireParent(parentRunId, parentPlanHash);
        if (!changedPlanParameter.isEmpty()) {
            throw new IllegalArgumentException(
                "a continuation cannot claim a plan change");
        }
    }

    private static void validateDuplicationRelation(
        String parentRunId,
        String parentPlanHash,
        String changedPlanParameter
    ) {
        requireParent(parentRunId, parentPlanHash);
        if (!RepresentationDiscoveryRunPlan.PARAMETERS.contains(
                changedPlanParameter)) {
            throw new IllegalArgumentException(
                "unsupported changed plan parameter: "
                    + changedPlanParameter);
        }
    }

    private static void requireParent(
        String parentRunId,
        String parentPlanHash
    ) {
        if (parentRunId.isEmpty() || parentPlanHash.isEmpty()) {
            throw new IllegalArgumentException(
                "derived runs require parent run and plan identities");
        }
    }

    private static List<RepresentationDiscoveryArtifactReference>
            completeArtifacts(
        List<RepresentationDiscoveryArtifactReference> references
    ) {
        Objects.requireNonNull(references, "artifacts");
        List<RepresentationDiscoveryArtifactReference> sorted =
            references.stream()
                .map(reference -> Objects.requireNonNull(
                    reference, "artifact"))
                .sorted(Comparator.comparing(reference ->
                    reference.role().name()))
                .toList();
        Set<ArtifactRole> roles = new HashSet<>();
        for (RepresentationDiscoveryArtifactReference reference : sorted) {
            if (!roles.add(reference.role())) {
                throw new IllegalArgumentException(
                    "duplicate artifact role: " + reference.role());
            }
        }
        Set<ArtifactRole> missing = EnumSet.allOf(ArtifactRole.class);
        missing.removeAll(roles);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                "missing explicit artifact roles: " + missing);
        }
        return sorted;
    }

    public enum RunRelation {
        ROOT,
        DUPLICATED_ONE_PARAMETER,
        CONTINUATION
    }
}
