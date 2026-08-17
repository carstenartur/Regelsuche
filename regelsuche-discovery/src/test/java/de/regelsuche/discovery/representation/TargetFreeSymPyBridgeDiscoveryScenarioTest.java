package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.WARNING_KNOWN_FORM_WITHOUT_NEW_CAPABILITY;
import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.WARNING_KNOWN_STRUCTURE_EVIDENCE_BELOW_MINIMUM;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryArtifactReference.ArtifactRole.CANDIDATE_DOSSIERS;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryArtifactReference.ArtifactRole.PROGRESS_LEDGER;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryArtifactReference.ArtifactRole.REPRESENTATION_CANDIDATES;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryArtifactReference.ArtifactRole.SEARCH_GRAPH;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunOutcome.TerminalState.COMPLETED;
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
    private static final String REPOSITORY_REVISION =
        "0123456789abcdef0123456789abcdef01234567";

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

    @Test
    void bindsTheScenarioToOneImmutableRunWorkspace(
        @TempDir Path temporary
    ) throws Exception {
        var bundle = TargetFreeRepresentationDiscoveryRun.write(
            temporary,
            REPOSITORY_REVISION
        );
        var scenario = bundle.scenario();
        var search = scenario.content().search();
        var workspace = bundle.workspace();

        assertEquals(COMPLETED, workspace.outcome().state());
        assertEquals(
            REPOSITORY_REVISION,
            workspace.revisions().repositoryCommit()
        );
        assertEquals(
            TargetFreeRepresentationDiscoveryRun.SCHEMA,
            workspace.revisions().applicationRevision()
        );
        assertEquals(
            RepresentationDiscoveryInformationBoundary.Track
                .R2_CATALOG_BLIND_POST_HOC_BRIDGE,
            workspace.plan().informationTrack()
        );
        assertEquals(
            search.ruleInventoryHash(),
            workspace.plan().ruleInventoryHash()
        );
        assertEquals(
            KnownStructureCatalog.sha256(
                RepresentationDiscoveryRunWorkspace.SCHEMA
                    + "/budget/" + canonical(search.budget())
            ),
            workspace.plan().budgetHash()
        );
        assertEquals(
            KnownStructureCatalog.sha256(
                RepresentationDiscoveryRunWorkspace.SCHEMA
                    + "/runtime/NOT_EVALUATED"
            ),
            workspace.outcome().runtimeDiagnosticsHash()
        );
        assertEquals(
            (long) search.budget().maxGeneratedTransitions(),
            workspace.outcome().configuredWork()
        );
        assertEquals(
            (long) search.generatedTransitionCount(),
            workspace.outcome().consumedWork()
        );
        assertEquals(
            scenario.content().searchContentHash(),
            workspace.outcome().canonicalWorkLedgerHash()
        );
        assertEquals(
            scenario.content().searchContentHash(),
            workspace.requireArtifact(
                SEARCH_GRAPH,
                TargetFreeRepresentationSearch.SCHEMA
            ).targetContentHash()
        );
        assertEquals(
            scenario.content().searchContentHash(),
            workspace.requireArtifact(
                REPRESENTATION_CANDIDATES,
                TargetFreeRepresentationSearch.SCHEMA
            ).targetContentHash()
        );
        assertEquals(
            scenario.contentHash(),
            workspace.requireArtifact(
                CANDIDATE_DOSSIERS,
                TargetFreeSymPyBridgeDiscoveryScenario.SCHEMA
            ).targetContentHash()
        );
        assertEquals(
            scenario.content().searchContentHash(),
            workspace.requireArtifact(
                PROGRESS_LEDGER,
                TargetFreeRepresentationSearch.SCHEMA
            ).targetContentHash()
        );

        Path scenarioPath = temporary.resolve(
            TargetFreeRepresentationDiscoveryRun.SCENARIO_FILE_NAME
        );
        Path workspacePath = temporary.resolve(
            TargetFreeRepresentationDiscoveryRun.WORKSPACE_FILE_NAME
        );
        assertTrue(Files.readString(scenarioPath).endsWith("\n"));
        assertEquals(
            workspace,
            RepresentationDiscoveryRunWorkspace.fromCanonicalJson(
                Files.readString(workspacePath)
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> TargetFreeRepresentationDiscoveryRun.run("WORKTREE")
        );
    }

    private static String canonical(Object content) throws Exception {
        return com.fasterxml.jackson.databind.json.JsonMapper.builder()
            .enable(com.fasterxml.jackson.databind.MapperFeature
                .SORT_PROPERTIES_ALPHABETICALLY)
            .enable(com.fasterxml.jackson.databind.SerializationFeature
                .ORDER_MAP_ENTRIES_BY_KEYS)
            .build()
            .writeValueAsString(content);
    }
}
