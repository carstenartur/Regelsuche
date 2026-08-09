package de.regelsuche.evolution;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.evolution.EvolutionRewriteProgramCheckpointArtifact.CheckpointArtifactManifest;
import de.regelsuche.evolution.EvolutionRewriteProgramCheckpointArtifact.LoadedCheckpoint;
import de.regelsuche.json.JsonWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Durable outer commit boundary for a TRAIN checkpoint plus the execution plan
 * and population-execution protocol under which it may be resumed.
 *
 * <p>The existing {@link EvolutionRewriteProgramCheckpointArtifact} remains the
 * authority for checkpoint bytes. This class stores that artifact unchanged in
 * a child directory and commits a small outer manifest last. A missing outer
 * manifest therefore never authorizes resume.</p>
 */
public final class ExecutionProtocolBoundEvolutionRewriteProgramCheckpointArtifact {
    public static final String SCHEMA =
        "regelsuche.evolution-rewrite-program-execution-protocol-bound-checkpoint-artifact/v1";
    public static final String COMMIT_PROTOCOL =
        "OUTER_MANIFEST_LAST_ATOMIC_RENAME_V1";
    public static final String MANIFEST_FILE_NAME =
        "execution-checkpoint-binding.json";
    public static final String CHECKPOINT_DIRECTORY_NAME = "checkpoint";
    private static final long MAX_MANIFEST_BYTES = 256L * 1024L;
    private static final ObjectMapper JSON = new ObjectMapper(
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final EvolutionRewriteProgramCheckpointArtifact checkpointArtifact;

    public ExecutionProtocolBoundEvolutionRewriteProgramCheckpointArtifact() {
        this(new EvolutionRewriteProgramCheckpointArtifact());
    }

    ExecutionProtocolBoundEvolutionRewriteProgramCheckpointArtifact(
        EvolutionRewriteProgramCheckpointArtifact checkpointArtifact
    ) {
        this.checkpointArtifact = Objects.requireNonNull(
            checkpointArtifact, "checkpointArtifact");
    }

    public BoundCheckpointArtifactManifest write(
        Path outputDirectory,
        ExecutionProtocolBoundEvolutionRewriteProgramPopulationCheckpoint checkpoint
    ) {
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        Objects.requireNonNull(checkpoint, "checkpoint");
        Path directory = outputDirectory.toAbsolutePath().normalize();
        try {
            requireSafeAncestry(directory);
            Files.createDirectories(directory);
            requireSafeAncestry(directory);
            requireOnlyKnownEntries(directory, false);
            Files.deleteIfExists(directory.resolve(MANIFEST_FILE_NAME));

            CheckpointArtifactManifest nested = checkpointArtifact.write(
                directory.resolve(CHECKPOINT_DIRECTORY_NAME),
                checkpoint.checkpoint());
            BoundCheckpointArtifactManifest manifest =
                BoundCheckpointArtifactManifest.create(
                    checkpoint.contentHash(),
                    checkpoint.checkpoint().contentHash(),
                    nested.contentHash(),
                    checkpoint.executionPlanHash(),
                    checkpoint.executionProtocolHash());
            atomicWrite(
                directory.resolve(MANIFEST_FILE_NAME),
                manifest.toCanonicalJson());
            requireOnlyKnownEntries(directory, true);
            return manifest;
        } catch (IOException exception) {
            throw new UncheckedIOException(
                "Could not commit execution-bound checkpoint artifact",
                exception);
        }
    }

    public LoadedBoundCheckpoint read(
        Path inputDirectory,
        EvolutionRewriteProgramPopulationExecutionPlan executionPlan,
        EvolutionRewriteProgramPopulationExecutionProtocol executionProtocol
    ) {
        Objects.requireNonNull(inputDirectory, "inputDirectory");
        Objects.requireNonNull(executionPlan, "executionPlan");
        Objects.requireNonNull(executionProtocol, "executionProtocol");
        Path directory = inputDirectory.toAbsolutePath().normalize();
        try {
            requireSafeAncestry(directory);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException(
                    "execution-bound checkpoint directory does not exist");
            }
            requireOnlyKnownEntries(directory, true);
            String manifestJson = readUtf8Bounded(
                directory.resolve(MANIFEST_FILE_NAME));
            BoundCheckpointArtifactManifest manifest = parse(manifestJson);
            if (!manifest.toCanonicalJson().equals(manifestJson)) {
                throw new IllegalArgumentException(
                    "execution-bound checkpoint manifest is not canonical");
            }
            if (!manifest.executionPlanHash().equals(executionPlan.contentHash())
                    || !manifest.executionProtocolHash().equals(
                        executionProtocol.contentHash())) {
                throw new IllegalArgumentException(
                    "execution-bound checkpoint manifest differs from requested execution identity");
            }

            LoadedCheckpoint nested = checkpointArtifact.read(
                directory.resolve(CHECKPOINT_DIRECTORY_NAME));
            if (!manifest.checkpointHash().equals(
                    nested.checkpoint().contentHash())
                    || !manifest.checkpointArtifactManifestHash().equals(
                        nested.manifest().contentHash())) {
                throw new IllegalArgumentException(
                    "nested checkpoint artifact differs from outer execution binding");
            }
            ExecutionProtocolBoundEvolutionRewriteProgramPopulationCheckpoint
                reconstructed =
                    ExecutionProtocolBoundEvolutionRewriteProgramPopulationCheckpoint
                        .create(
                            nested.checkpoint(),
                            executionPlan,
                            executionProtocol);
            if (!manifest.boundCheckpointHash().equals(
                    reconstructed.contentHash())) {
                throw new IllegalArgumentException(
                    "reconstructed execution-bound checkpoint differs from outer manifest");
            }
            return new LoadedBoundCheckpoint(reconstructed, manifest);
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                "Could not read execution-bound checkpoint artifact",
                exception);
        }
    }

    private static BoundCheckpointArtifactManifest parse(String json) {
        try {
            return JSON.readValue(json, BoundCheckpointArtifactManifest.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                "Invalid execution-bound checkpoint manifest JSON",
                exception);
        }
    }

    private static String readUtf8Bounded(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException(
                "execution-bound checkpoint manifest must be a regular file");
        }
        long size = Files.size(path);
        if (size < 1 || size > MAX_MANIFEST_BYTES) {
            throw new IllegalArgumentException(
                "execution-bound checkpoint manifest size is out of bounds");
        }
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length != size) {
            throw new IllegalArgumentException(
                "execution-bound checkpoint manifest changed while being read");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException(
                "execution-bound checkpoint manifest is not valid UTF-8",
                exception);
        }
    }

    private static void requireSafeAncestry(Path path) {
        for (Path current = path; current != null; current = current.getParent()) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException(
                    "execution-bound checkpoint ancestry must not contain symbolic links");
            }
        }
    }

    private static void requireOnlyKnownEntries(
        Path directory,
        boolean requireManifest
    ) throws IOException {
        if (Files.isSymbolicLink(directory)) {
            throw new IllegalArgumentException(
                "execution-bound checkpoint directory must not be a symbolic link");
        }
        Set<String> names = new HashSet<>();
        try (var entries = Files.list(directory)) {
            entries.forEach(entry -> names.add(entry.getFileName().toString()));
        }
        Set<String> allowed = Set.of(
            MANIFEST_FILE_NAME,
            CHECKPOINT_DIRECTORY_NAME);
        if (!allowed.containsAll(names)) {
            throw new IllegalArgumentException(
                "execution-bound checkpoint directory contains unexpected entries");
        }
        Path checkpointDirectory = directory.resolve(CHECKPOINT_DIRECTORY_NAME);
        if (names.contains(CHECKPOINT_DIRECTORY_NAME)
                && (!Files.isDirectory(
                    checkpointDirectory,
                    LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(checkpointDirectory))) {
            throw new IllegalArgumentException(
                "nested checkpoint entry must be a regular directory");
        }
        Path manifest = directory.resolve(MANIFEST_FILE_NAME);
        if (names.contains(MANIFEST_FILE_NAME)
                && (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(manifest))) {
            throw new IllegalArgumentException(
                "execution binding manifest must be a regular file");
        }
        if (requireManifest
                && (!names.contains(MANIFEST_FILE_NAME)
                    || !names.contains(CHECKPOINT_DIRECTORY_NAME))) {
            throw new IllegalArgumentException(
                "execution-bound checkpoint artifact is incomplete");
        }
    }

    private static void atomicWrite(Path target, String content)
            throws IOException {
        Objects.requireNonNull(content, "content");
        Path directory = target.getParent();
        Path temporary = directory.resolve(
            "." + target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                channel.write(ByteBuffer.wrap(bytes));
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

    public record BoundCheckpointArtifactManifest(
        String schema,
        String boundCheckpointHash,
        String checkpointHash,
        String checkpointArtifactManifestHash,
        String executionPlanHash,
        String executionProtocolHash,
        String commitProtocol,
        String contentHash
    ) {
        public BoundCheckpointArtifactManifest {
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported execution-bound checkpoint artifact schema");
            }
            EvolutionGenome.requireSha256(
                boundCheckpointHash, "boundCheckpointHash");
            EvolutionGenome.requireSha256(checkpointHash, "checkpointHash");
            EvolutionGenome.requireSha256(
                checkpointArtifactManifestHash,
                "checkpointArtifactManifestHash");
            EvolutionGenome.requireSha256(
                executionPlanHash, "executionPlanHash");
            EvolutionGenome.requireSha256(
                executionProtocolHash, "executionProtocolHash");
            if (!COMMIT_PROTOCOL.equals(commitProtocol)) {
                throw new IllegalArgumentException(
                    "unsupported execution-bound checkpoint commit protocol");
            }
            EvolutionGenome.requireSha256(contentHash, "contentHash");
            String expected = EvolutionGenome.hash(render(
                boundCheckpointHash,
                checkpointHash,
                checkpointArtifactManifestHash,
                executionPlanHash,
                executionProtocolHash,
                null));
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "execution-bound checkpoint artifact contentHash mismatch");
            }
        }

        static BoundCheckpointArtifactManifest create(
            String boundCheckpointHash,
            String checkpointHash,
            String checkpointArtifactManifestHash,
            String executionPlanHash,
            String executionProtocolHash
        ) {
            String hash = EvolutionGenome.hash(render(
                boundCheckpointHash,
                checkpointHash,
                checkpointArtifactManifestHash,
                executionPlanHash,
                executionProtocolHash,
                null));
            return new BoundCheckpointArtifactManifest(
                SCHEMA,
                boundCheckpointHash,
                checkpointHash,
                checkpointArtifactManifestHash,
                executionPlanHash,
                executionProtocolHash,
                COMMIT_PROTOCOL,
                hash);
        }

        public String toCanonicalJson() {
            return render(
                boundCheckpointHash,
                checkpointHash,
                checkpointArtifactManifestHash,
                executionPlanHash,
                executionProtocolHash,
                contentHash);
        }

        private static String render(
            String boundCheckpointHash,
            String checkpointHash,
            String checkpointArtifactManifestHash,
            String executionPlanHash,
            String executionProtocolHash,
            String contentHash
        ) {
            JsonWriter json = new JsonWriter().beginObject()
                .property("schema", SCHEMA)
                .property("boundCheckpointHash", boundCheckpointHash)
                .property("checkpointHash", checkpointHash)
                .property(
                    "checkpointArtifactManifestHash",
                    checkpointArtifactManifestHash)
                .property("executionPlanHash", executionPlanHash)
                .property("executionProtocolHash", executionProtocolHash)
                .property("commitProtocol", COMMIT_PROTOCOL);
            if (contentHash != null) {
                json.property("contentHash", contentHash);
            }
            return json.endObject().toString();
        }
    }

    public record LoadedBoundCheckpoint(
        ExecutionProtocolBoundEvolutionRewriteProgramPopulationCheckpoint checkpoint,
        BoundCheckpointArtifactManifest manifest
    ) {
        public LoadedBoundCheckpoint {
            Objects.requireNonNull(checkpoint, "checkpoint");
            Objects.requireNonNull(manifest, "manifest");
            if (!checkpoint.contentHash().equals(
                    manifest.boundCheckpointHash())) {
                throw new IllegalArgumentException(
                    "loaded execution-bound checkpoint differs from manifest");
            }
        }
    }
}
