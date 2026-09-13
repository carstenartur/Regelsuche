package de.regelsuche.web;

import static de.regelsuche.discovery.representation.RepresentationDiscoveryArtifactReference.ArtifactRole.CANDIDATE_DOSSIERS;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.regelsuche.discovery.representation.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** One bounded retained #663 artifact per immutable workspace. No live search. */
final class RetainedCandidateDossierRepository {
    static final int MAX_BYTES = 1_048_576;
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private RetainedCandidateDossierRepository() { }

    static TargetFreeSymPyBridgeDiscoveryScenario.ScenarioArtifact decode(
        RepresentationDiscoveryRunWorkspace run, byte[] bytes
    ) {
        try {
            if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("dossier exceeds 1 MiB");
            var artifact = JSON.readValue(bytes, TargetFreeSymPyBridgeDiscoveryScenario.ScenarioArtifact.class);
            if (artifact == null) throw new IllegalArgumentException("candidate dossier is missing");
            byte[] canonical = artifact.toCanonicalJson().getBytes(StandardCharsets.UTF_8);
            byte[] withNewline = (artifact.toCanonicalJson() + "\n").getBytes(StandardCharsets.UTF_8);
            if (!Arrays.equals(bytes, canonical) && !Arrays.equals(bytes, withNewline)) {
                throw new IllegalArgumentException("dossier must contain canonical UTF-8 JSON");
            }
            artifact.validateRetainedContract();
            var content = artifact.content();
            String searchHash = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(JSON.writeValueAsBytes(content.search())));
            if (!searchHash.equals(content.searchContentHash())
                || !run.plan().informationTrack().id().equals(content.informationTrack())
                || !run.input().assumptions().equals(content.search().states().getFirst().assumptions())) {
                throw new IllegalArgumentException("dossier search or information boundary differs from run");
            }
            new TargetFreeRepresentationDiscoveryRun.RunBundle(artifact, run);
            var bridge = content.discoveredBridge();
            var state = content.search().state(bridge.stateHash());
            if (state.depth() == 0 || !state.expression().equals(bridge.expression()) || state.depth() != bridge.depth()
                || !state.pathRuleIds().equals(bridge.pathRuleIds()) || !state.primitiveRuleIds().equals(bridge.primitiveRuleIds())
                || !state.assumptions().equals(bridge.assumptions()) || !state.packIds().equals(bridge.packIds())) {
                throw new IllegalArgumentException("dossier bridge is not bound to its retained candidate");
            }
            return artifact;
        } catch (IOException | IllegalStateException | NoSuchAlgorithmException exception) {
            throw new IllegalArgumentException("invalid or unbound candidate dossier", exception);
        }
    }

    static synchronized Optional<String> read(Path runs, RepresentationDiscoveryRunWorkspace run) throws IOException {
        Path target = target(runs, run);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        requireRegular(target);
        if (Files.size(target) > MAX_BYTES) throw new IllegalArgumentException("retained dossier exceeds 1 MiB");
        try (var input = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.of(decode(run, input.readNBytes(MAX_BYTES + 1)).toCanonicalJson());
        }
    }

    static synchronized String retain(Path runs, RepresentationDiscoveryRunWorkspace run, byte[] bytes) throws IOException {
        String canonical = decode(run, bytes).toCanonicalJson();
        Path target = target(runs, run), root = target.getParent();
        Files.createDirectories(root);
        rejectSymbolicComponents(root);
        var existing = read(runs, run);
        if (existing.isPresent()) {
            if (!existing.get().equals(canonical)) throw new IllegalStateException("immutable dossier conflict");
            return canonical;
        }
        try (var entries = Files.list(root)) {
            if (entries.limit(RepresentationDiscoveryRunWorkspace.DEFAULT_MAX_RETAINED_RUNS).count()
                >= RepresentationDiscoveryRunWorkspace.DEFAULT_MAX_RETAINED_RUNS) {
                throw new IllegalStateException("retained dossier repository limit exceeded");
            }
        }
        Path temporary = Files.createTempFile(root, ".candidate-dossier-", ".tmp");
        try {
            Files.writeString(temporary, canonical, StandardCharsets.UTF_8);
            try { Files.createLink(target, temporary); }
            catch (FileAlreadyExistsException exception) {
                if (!read(runs, run).orElseThrow().equals(canonical)) throw new IllegalStateException("immutable dossier conflict", exception);
            }
        } finally { Files.deleteIfExists(temporary); }
        return canonical;
    }

    private static Path target(Path runs, RepresentationDiscoveryRunWorkspace run) {
        run.requireArtifact(CANDIDATE_DOSSIERS, TargetFreeSymPyBridgeDiscoveryScenario.SCHEMA);
        Path absolute = runs.toAbsolutePath().normalize();
        Path root = absolute.resolveSibling(absolute.getFileName() + "-dossiers");
        rejectSymbolicComponents(root);
        return root.resolve(run.runId().substring(7) + ".json");
    }

    private static void rejectSymbolicComponents(Path absolute) {
        Path current = absolute.getRoot();
        for (Path component : absolute) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) throw new IllegalStateException("symbolic dossier repository path");
        }
    }

    private static void requireRegular(Path path) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("dossier is not a regular file");
    }
}
