package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.WARNING_KNOWN_FORM_WITHOUT_NEW_CAPABILITY;
import static de.regelsuche.discovery.representation.RepresentationCandidateAssessment.WARNING_KNOWN_STRUCTURE_EVIDENCE_BELOW_MINIMUM;
import static de.regelsuche.discovery.representation.SymPyKnowledgeBridgeScenario.CANDIDATE_EXPRESSION;
import static de.regelsuche.discovery.representation.SymPyKnowledgeBridgeScenario.CLAIM_BOUNDARY;
import static de.regelsuche.discovery.representation.SymPyKnowledgeBridgeScenario.CONSEQUENCE_ID;
import static de.regelsuche.discovery.representation.SymPyKnowledgeBridgeScenario.FOLLOW_ON_EXPRESSION;
import static de.regelsuche.discovery.representation.SymPyKnowledgeBridgeScenario.FORMATION_RULE_ID;
import static de.regelsuche.discovery.representation.SymPyKnowledgeBridgeScenario.SCHEMA;
import static de.regelsuche.discovery.representation.SymPyKnowledgeBridgeScenario.STRUCTURE_ID;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.regelsuche.validation.CandidateProofStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SymPyKnowledgeBridgeScenarioTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void packSelectionAndEvidenceGateChangeConcreteCapabilities()
            throws Exception {
        SymPyKnowledgeBridgeScenario.ScenarioArtifact artifact =
            SymPyKnowledgeBridgeScenario.run();
        var content = artifact.content();
        var comparison = content.comparison();
        var followOn = content.followOnExecution();

        assertEquals(SCHEMA, content.schema());
        assertEquals("R2", content.informationTrack());
        assertEquals(CANDIDATE_EXPRESSION, content.candidateExpression());
        assertEquals(FORMATION_RULE_ID, content.formation().ruleId());
        assertEquals("core", content.formation().packId());
        assertEquals(List.of(FORMATION_RULE_ID),
            content.formation().primitiveRuleIds());
        assertTrue(content.formation()
            .equivalencePreservingByConstruction());
        assertTrue(content.formation().assumptions().isEmpty());
        assertEquals("AGREE", content.validation().oracleStatus());
        assertEquals(
            CandidateProofStatus.SYMBOLICALLY_VERIFIED.name(),
            content.validation().candidateProofStatus()
        );

        assertEquals(STRUCTURE_ID,
            content.targetStructure().structureId());
        assertEquals("SymPy",
            content.targetStructure().sourceProject());
        assertEquals("BSD-3-Clause",
            content.targetStructure().license());
        assertEquals(CONSEQUENCE_ID,
            content.targetStructure().consequenceId());
        assertNotEquals(
            content.informationIdentities().disabledCatalogHash(),
            content.informationIdentities().enabledCatalogHash()
        );

        assertEquals(0, comparison.disabledMatches());
        assertEquals(1, comparison.provisionalMatches());
        assertTrue(comparison.provisionalUnlocks().isEmpty());
        assertTrue(comparison.provisionalWarnings().contains(
            WARNING_KNOWN_STRUCTURE_EVIDENCE_BELOW_MINIMUM));
        assertFalse(comparison.provisionalWarnings().contains(
            WARNING_KNOWN_FORM_WITHOUT_NEW_CAPABILITY));
        assertEquals(1, comparison.verifiedMatches());
        assertEquals(List.of(CONSEQUENCE_ID),
            comparison.verifiedUnlocks());

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
        assertEquals(CLAIM_BOUNDARY, content.claimBoundary());
        assertEquals(
            KnownStructureCatalog.sha256(canonicalContent(content)),
            artifact.contentHash()
        );
        assertThrows(IllegalArgumentException.class,
            () -> new SymPyKnowledgeBridgeScenario.ScenarioArtifact(
                content,
                "sha256:" + "0".repeat(64)
            ));
    }

    @Test
    void retainedArtifactIsByteStable(@TempDir Path temporary)
            throws Exception {
        String first = SymPyKnowledgeBridgeScenario.run().toCanonicalJson();
        String second = SymPyKnowledgeBridgeScenario.run().toCanonicalJson();
        assertEquals(first, second);

        Path retained = Path.of(
            "build/reports/representation-discovery/"
                + "sympy-knowledge-bridge.json");
        Path copy = temporary.resolve("sympy-knowledge-bridge.json");
        SymPyKnowledgeBridgeScenario.write(retained);
        SymPyKnowledgeBridgeScenario.write(copy);
        assertArrayEquals(
            Files.readAllBytes(retained),
            Files.readAllBytes(copy)
        );

        JsonNode root = JSON.readTree(Files.readString(retained));
        assertEquals(SCHEMA,
            root.path("content").path("schema").asText());
        assertEquals(
            root.path("contentHash").asText(),
            SymPyKnowledgeBridgeScenario.run().contentHash()
        );
    }

    private static String canonicalContent(
        SymPyKnowledgeBridgeScenario.ScenarioContent content
    ) throws Exception {
        return JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build()
            .writeValueAsString(content);
    }
}
