package de.regelsuche.discovery.representation;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.scoring.ScoreRevision;
import de.regelsuche.search.SearchHeuristic;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Admission and historical observation tests use actual native artifacts and the real store. */
class NativeScoringProvenanceTest {
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    @TempDir Path directory;

    @Test void nativeEventsRetainTheirActualScoreProducer() throws Exception {
        var result = run();
        var events = JSON.readTree(result.artifact().toCanonicalJson()).path("content").path("events");
        assertTrue(events.size() > 2);
        events.forEach(event -> assertEquals(ScoreRevision.CURRENT, event.path("scoringRevision").asText()));
        assertEquals(result.artifact(), result.artifact().replay().artifact());
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "null", "3", "true", "[]", "{}", "\"obsolete/v1\""})
    void malformedCurrentStateRevisionIsRejectedDuringArtifactAdmission(String revision) throws Exception {
        var original = run();
        assertThrows(IllegalArgumentException.class, () -> changed(original, state -> {
            if ("absent".equals(revision)) state.remove("scoringRevision");
            else {
                try { state.set("scoringRevision", JSON.readTree(revision)); }
                catch (java.io.IOException exception) { throw new AssertionError(exception); }
            }
        }, event -> { }));
    }

    @Test void historicalNativeBytesRemainReadableButCannotStartExecutableReplay() throws Exception {
        var historical = assertDoesNotThrow(() -> changed(run(), state -> {
            state.put("schema", "regelsuche.search-state-replay/v1");
            state.remove("scoringRevision");
        }, event -> event.remove("scoringRevision")));
        // Use the production workspace binder to construct the fixture, not a second implementation of its hashes.
        var bind = TargetFreeSearchExecution.class.getDeclaredMethod("workspace", TargetFreeSearchExecution.Artifact.class);
        bind.setAccessible(true);
        var workspace = (RepresentationDiscoveryRunWorkspace) bind.invoke(null, historical);
        byte[] bytes = historical.toCanonicalJson().getBytes(StandardCharsets.UTF_8);
        var decoded = TargetFreeSearchExecution.fromCanonicalBytes(workspace, bytes);
        assertArrayEquals(bytes, decoded.toCanonicalJson().getBytes(StandardCharsets.UTF_8));
        assertEquals(historical.toCanonicalJson(), TargetFreeSearchArtifactStore.retain(directory.resolve("runs"), workspace, bytes));
        assertEquals(historical.toCanonicalJson(), TargetFreeSearchArtifactStore.read(directory.resolve("runs"), workspace).orElseThrow());
        var failure = assertThrows(IllegalArgumentException.class, decoded::replay);
        assertTrue(failure.getMessage().contains("scoring"), failure.getMessage());
        assertFalse(decoded.toCanonicalJson().contains("\"scoringRevision\""));
    }

    private static TargetFreeSearchExecution.RunResult run() {
        return TargetFreeRepresentationDiscoveryRun.runTargetFree("x + 0",
            new SearchHeuristic(2, 8, 1, 1, 8, 8), "0123456789abcdef0123456789abcdef01234567");
    }

    /** Rebind all changed hashes so rejection cannot merely be an outer-hash mismatch. */
    private static TargetFreeSearchExecution.Artifact changed(TargetFreeSearchExecution.RunResult original,
            Consumer<ObjectNode> editState, Consumer<ObjectNode> editEvent) throws Exception {
        ObjectNode content = JSON.valueToTree(original.artifact().content());
        var ids = new HashMap<String, String>();
        for (var item : content.path("states")) {
            var state = (ObjectNode) item;
            var value = (ObjectNode) JSON.readTree(state.path("canonicalStateJson").asText());
            editState.accept(value);
            String observation = JSON.writeValueAsString(value);
            String id = hash(observation.getBytes(StandardCharsets.UTF_8));
            ids.put(state.path("stateId").asText(), id);
            state.put("stateId", id);
            state.put("canonicalStateJson", observation);
        }
        content.put("bestStateId", ids.get(content.path("bestStateId").asText()));
        for (var item : content.path("transitions")) {
            var edge = (ObjectNode) item;
            edge.put("fromStateId", ids.get(edge.path("fromStateId").asText()));
            edge.put("toStateId", ids.get(edge.path("toStateId").asText()));
        }
        content.path("events").forEach(event -> editEvent.accept((ObjectNode) event));
        var value = JSON.treeToValue(content, TargetFreeSearchExecution.Content.class);
        return new TargetFreeSearchExecution.Artifact(value, hash(JSON.writeValueAsBytes(value)));
    }

    private static String hash(byte[] bytes) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
