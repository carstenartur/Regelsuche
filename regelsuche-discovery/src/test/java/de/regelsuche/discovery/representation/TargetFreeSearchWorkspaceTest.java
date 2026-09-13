package de.regelsuche.discovery.representation;

import static org.junit.jupiter.api.Assertions.*;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryArtifactReference.ArtifactRole.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalStatus;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TargetFreeSearchWorkspaceTest {
    static final String REVISION = "0123456789abcdef0123456789abcdef01234567";
    @TempDir Path temporary;
    final JsonMapper json = new JsonMapper();

    @ParameterizedTest
    @CsvSource({"x+0, x + 0", "((x + 0) * (x + 0)), (x + 0) * (x + 0)"})
    void normalizesValidInputBeforeExecutingTheWorkspaceBoundSearch(String source, String canonicalSource) throws Exception {
        var budget = new SearchHeuristic(3, 12, 1, 1, 8, 8);
        var result = TargetFreeRepresentationDiscoveryRun.writeTargetFree(temporary.resolve("runs"), source, budget, REVISION);
        var canonicalResult = TargetFreeRepresentationDiscoveryRun.runTargetFree(canonicalSource, budget, REVISION);
        assertEquals(canonicalSource, result.workspace().input().displayText());
        assertEquals(canonicalSource, result.searchResult().states().getFirst().expression());
        assertEquals(canonicalResult.artifact(), result.artifact());
        var retained = TargetFreeSearchArtifactStore.read(temporary.resolve("runs"), result.workspace()).orElseThrow();
        assertEquals(result.artifact(), TargetFreeSearchExecution.fromCanonicalBytes(result.workspace(),
            retained.getBytes(java.nio.charset.StandardCharsets.UTF_8)).replay().artifact());
    }

    @Test void persistsTheRealNativeResultAndAllSourceBoundPathsInTheExistingWorkspace() throws Exception {
        var budget = new SearchHeuristic(3, 12, 1, 1, 8, 8);
        var result = TargetFreeRepresentationDiscoveryRun.writeTargetFree(temporary.resolve("runs"), "(x + 0) * (x + 0)", budget, REVISION);
        assertEquals(GoalStatus.UNTARGETED, result.searchResult().status());
        var content = json.readTree(result.artifact().toCanonicalJson()).path("content");
        assertEquals(result.searchResult().states().size(), content.path("states").size());
        assertEquals(result.searchResult().metrics().generatedTransformations(), content.path("metrics").path("generatedTransformations").asInt());
        assertEquals("UNTARGETED", content.path("goalStatus").asText());
        assertEquals(-1, content.path("bestDistance").asInt());
        assertTrue(content.path("events").size() > 2);
        for (var role : List.of(SEARCH_GRAPH, PROGRESS_LEDGER, CANDIDATE_DOSSIERS, PATH_REPLAY)) {
            assertEquals(result.artifact().contentHash(), result.workspace().requireArtifact(role, TargetFreeSearchExecution.SCHEMA).targetContentHash());
        }
        assertEquals(result.workspace(), RepresentationDiscoveryRunWorkspace.findRetained(temporary.resolve("runs"), result.workspace().runId()).orElseThrow());
        assertEquals(result.artifact().toCanonicalJson(), Files.readString(temporary.resolve("runs-dossiers").resolve(result.workspace().runId().substring(7) + ".json")));
        for (var state : content.path("states")) {
            var nativeState = result.searchResult().states().get(state.path("sequence").asInt());
            var retainedState = json.readTree(state.path("canonicalStateJson").asText());
            assertEquals(nativeState.appliedRuleApplications().stream().sorted().toList(), json.convertValue(retainedState.path("applicationKeys"), List.class));
            assertEquals(nativeState.depth(), state.path("generationSequences").size());
        }
        assertEquals(content.path("states").size() - 1, content.path("transitions").size());
        assertEquals(result.artifact(), result.artifact().replay().artifact());
    }

    @Test void retainsUntargetedStatusAtAStateLimitAndDoesNotClaimCompleteClosure() throws Exception {
        var result = TargetFreeRepresentationDiscoveryRun.runTargetFree("x + 0", new SearchHeuristic(3, 1, 1, 1, 8, 8), REVISION);
        var content = json.readTree(result.artifact().toCanonicalJson()).path("content");
        assertEquals("UNTARGETED", content.path("goalStatus").asText());
        assertTrue(content.path("events").get(content.path("events").size() - 1).path("frontierSize").asInt() > 0);
        assertFalse(result.workspace().outcome().terminalReason().contains("FRONTIER_EXHAUSTED"));
        assertTrue(result.workspace().artifacts().stream().filter(a -> a.role() == PROOF_OBLIGATIONS).allMatch(a -> a.status().name().equals("NOT_PRODUCED")));
    }

    @Test void refusesAlteredOrForeignObservationInsteadOfPromotingItToExecutionAuthority() throws Exception {
        var budget = new SearchHeuristic(2, 8, 1, 1, 8, 8);
        var first = TargetFreeRepresentationDiscoveryRun.runTargetFree("x + 0", budget, REVISION);
        var other = TargetFreeRepresentationDiscoveryRun.runTargetFree("y + 0", budget, REVISION);
        assertThrows(IllegalArgumentException.class, () -> TargetFreeSearchExecution.fromCanonicalBytes(first.workspace(), other.artifact().toCanonicalJson().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> TargetFreeSearchExecution.fromCanonicalBytes(first.workspace(), first.artifact().toCanonicalJson().replace("UNTARGETED", "REACHED").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test void replayObtainsAFreshNativeResultAndRejectsEvenRehashedFalseTelemetry() throws Exception {
        var original = TargetFreeRepresentationDiscoveryRun.runTargetFree("x + 0", new SearchHeuristic(2, 8, 1, 1, 8, 8), REVISION);
        assertEquals(original.artifact(), original.artifact().replay().artifact());
        var mapper = JsonMapper.builder().enable(com.fasterxml.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
        var changed = mapper.valueToTree(original.artifact().content());
        var events = changed.path("events");
        ((com.fasterxml.jackson.databind.node.ObjectNode) events.get(events.size() - 1)).put("pruningReason", "invented-terminal-evidence");
        var content = mapper.treeToValue(changed, TargetFreeSearchExecution.Content.class);
        String hash = "sha256:" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(content)));
        var forged = new TargetFreeSearchExecution.Artifact(content, hash);
        assertThrows(IllegalArgumentException.class, forged::replay);
    }
}
