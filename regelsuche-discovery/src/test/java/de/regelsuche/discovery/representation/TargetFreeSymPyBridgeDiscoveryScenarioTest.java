package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.WARNING_KNOWN_FORM_WITHOUT_NEW_CAPABILITY;
import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.WARNING_KNOWN_STRUCTURE_EVIDENCE_BELOW_MINIMUM;
import static de.regelsuche.discovery.representation.TargetFreeSymPyBridgeDiscoveryScenario.CLAIM_BOUNDARY;
import static de.regelsuche.discovery.representation.TargetFreeSymPyBridgeDiscoveryScenario.CONSEQUENCE_ID;
import static de.regelsuche.discovery.representation.TargetFreeSymPyBridgeDiscoveryScenario.FOLLOW_ON_EXPRESSION;
import static de.regelsuche.discovery.representation.TargetFreeSymPyBridgeDiscoveryScenario.PACK_ID;
import static de.regelsuche.discovery.representation.TargetFreeSymPyBridgeDiscoveryScenario.SCHEMA;
import static de.regelsuche.discovery.representation.TargetFreeSymPyBridgeDiscoveryScenario.STRUCTURE_ID;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TargetFreeSymPyBridgeDiscoveryScenarioTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void postFreezeKnowledgeFindsAndExecutesAnUnguidedBridge()
            throws Exception {
        var artifact = TargetFreeSymPyBridgeDiscoveryScenario.run();
        var content = artifact.content();
        var bridge = content.discoveredBridge();
        var classification = content.classification();
        var followOn = content.followOnExecution();

        assertEquals(SCHEMA, content.schema());
        assertEquals("R2", content.informationTrack());
        assertEquals(CLAIM_BOUNDARY, content.claimBoundary());
        assertTrue(content.search().candidateStates().size() > 1);
        assertTrue(content.search().states().stream()
            .anyMatch(state -> state.stateHash().equals(
                bridge.stateHash())));
        assertTrue(content.search().paretoStateHashes().stream()
            .allMatch(hash -> content.search().states().stream()
                .anyMatch(state -> state.stateHash().equals(hash))));
        assertFalse(bridge.pathRuleIds().contains(
            CONSEQUENCE_ID.substring("rule:".length())));
        assertFalse(bridge.packIds().contains(PACK_ID));
        assertEquals(STRUCTURE_ID, bridge.structureId());
        assertEquals(CONSEQUENCE_ID, bridge.consequenceId());
        assertEquals("SymPy", bridge.sourceProject());
        assertEquals("BSD-3-Clause", bridge.license());
        assertEquals("SYMBOLICALLY_VERIFIED",
            bridge.candidateProofStatus());

        assertEquals(0, classification.disabledMatches());
        assertEquals(1, classification.provisionalMatches());
        assertTrue(classification.provisionalUnlocks().isEmpty());
        assertTrue(classification.provisionalWarnings().contains(
            WARNING_KNOWN_STRUCTURE_EVIDENCE_BELOW_MINIMUM));
        assertFalse(classification.provisionalWarnings().contains(
            WARNING_KNOWN_FORM_WITHOUT_NEW_CAPABILITY));
        assertEquals(1, classification.verifiedMatches());
        assertEquals(List.of(CONSEQUENCE_ID),
            classification.verifiedUnlocks());

        assertFalse(followOn.formationTargetRulePresent());
        assertFalse(followOn.disabledTargetRulePresent());
        assertTrue(followOn.enabledTargetRulePresent());
        assertTrue(followOn.formationTargetSuccessors().isEmpty());
        assertTrue(followOn.disabledTargetSuccessors().isEmpty());
        assertEquals(List.of(FOLLOW_ON_EXPRESSION),
            followOn.enabledTargetSuccessors());
        assertNotEquals(
            followOn.disabledRuleInventoryHash(),
            followOn.enabledRuleInventoryHash()
        );
        assertEquals(
            KnownStructureCatalog.sha256(canonical(content)),
            artifact.contentHash()
        );
    }

    @Test
    void retainedArtifactIsByteStableAndTamperEvident(
        @TempDir Path temporary
    ) throws Exception {
        String first = TargetFreeSymPyBridgeDiscoveryScenario
            .run().toCanonicalJson();
        String second = TargetFreeSymPyBridgeDiscoveryScenario
            .run().toCanonicalJson();
        assertEquals(first, second);

        Path retained = Path.of(
            "build/reports/representation-discovery/"
                + "target-free-sympy-bridge.json");
        Path copy = temporary.resolve(
            "target-free-sympy-bridge.json");
        var artifact =
            TargetFreeSymPyBridgeDiscoveryScenario.write(retained);
        TargetFreeSymPyBridgeDiscoveryScenario.write(copy);
        byte[] retainedBytes = Files.readAllBytes(retained);
        assertArrayEquals(retainedBytes, Files.readAllBytes(copy));
        assertEquals('\n', retainedBytes[retainedBytes.length - 1]);
        assertFalse(Files.readString(retained).contains("\r\n"));

        JsonNode root = JSON.readTree(Files.readString(retained));
        assertEquals(SCHEMA,
            root.path("content").path("schema").asText());
        assertEquals(artifact.contentHash(),
            root.path("contentHash").asText());
        assertThrows(
            IllegalArgumentException.class,
            () -> new TargetFreeSymPyBridgeDiscoveryScenario
                .ScenarioArtifact(
                    artifact.content(),
                    "sha256:" + "0".repeat(64)
                )
        );
    }

    private static String canonical(
        TargetFreeSymPyBridgeDiscoveryScenario.ScenarioContent content
    ) throws Exception {
        return com.fasterxml.jackson.databind.json.JsonMapper.builder()
            .enable(com.fasterxml.jackson.databind.MapperFeature
                .SORT_PROPERTIES_ALPHABETICALLY)
            .enable(com.fasterxml.jackson.databind.SerializationFeature
                .ORDER_MAP_ENTRIES_BY_KEYS)
            .build()
            .writeValueAsString(content);
    }
}
