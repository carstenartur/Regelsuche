package de.regelsuche.evolution;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.evolution.EvolutionRewriteProgramPopulationEngine.CandidateEvaluation;
import de.regelsuche.evolution.EvolutionRewriteProgramPopulationEngine.GenerationOutcome;
import de.regelsuche.evolution.EvolutionRewriteProgramPopulationEngine.GenerationReport;
import de.regelsuche.evolution.EvolutionRewriteProgramPopulationEngine.LineageEdge;
import de.regelsuche.evolution.EvolutionRewriteProgramPopulationEngine.MutationRejection;
import de.regelsuche.evolution.EvolutionRewriteProgramPopulationEngine.PopulationCheckpoint;
import de.regelsuche.evolution.EvolutionStudyPlan.FitnessComponent;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Durable, manifest-bound persistence for TRAIN-only rewrite-program population
 * checkpoints.
 *
 * <p>The population engine remains the authority for checkpoint semantics. This
 * class adds only a strict process-independent byte boundary. The canonical
 * checkpoint header and the complete resumable state are committed atomically;
 * the manifest is written last, so its absence denotes an incomplete export.</p>
 */
public final class EvolutionRewriteProgramCheckpointArtifact {
    public static final String MANIFEST_SCHEMA =
        "regelsuche.evolution-rewrite-program-checkpoint-artifact/v1";
    public static final String STATE_SCHEMA =
        "regelsuche.evolution-rewrite-program-checkpoint-state/v1";
    public static final String COMMIT_PROTOCOL = "MANIFEST_LAST_ATOMIC_RENAME";
    public static final String MANIFEST_FILE_NAME = "checkpoint-artifact-manifest.json";
    public static final String CHECKPOINT_FILE_NAME = "checkpoint.json";
    public static final String STATE_FILE_NAME = "state.json";
    private static final long MAX_MANIFEST_BYTES = 256L * 1024L;
    private static final long MAX_PAYLOAD_BYTES = 32L * 1024L * 1024L;
    private static final ObjectMapper JSON = new ObjectMapper(
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final EvolutionGenomeCodec GENOME_CODEC = new EvolutionGenomeCodec();
    private static final EvolutionRewriteProgramPlanCodec PLAN_CODEC =
        new EvolutionRewriteProgramPlanCodec();

    public CheckpointArtifactManifest write(
        Path outputDirectory,
        PopulationCheckpoint checkpoint
    ) {
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        Objects.requireNonNull(checkpoint, "checkpoint");

        CheckpointState state = CheckpointState.create(checkpoint);
        List<Payload> payloads = List.of(
            payload(
                ArtifactRole.CHECKPOINT,
                checkpoint.contentHash(),
                checkpoint.toCanonicalJson()),
            payload(
                ArtifactRole.STATE,
                state.contentHash(),
                state.toCanonicalJson()));
        CheckpointArtifactManifest manifest = CheckpointArtifactManifest.create(
            checkpoint.contentHash(),
            state.contentHash(),
            payloads.stream().map(Payload::artifact).toList());

        Path directory = outputDirectory.toAbsolutePath().normalize();
        try {
            requireSafeAncestry(directory);
            Files.createDirectories(directory);
            requireSafeAncestry(directory);
            requireOnlyKnownEntries(directory, false);
            Files.deleteIfExists(directory.resolve(MANIFEST_FILE_NAME));
            for (Payload value : payloads.stream()
                    .sorted(Comparator.comparing(item -> item.artifact().fileName()))
                    .toList()) {
                atomicWrite(
                    directory.resolve(value.artifact().fileName()),
                    value.content());
            }
            atomicWrite(
                directory.resolve(MANIFEST_FILE_NAME),
                manifest.toCanonicalJson());
            requireOnlyKnownEntries(directory, true);
            return manifest;
        } catch (IOException exception) {
            throw new UncheckedIOException(
                "Could not commit rewrite-program checkpoint artifact",
                exception);
        }
    }

    public LoadedCheckpoint read(Path inputDirectory) {
        Objects.requireNonNull(inputDirectory, "inputDirectory");
        Path directory = inputDirectory.toAbsolutePath().normalize();
        try {
            requireSafeAncestry(directory);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException(
                    "checkpoint artifact directory does not exist");
            }
            requireOnlyKnownEntries(directory, true);

            String manifestJson = readUtf8Bounded(
                directory.resolve(MANIFEST_FILE_NAME),
                MAX_MANIFEST_BYTES);
            CheckpointArtifactManifest manifest = parseManifest(manifestJson);
            if (!manifest.toCanonicalJson().equals(manifestJson)) {
                throw new IllegalArgumentException(
                    "checkpoint artifact manifest is not canonical");
            }

            Map<ArtifactRole, String> payloads = new EnumMap<>(ArtifactRole.class);
            for (CheckpointArtifact artifact : manifest.artifacts()) {
                String text = readUtf8Bounded(
                    directory.resolve(artifact.fileName()),
                    MAX_PAYLOAD_BYTES);
                byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                if (bytes.length != artifact.byteLength()
                        || !sha256(bytes).equals(artifact.byteHash())) {
                    throw new IllegalArgumentException(
                        "checkpoint artifact bytes do not match manifest for "
                            + artifact.fileName());
                }
                payloads.put(artifact.role(), text);
            }

            String checkpointJson = requiredPayload(payloads, ArtifactRole.CHECKPOINT);
            CheckpointHeader header = parseCheckpointHeader(checkpointJson);
            if (!manifest.checkpointHash().equals(header.contentHash())) {
                throw new IllegalArgumentException(
                    "manifest checkpoint root does not match checkpoint payload");
            }
            requireRoleRoot(
                manifest.artifacts(), ArtifactRole.CHECKPOINT, header.contentHash());

            String stateJson = requiredPayload(payloads, ArtifactRole.STATE);
            CheckpointState state = parseState(stateJson);
            if (!manifest.stateHash().equals(state.contentHash())) {
                throw new IllegalArgumentException(
                    "manifest state root does not match state payload");
            }
            requireRoleRoot(
                manifest.artifacts(), ArtifactRole.STATE, state.contentHash());
            if (!header.contentHash().equals(state.checkpointHash())) {
                throw new IllegalArgumentException(
                    "checkpoint state does not bind the checkpoint root");
            }
            requireCheckpointBinding(header, state);

            PopulationCheckpoint checkpoint = new PopulationCheckpoint(
                header.schema(),
                header.studyPlanHash(),
                header.trainSuiteHash(),
                header.mutationCatalogHash(),
                header.seedCandidateHashes(),
                header.completedGeneration(),
                state.candidates().stream()
                    .map(PopulationCandidate::candidate)
                    .toList(),
                state.evaluations(),
                state.generationReports(),
                header.mutationAttempts(),
                header.trainEvaluations(),
                header.validationStatus(),
                header.finalTestStatus(),
                header.contentHash());
            if (!checkpoint.toCanonicalJson().equals(checkpointJson)) {
                throw new IllegalArgumentException(
                    "checkpoint payload is not canonical");
            }
            return new LoadedCheckpoint(checkpoint, manifest);
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                "Could not read rewrite-program checkpoint artifact",
                exception);
        }
    }

    private static void requireCheckpointBinding(
        CheckpointHeader header,
        CheckpointState state
    ) {
        List<String> populationHashes = state.candidates().stream()
            .map(PopulationCandidate::candidateHash)
            .toList();
        if (!header.populationCandidateHashes().equals(populationHashes)) {
            throw new IllegalArgumentException(
                "checkpoint population roots do not match durable state");
        }
        List<String> evaluationHashes = state.evaluations().stream()
            .map(CandidateEvaluation::contentHash)
            .toList();
        if (!header.evaluationHashes().equals(evaluationHashes)) {
            throw new IllegalArgumentException(
                "checkpoint evaluation roots do not match durable state");
        }
        List<String> generationHashes = state.generationReports().stream()
            .map(GenerationReport::contentHash)
            .toList();
        if (!header.generationReportHashes().equals(generationHashes)) {
            throw new IllegalArgumentException(
                "checkpoint generation roots do not match durable state");
        }
    }

    private static Payload payload(
        ArtifactRole role,
        String sourceContentHash,
        String content
    ) {
        byte[] bytes = Objects.requireNonNull(content, "content")
            .getBytes(StandardCharsets.UTF_8);
        return new Payload(
            new CheckpointArtifact(
                role.fileName(),
                role,
                sourceContentHash,
                sha256(bytes),
                bytes.length),
            content);
    }

    private static String requiredPayload(
        Map<ArtifactRole, String> payloads,
        ArtifactRole role
    ) {
        String value = payloads.get(role);
        if (value == null) {
            throw new IllegalArgumentException(
                "missing checkpoint artifact payload for " + role);
        }
        return value;
    }

    private static CheckpointHeader parseCheckpointHeader(String json) {
        try {
            ObjectNode root = object(JSON.readTree(json), "checkpoint");
            requireExactFields(root, Set.of(
                "schema",
                "studyPlanHash",
                "trainSuiteHash",
                "mutationCatalogHash",
                "seedCandidateHashes",
                "completedGeneration",
                "populationCandidateHashes",
                "evaluationHashes",
                "generationReportHashes",
                "mutationAttempts",
                "trainEvaluations",
                "validationStatus",
                "finalTestStatus",
                "contentHash"), "checkpoint");
            return new CheckpointHeader(
                text(root, "schema", "checkpoint"),
                text(root, "studyPlanHash", "checkpoint"),
                text(root, "trainSuiteHash", "checkpoint"),
                text(root, "mutationCatalogHash", "checkpoint"),
                strings(root, "seedCandidateHashes", "checkpoint"),
                integer(root, "completedGeneration", "checkpoint"),
                strings(root, "populationCandidateHashes", "checkpoint"),
                strings(root, "evaluationHashes", "checkpoint"),
                strings(root, "generationReportHashes", "checkpoint"),
                integer(root, "mutationAttempts", "checkpoint"),
                integer(root, "trainEvaluations", "checkpoint"),
                text(root, "validationStatus", "checkpoint"),
                text(root, "finalTestStatus", "checkpoint"),
                text(root, "contentHash", "checkpoint"));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid checkpoint JSON", exception);
        }
    }

    private static CheckpointState parseState(String json) {
        try {
            ObjectNode root = object(JSON.readTree(json), "checkpoint state");
            requireExactFields(root, Set.of(
                "schema",
                "checkpointHash",
                "candidates",
                "evaluations",
                "generationReports",
                "contentHash"), "checkpoint state");

            List<PopulationCandidate> candidates = parseCandidates(
                required(root, "candidates", "checkpoint state"));
            List<CandidateEvaluation> evaluations = parseEvaluations(
                required(root, "evaluations", "checkpoint state"));
            List<GenerationReport> generations = parseGenerations(
                required(root, "generationReports", "checkpoint state"),
                evaluations);
            CheckpointState state = new CheckpointState(
                text(root, "schema", "checkpoint state"),
                text(root, "checkpointHash", "checkpoint state"),
                candidates,
                evaluations,
                generations,
                text(root, "contentHash", "checkpoint state"));
            if (!state.toCanonicalJson().equals(json)) {
                throw new IllegalArgumentException(
                    "checkpoint state payload is not canonical");
            }
            return state;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                "Invalid checkpoint state JSON", exception);
        }
    }

    private static List<PopulationCandidate> parseCandidates(JsonNode values) {
        if (!values.isArray()) {
            throw new IllegalArgumentException("checkpoint candidates must be an array");
        }
        List<PopulationCandidate> result = new ArrayList<>();
        for (JsonNode value : values) {
            ObjectNode item = object(value, "checkpoint candidate");
            requireExactFields(item, Set.of(
                "candidateHash",
                "alphaStructuralHash",
                "genomeJson",
                "planJson"), "checkpoint candidate");
            result.add(new PopulationCandidate(
                text(item, "candidateHash", "checkpoint candidate"),
                text(item, "alphaStructuralHash", "checkpoint candidate"),
                text(item, "genomeJson", "checkpoint candidate"),
                text(item, "planJson", "checkpoint candidate")));
        }
        return List.copyOf(result);
    }

    private static List<CandidateEvaluation> parseEvaluations(JsonNode values) {
        if (!values.isArray()) {
            throw new IllegalArgumentException("checkpoint evaluations must be an array");
        }
        List<CandidateEvaluation> result = new ArrayList<>();
        for (JsonNode value : values) {
            ObjectNode item = object(value, "candidate evaluation");
            requireExactFields(item, Set.of(
                "candidateHash",
                "alphaStructuralHash",
                "rawComponents",
                "blockers",
                "scalarFitness",
                "evidenceHash",
                "contentHash"), "candidate evaluation");
            ObjectNode components = object(
                required(item, "rawComponents", "candidate evaluation"),
                "candidate evaluation rawComponents");
            Map<FitnessComponent, Integer> raw = new EnumMap<>(FitnessComponent.class);
            Iterator<Map.Entry<String, JsonNode>> fields = components.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                FitnessComponent component = enumValue(
                    FitnessComponent.class, field.getKey());
                if (!field.getValue().isIntegralNumber()
                        || !field.getValue().canConvertToInt()) {
                    throw new IllegalArgumentException(
                        "fitness component value must be an integer");
                }
                raw.put(component, field.getValue().intValue());
            }
            result.add(new CandidateEvaluation(
                text(item, "candidateHash", "candidate evaluation"),
                text(item, "alphaStructuralHash", "candidate evaluation"),
                raw,
                strings(item, "blockers", "candidate evaluation"),
                integer(item, "scalarFitness", "candidate evaluation"),
                nullableText(item, "evidenceHash", "candidate evaluation"),
                text(item, "contentHash", "candidate evaluation")));
        }
        return List.copyOf(result);
    }

    private static List<GenerationReport> parseGenerations(
        JsonNode values,
        List<CandidateEvaluation> evaluations
    ) {
        if (!values.isArray()) {
            throw new IllegalArgumentException(
                "checkpoint generationReports must be an array");
        }
        Map<String, CandidateEvaluation> byHash = new HashMap<>();
        evaluations.forEach(value -> byHash.put(value.contentHash(), value));
        List<GenerationReport> result = new ArrayList<>();
        for (JsonNode value : values) {
            ObjectNode item = object(value, "generation report");
            requireExactFields(item, Set.of(
                "schema",
                "generation",
                "evaluationHashes",
                "selectedCandidateHashes",
                "lineage",
                "rejections",
                "distinctAlphaStructures",
                "cumulativeMutationAttempts",
                "cumulativeTrainEvaluations",
                "outcome",
                "contentHash"), "generation report");
            List<CandidateEvaluation> retained = strings(
                item, "evaluationHashes", "generation report").stream()
                .map(hash -> {
                    CandidateEvaluation evaluation = byHash.get(hash);
                    if (evaluation == null) {
                        throw new IllegalArgumentException(
                            "generation references unknown evaluation: " + hash);
                    }
                    return evaluation;
                })
                .toList();
            result.add(new GenerationReport(
                text(item, "schema", "generation report"),
                integer(item, "generation", "generation report"),
                retained,
                strings(item, "selectedCandidateHashes", "generation report"),
                parseLineage(required(item, "lineage", "generation report")),
                parseRejections(required(item, "rejections", "generation report")),
                integer(item, "distinctAlphaStructures", "generation report"),
                integer(item, "cumulativeMutationAttempts", "generation report"),
                integer(item, "cumulativeTrainEvaluations", "generation report"),
                enumValue(
                    GenerationOutcome.class,
                    text(item, "outcome", "generation report")),
                text(item, "contentHash", "generation report")));
        }
        return List.copyOf(result);
    }

    private static List<LineageEdge> parseLineage(JsonNode values) {
        if (!values.isArray()) {
            throw new IllegalArgumentException("generation lineage must be an array");
        }
        List<LineageEdge> result = new ArrayList<>();
        for (JsonNode value : values) {
            ObjectNode item = object(value, "lineage edge");
            requireExactFields(item, Set.of(
                "parentCandidateHash",
                "childCandidateHash",
                "childPlanHash",
                "childAlphaStructuralHash",
                "mutationKind",
                "proposalKey"), "lineage edge");
            result.add(new LineageEdge(
                text(item, "parentCandidateHash", "lineage edge"),
                text(item, "childCandidateHash", "lineage edge"),
                text(item, "childPlanHash", "lineage edge"),
                text(item, "childAlphaStructuralHash", "lineage edge"),
                enumValue(
                    EvolutionRewriteProgramMutationKind.class,
                    text(item, "mutationKind", "lineage edge")),
                text(item, "proposalKey", "lineage edge")));
        }
        return List.copyOf(result);
    }

    private static List<MutationRejection> parseRejections(JsonNode values) {
        if (!values.isArray()) {
            throw new IllegalArgumentException("generation rejections must be an array");
        }
        List<MutationRejection> result = new ArrayList<>();
        for (JsonNode value : values) {
            ObjectNode item = object(value, "mutation rejection");
            requireExactFields(item, Set.of(
                "parentCandidateHash",
                "mutationKind",
                "proposalKey",
                "childPlanHash",
                "childAlphaStructuralHash",
                "blockers"), "mutation rejection");
            result.add(new MutationRejection(
                text(item, "parentCandidateHash", "mutation rejection"),
                enumValue(
                    EvolutionRewriteProgramMutationKind.class,
                    text(item, "mutationKind", "mutation rejection")),
                text(item, "proposalKey", "mutation rejection"),
                nullableText(item, "childPlanHash", "mutation rejection"),
                nullableText(item, "childAlphaStructuralHash", "mutation rejection"),
                strings(item, "blockers", "mutation rejection")));
        }
        return List.copyOf(result);
    }

    private static CheckpointArtifactManifest parseManifest(String json) {
        try {
            ObjectNode root = object(JSON.readTree(json), "checkpoint artifact manifest");
            requireExactFields(root, Set.of(
                "schema",
                "checkpointHash",
                "stateHash",
                "artifacts",
                "commitProtocol",
                "contentHash"), "checkpoint artifact manifest");
            JsonNode values = required(root, "artifacts", "checkpoint artifact manifest");
            if (!values.isArray()) {
                throw new IllegalArgumentException("manifest artifacts must be an array");
            }
            List<CheckpointArtifact> artifacts = new ArrayList<>();
            for (JsonNode value : values) {
                ObjectNode item = object(value, "checkpoint artifact");
                requireExactFields(item, Set.of(
                    "fileName",
                    "role",
                    "sourceContentHash",
                    "byteHash",
                    "byteLength"), "checkpoint artifact");
                artifacts.add(new CheckpointArtifact(
                    text(item, "fileName", "checkpoint artifact"),
                    enumValue(
                        ArtifactRole.class,
                        text(item, "role", "checkpoint artifact")),
                    text(item, "sourceContentHash", "checkpoint artifact"),
                    text(item, "byteHash", "checkpoint artifact"),
                    longInteger(item, "byteLength", "checkpoint artifact")));
            }
            return new CheckpointArtifactManifest(
                text(root, "schema", "checkpoint artifact manifest"),
                text(root, "checkpointHash", "checkpoint artifact manifest"),
                text(root, "stateHash", "checkpoint artifact manifest"),
                artifacts,
                text(root, "commitProtocol", "checkpoint artifact manifest"),
                text(root, "contentHash", "checkpoint artifact manifest"));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                "Invalid checkpoint artifact manifest JSON", exception);
        }
    }

    private static void requireOnlyKnownEntries(
        Path directory,
        boolean complete
    ) throws IOException {
        Set<String> allowed = Set.of(
            CHECKPOINT_FILE_NAME,
            STATE_FILE_NAME,
            MANIFEST_FILE_NAME);
        Set<String> actual = new HashSet<>();
        try (var entries = Files.list(directory)) {
            entries.forEach(path -> {
                String name = path.getFileName().toString();
                if (!allowed.contains(name)) {
                    throw new IllegalArgumentException(
                        "unexpected checkpoint artifact file: " + name);
                }
                if (Files.isSymbolicLink(path)
                        || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalArgumentException(
                        "checkpoint artifact entry must be a regular non-symlink file: "
                            + name);
                }
                actual.add(name);
            });
        }
        if (complete && !actual.equals(allowed)) {
            Set<String> missing = new HashSet<>(allowed);
            missing.removeAll(actual);
            throw new IllegalArgumentException(
                "checkpoint artifact is incomplete; missing=" + missing);
        }
    }

    private static void requireSafeAncestry(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        if (current == null) {
            throw new IllegalArgumentException(
                "checkpoint artifact path must have a filesystem root");
        }
        for (Path component : absolute) {
            current = current.resolve(component);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException(
                    "checkpoint artifact path must not traverse symbolic links");
            }
        }
    }

    private static String readUtf8Bounded(Path file, long maxBytes) throws IOException {
        if (Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(
                "checkpoint artifact payload must be a regular non-symlink file");
        }
        long size = Files.size(file);
        if (size <= 0L || size > maxBytes) {
            throw new IllegalArgumentException(
                "checkpoint artifact payload size is outside the allowed bound");
        }
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length != size) {
            throw new IllegalArgumentException(
                "checkpoint artifact payload changed while reading");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException(
                "checkpoint artifact payload is not valid UTF-8", exception);
        }
    }

    private static void atomicWrite(Path target, String content) throws IOException {
        Path directory = target.getParent();
        if (directory == null) {
            throw new IOException("checkpoint artifact target requires a parent directory");
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

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return "sha256:" + java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static ObjectNode object(JsonNode value, String context) {
        if (!(value instanceof ObjectNode object)) {
            throw new IllegalArgumentException(context + " must be an object");
        }
        return object;
    }

    private static JsonNode required(
        ObjectNode object,
        String field,
        String context
    ) {
        JsonNode value = object.get(field);
        if (value == null) {
            throw new IllegalArgumentException(context + "." + field + " is required");
        }
        return value;
    }

    private static String text(
        ObjectNode object,
        String field,
        String context
    ) {
        JsonNode value = required(object, field, context);
        if (!value.isTextual()) {
            throw new IllegalArgumentException(context + "." + field + " must be a string");
        }
        return value.textValue();
    }

    private static String nullableText(
        ObjectNode object,
        String field,
        String context
    ) {
        JsonNode value = required(object, field, context);
        if (value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException(
                context + "." + field + " must be a string or null");
        }
        return value.textValue();
    }

    private static int integer(
        ObjectNode object,
        String field,
        String context
    ) {
        JsonNode value = required(object, field, context);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(context + "." + field + " must be an integer");
        }
        return value.intValue();
    }

    private static long longInteger(
        ObjectNode object,
        String field,
        String context
    ) {
        JsonNode value = required(object, field, context);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException(context + "." + field + " must be an integer");
        }
        return value.longValue();
    }

    private static List<String> strings(
        ObjectNode object,
        String field,
        String context
    ) {
        JsonNode value = required(object, field, context);
        if (!value.isArray()) {
            throw new IllegalArgumentException(context + "." + field + " must be an array");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException(
                    context + "." + field + " must contain strings");
            }
            result.add(item.textValue());
        }
        return List.copyOf(result);
    }

    private static void requireExactFields(
        ObjectNode object,
        Set<String> expected,
        String context
    ) {
        Set<String> actual = new LinkedHashSet<>();
        object.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected) && !Set.copyOf(actual).equals(expected)) {
            Set<String> missing = new LinkedHashSet<>(expected);
            missing.removeAll(actual);
            Set<String> unexpected = new LinkedHashSet<>(actual);
            unexpected.removeAll(expected);
            throw new IllegalArgumentException(
                context + " fields differ; missing=" + missing
                    + ", unexpected=" + unexpected);
        }
    }

    private static <E extends Enum<E>> E enumValue(
        Class<E> type,
        String value
    ) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "Unsupported " + type.getSimpleName() + ": " + value,
                exception);
        }
    }

    private static List<String> orderedUniqueHashes(
        List<String> values,
        boolean requireNonEmpty,
        String name
    ) {
        List<String> result = values == null ? List.of() : List.copyOf(values);
        if (requireNonEmpty && result.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        if (new HashSet<>(result).size() != result.size()) {
            throw new IllegalArgumentException(name + " must be unique");
        }
        result.forEach(value -> EvolutionGenome.requireSha256(value, name));
        return result;
    }

    private static List<CheckpointArtifact> normalizeArtifacts(
        List<CheckpointArtifact> values
    ) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(
                "checkpoint artifact manifest requires artifacts");
        }
        List<CheckpointArtifact> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        EnumSet<ArtifactRole> roles = EnumSet.noneOf(ArtifactRole.class);
        for (CheckpointArtifact value : values) {
            CheckpointArtifact artifact = Objects.requireNonNull(
                value, "checkpoint artifact");
            if (!names.add(artifact.fileName())) {
                throw new IllegalArgumentException(
                    "duplicate checkpoint artifact file: " + artifact.fileName());
            }
            if (!roles.add(artifact.role())) {
                throw new IllegalArgumentException(
                    "duplicate checkpoint artifact role: " + artifact.role());
            }
            result.add(artifact);
        }
        if (!roles.equals(EnumSet.allOf(ArtifactRole.class))) {
            throw new IllegalArgumentException(
                "checkpoint artifact manifest must contain every payload role");
        }
        result.sort(Comparator.comparing(CheckpointArtifact::fileName));
        return List.copyOf(result);
    }

    private static String manifestHash(
        String checkpointHash,
        String stateHash,
        List<CheckpointArtifact> artifacts
    ) {
        return EvolutionGenome.hash(
            MANIFEST_SCHEMA
                + "\ncheckpointHash=" + checkpointHash
                + "\nstateHash=" + stateHash
                + "\nartifacts=" + artifacts.stream()
                    .map(CheckpointArtifact::canonicalMaterial).toList()
                + "\ncommitProtocol=" + COMMIT_PROTOCOL);
    }

    public enum ArtifactRole {
        CHECKPOINT(CHECKPOINT_FILE_NAME),
        STATE(STATE_FILE_NAME);

        private final String fileName;

        ArtifactRole(String fileName) {
            this.fileName = fileName;
        }

        public String fileName() {
            return fileName;
        }
    }

    public record CheckpointArtifact(
        String fileName,
        ArtifactRole role,
        String sourceContentHash,
        String byteHash,
        long byteLength
    ) {
        public CheckpointArtifact {
            Objects.requireNonNull(role, "role");
            if (!role.fileName().equals(fileName)
                    || fileName.contains("/")
                    || fileName.contains("\\")
                    || MANIFEST_FILE_NAME.equals(fileName)) {
                throw new IllegalArgumentException(
                    "checkpoint artifact file name must match its canonical role name");
            }
            EvolutionGenome.requireSha256(
                sourceContentHash, "sourceContentHash");
            EvolutionGenome.requireSha256(byteHash, "byteHash");
            if (byteLength <= 0L || byteLength > MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException(
                    "checkpoint artifact byteLength is outside the allowed bound");
            }
        }

        String canonicalMaterial() {
            return fileName + '|' + role.name() + '|' + sourceContentHash
                + '|' + byteHash + '|' + byteLength;
        }
    }

    public record CheckpointArtifactManifest(
        String schema,
        String checkpointHash,
        String stateHash,
        List<CheckpointArtifact> artifacts,
        String commitProtocol,
        String contentHash
    ) {
        public CheckpointArtifactManifest {
            if (!MANIFEST_SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported checkpoint artifact manifest schema");
            }
            EvolutionGenome.requireSha256(checkpointHash, "checkpointHash");
            EvolutionGenome.requireSha256(stateHash, "stateHash");
            artifacts = normalizeArtifacts(artifacts);
            if (!COMMIT_PROTOCOL.equals(commitProtocol)) {
                throw new IllegalArgumentException(
                    "unsupported checkpoint artifact commit protocol");
            }
            EvolutionGenome.requireSha256(contentHash, "contentHash");
            String expected = manifestHash(checkpointHash, stateHash, artifacts);
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "checkpoint artifact manifest contentHash mismatch");
            }
            requireRoleRoot(artifacts, ArtifactRole.CHECKPOINT, checkpointHash);
            requireRoleRoot(artifacts, ArtifactRole.STATE, stateHash);
        }

        static CheckpointArtifactManifest create(
            String checkpointHash,
            String stateHash,
            List<CheckpointArtifact> artifacts
        ) {
            List<CheckpointArtifact> normalized = normalizeArtifacts(artifacts);
            return new CheckpointArtifactManifest(
                MANIFEST_SCHEMA,
                checkpointHash,
                stateHash,
                normalized,
                COMMIT_PROTOCOL,
                manifestHash(checkpointHash, stateHash, normalized));
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("checkpointHash", checkpointHash)
                .property("stateHash", stateHash)
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

    private static void requireRoleRoot(
        List<CheckpointArtifact> artifacts,
        ArtifactRole role,
        String expectedRoot
    ) {
        CheckpointArtifact artifact = artifacts.stream()
            .filter(value -> value.role() == role)
            .findFirst()
            .orElseThrow();
        if (!artifact.sourceContentHash().equals(expectedRoot)) {
            throw new IllegalArgumentException(
                "checkpoint artifact role root mismatch for " + role);
        }
    }

    public record LoadedCheckpoint(
        PopulationCheckpoint checkpoint,
        CheckpointArtifactManifest manifest
    ) {
        public LoadedCheckpoint {
            Objects.requireNonNull(checkpoint, "checkpoint");
            Objects.requireNonNull(manifest, "manifest");
            if (!checkpoint.contentHash().equals(manifest.checkpointHash())) {
                throw new IllegalArgumentException(
                    "loaded checkpoint does not match artifact manifest");
            }
        }
    }

    private record Payload(CheckpointArtifact artifact, String content) {
    }

    private record CheckpointHeader(
        String schema,
        String studyPlanHash,
        String trainSuiteHash,
        String mutationCatalogHash,
        List<String> seedCandidateHashes,
        int completedGeneration,
        List<String> populationCandidateHashes,
        List<String> evaluationHashes,
        List<String> generationReportHashes,
        int mutationAttempts,
        int trainEvaluations,
        String validationStatus,
        String finalTestStatus,
        String contentHash
    ) {
        private CheckpointHeader {
            if (!EvolutionRewriteProgramPopulationEngine.CHECKPOINT_SCHEMA.equals(schema)) {
                throw new IllegalArgumentException("unsupported checkpoint schema");
            }
            EvolutionGenome.requireSha256(studyPlanHash, "studyPlanHash");
            EvolutionGenome.requireSha256(trainSuiteHash, "trainSuiteHash");
            EvolutionGenome.requireSha256(mutationCatalogHash, "mutationCatalogHash");
            seedCandidateHashes = orderedUniqueHashes(
                seedCandidateHashes, true, "seedCandidateHashes");
            populationCandidateHashes = orderedUniqueHashes(
                populationCandidateHashes, true, "populationCandidateHashes");
            evaluationHashes = orderedUniqueHashes(
                evaluationHashes, true, "evaluationHashes");
            generationReportHashes = orderedUniqueHashes(
                generationReportHashes, true, "generationReportHashes");
            if (completedGeneration < 1 || mutationAttempts < 0 || trainEvaluations < 0) {
                throw new IllegalArgumentException("invalid checkpoint counters");
            }
            if (!"NOT_EVALUATED".equals(validationStatus)
                    || !"NOT_EVALUATED".equals(finalTestStatus)) {
                throw new IllegalArgumentException(
                    "checkpoint artifact cannot contain later-stage outcomes");
            }
            EvolutionGenome.requireSha256(contentHash, "contentHash");
        }
    }

    private record PopulationCandidate(
        String candidateHash,
        String alphaStructuralHash,
        String genomeJson,
        String planJson
    ) {
        private PopulationCandidate {
            EvolutionGenome.requireSha256(candidateHash, "candidateHash");
            EvolutionGenome.requireSha256(
                alphaStructuralHash, "alphaStructuralHash");
            if (genomeJson == null || genomeJson.isBlank()
                    || planJson == null || planJson.isBlank()) {
                throw new IllegalArgumentException(
                    "checkpoint candidate requires genome and plan JSON");
            }
            EvolutionGenome genome = GENOME_CODEC.read(genomeJson);
            EvolutionRewriteProgramPlan plan = PLAN_CODEC.read(planJson);
            if (!GENOME_CODEC.write(genome).equals(genomeJson)
                    || !PLAN_CODEC.write(plan).equals(planJson)) {
                throw new IllegalArgumentException(
                    "checkpoint candidate genome/plan JSON is not canonical");
            }
            EvolutionRewriteProgramCandidate reconstructed =
                EvolutionRewriteProgramCandidate.create(genome, plan);
            if (!candidateHash.equals(reconstructed.contentHash())
                    || !alphaStructuralHash.equals(
                        reconstructed.alphaStructuralHash())) {
                throw new IllegalArgumentException(
                    "checkpoint candidate identities do not match genome/plan payloads");
            }
        }

        static PopulationCandidate from(EvolutionRewriteProgramCandidate candidate) {
            return new PopulationCandidate(
                candidate.contentHash(),
                candidate.alphaStructuralHash(),
                GENOME_CODEC.write(candidate.genome()),
                PLAN_CODEC.write(candidate.plan()));
        }

        EvolutionRewriteProgramCandidate candidate() {
            return EvolutionRewriteProgramCandidate.create(
                GENOME_CODEC.read(genomeJson),
                PLAN_CODEC.read(planJson));
        }
    }

    private record CheckpointState(
        String schema,
        String checkpointHash,
        List<PopulationCandidate> candidates,
        List<CandidateEvaluation> evaluations,
        List<GenerationReport> generationReports,
        String contentHash
    ) {
        private CheckpointState {
            if (!STATE_SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported checkpoint state schema");
            }
            EvolutionGenome.requireSha256(checkpointHash, "checkpointHash");
            candidates = candidates == null ? List.of() : candidates.stream()
                .map(value -> Objects.requireNonNull(value, "checkpoint candidate"))
                .sorted(Comparator.comparing(PopulationCandidate::candidateHash))
                .toList();
            evaluations = evaluations == null ? List.of() : evaluations.stream()
                .map(value -> Objects.requireNonNull(value, "candidate evaluation"))
                .sorted(Comparator.comparing(CandidateEvaluation::candidateHash))
                .toList();
            generationReports = generationReports == null
                ? List.of() : List.copyOf(generationReports);
            if (candidates.isEmpty()
                    || evaluations.isEmpty()
                    || generationReports.isEmpty()) {
                throw new IllegalArgumentException(
                    "checkpoint state requires candidates, evaluations and generations");
            }
            if (new HashSet<>(candidates.stream()
                    .map(PopulationCandidate::candidateHash).toList()).size()
                    != candidates.size()) {
                throw new IllegalArgumentException(
                    "checkpoint state candidates must be unique");
            }
            if (new HashSet<>(evaluations.stream()
                    .map(CandidateEvaluation::candidateHash).toList()).size()
                    != evaluations.size()) {
                throw new IllegalArgumentException(
                    "checkpoint state evaluations must be unique");
            }
            for (int index = 0; index < generationReports.size(); index++) {
                if (generationReports.get(index).generation() != index + 1) {
                    throw new IllegalArgumentException(
                        "checkpoint generations must be complete and ordered");
                }
            }
            EvolutionGenome.requireSha256(contentHash, "contentHash");
            String expected = EvolutionGenome.hash(render(
                checkpointHash,
                candidates,
                evaluations,
                generationReports,
                null));
            if (!expected.equals(contentHash)) {
                throw new IllegalArgumentException(
                    "checkpoint state contentHash mismatch");
            }
        }

        static CheckpointState create(PopulationCheckpoint checkpoint) {
            List<PopulationCandidate> candidates = checkpoint.population().stream()
                .map(PopulationCandidate::from)
                .toList();
            String hash = EvolutionGenome.hash(render(
                checkpoint.contentHash(),
                candidates,
                checkpoint.evaluations(),
                checkpoint.generationReports(),
                null));
            return new CheckpointState(
                STATE_SCHEMA,
                checkpoint.contentHash(),
                candidates,
                checkpoint.evaluations(),
                checkpoint.generationReports(),
                hash);
        }

        String toCanonicalJson() {
            return render(
                checkpointHash,
                candidates,
                evaluations,
                generationReports,
                contentHash);
        }

        private static String render(
            String checkpointHash,
            List<PopulationCandidate> candidates,
            List<CandidateEvaluation> evaluations,
            List<GenerationReport> reports,
            String contentHash
        ) {
            JsonWriter json = new JsonWriter().beginObject()
                .property("schema", STATE_SCHEMA)
                .property("checkpointHash", checkpointHash)
                .array("candidates", array -> candidates.forEach(candidate ->
                    array.objectValue(object -> object
                        .property("candidateHash", candidate.candidateHash())
                        .property("alphaStructuralHash", candidate.alphaStructuralHash())
                        .property("genomeJson", candidate.genomeJson())
                        .property("planJson", candidate.planJson()))))
                .array("evaluations", array -> evaluations.forEach(evaluation ->
                    array.objectValue(object -> {
                        object.property("candidateHash", evaluation.candidateHash())
                            .property("alphaStructuralHash", evaluation.alphaStructuralHash())
                            .object("rawComponents", components -> {
                                for (FitnessComponent component : FitnessComponent.values()) {
                                    Integer value = evaluation.rawComponents().get(component);
                                    if (value != null) {
                                        components.property(component.name(), value);
                                    }
                                }
                            })
                            .stringArray("blockers", evaluation.blockers())
                            .property("scalarFitness", evaluation.scalarFitness());
                        if (evaluation.evidenceHash() == null) {
                            object.nullProperty("evidenceHash");
                        } else {
                            object.property("evidenceHash", evaluation.evidenceHash());
                        }
                        object.property("contentHash", evaluation.contentHash());
                    })))
                .array("generationReports", array -> reports.forEach(report ->
                    array.objectValue(object -> {
                        object.property("schema", report.schema())
                            .property("generation", report.generation())
                            .stringArray("evaluationHashes", report.evaluations().stream()
                                .map(CandidateEvaluation::contentHash).toList())
                            .stringArray(
                                "selectedCandidateHashes",
                                report.selectedCandidateHashes())
                            .array("lineage", lineage -> report.lineage().forEach(edge ->
                                lineage.objectValue(item -> item
                                    .property("parentCandidateHash", edge.parentCandidateHash())
                                    .property("childCandidateHash", edge.childCandidateHash())
                                    .property("childPlanHash", edge.childPlanHash())
                                    .property(
                                        "childAlphaStructuralHash",
                                        edge.childAlphaStructuralHash())
                                    .property("mutationKind", edge.mutationKind().name())
                                    .property("proposalKey", edge.proposalKey()))))
                            .array("rejections", rejections ->
                                report.rejections().forEach(rejection ->
                                    rejections.objectValue(item -> {
                                        item.property(
                                                "parentCandidateHash",
                                                rejection.parentCandidateHash())
                                            .property(
                                                "mutationKind",
                                                rejection.mutationKind().name())
                                            .property("proposalKey", rejection.proposalKey());
                                        if (rejection.childPlanHash() == null) {
                                            item.nullProperty("childPlanHash");
                                        } else {
                                            item.property(
                                                "childPlanHash",
                                                rejection.childPlanHash());
                                        }
                                        if (rejection.childAlphaStructuralHash() == null) {
                                            item.nullProperty("childAlphaStructuralHash");
                                        } else {
                                            item.property(
                                                "childAlphaStructuralHash",
                                                rejection.childAlphaStructuralHash());
                                        }
                                        item.stringArray("blockers", rejection.blockers());
                                    })))
                            .property(
                                "distinctAlphaStructures",
                                report.distinctAlphaStructures())
                            .property(
                                "cumulativeMutationAttempts",
                                report.cumulativeMutationAttempts())
                            .property(
                                "cumulativeTrainEvaluations",
                                report.cumulativeTrainEvaluations())
                            .property("outcome", report.outcome().name())
                            .property("contentHash", report.contentHash());
                    })));
            if (contentHash != null) {
                json.property("contentHash", contentHash);
            }
            return json.endObject().toString();
        }
    }
}
