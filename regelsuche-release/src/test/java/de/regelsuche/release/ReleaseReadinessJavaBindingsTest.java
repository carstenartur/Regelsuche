package de.regelsuche.release;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

class ReleaseReadinessJavaBindingsTest {
    private static final String FORGED = "sha256:" + "0".repeat(64);
    private static final String RUN = "release-readiness-run.json";

    @Test
    void acceptsExistingRetainedJavaIdentitiesWithoutExecutingACampaign() throws Exception {
        var fixture = fixture();
        assertEquals(48, fixture.size());
        assertDoesNotThrow(() -> ReleaseReadinessBindings.verify(documents(fixture), fixture.get("profiles.json")));
    }

    @Test
    void frozenFixturePreservesAllOriginalBytesAndItsSourceManifest() throws Exception {
        try (var zip = getClass().getResourceAsStream("/release-readiness/qualified-evidence.zip");
                var manifest = getClass().getResourceAsStream("/release-readiness/qualified-evidence-manifest.json")) {
            assertEquals("sha256:2a827ff0e80b0db0a5a40fc3622b1980c18a39573d3613ba3fa1b047b6ea651d",
                ReleaseReadinessBindings.sha256(zip.readAllBytes()));
            var bytes = fixture();
            var entries = new ObjectMapper().readTree(manifest).get("files");
            assertEquals(48, entries.size());
            for (JsonNode entry : entries) {
                byte[] payload = bytes.remove(entry.get("path").textValue());
                assertNotNull(payload);
                assertEquals(entry.get("bytes").intValue(), payload.length);
                assertEquals("sha256:" + entry.get("sha256").textValue(), ReleaseReadinessBindings.sha256(payload));
            }
            assertTrue(bytes.isEmpty());
        }
    }

    @Test
    void rejectsEveryRootReferenceEvenAfterTheAttackerRehashesTheRoot() throws Exception {
        for (String field : new String[] {"campaignManifestHash", "profileCatalogHash", "evidenceHash",
                "hiddenRuleEvidenceHash", "qualificationEvidenceHash", "matrixHash"}) {
            var bytes = fixture();
            var documents = documents(bytes);
            var run = (ObjectNode) documents.get(RUN);
            run.put(field, FORGED);
            run.put("contentHash", rehashRun(run));
            assertThrows(IllegalArgumentException.class, () -> ReleaseReadinessBindings.verify(documents, bytes.get("profiles.json")), field);
        }
    }

    @Test
    void rejectsChangedConsumedFieldsAndDirectDependencyIdentities() throws Exception {
        for (String[] mutation : new String[][] {
                {"evidence-summary.json", "candidateCount", "2"},
                {"campaign/production-campaign-manifest.json", "candidateCount", "2"},
                {"hidden-rule-release-evidence.json", "generatedValidationExamples", "176"},
                {"qualification/candidate-qualification-evidence.json", "pairedUtilityPermille", "999"},
                {"qualification/candidate-qualification-run.json", "suiteHash", FORGED}}) {
            var bytes = fixture();
            var documents = documents(bytes);
            var changed = (ObjectNode) documents.get(mutation[0]);
            if (mutation[2].startsWith("sha256:")) changed.put(mutation[1], mutation[2]);
            else changed.put(mutation[1], Integer.parseInt(mutation[2]));
            assertThrows(IllegalArgumentException.class, () -> ReleaseReadinessBindings.verify(documents, bytes.get("profiles.json")), mutation[0]);
        }
    }

    @Test
    void catalogBindingUsesOriginalBytes() throws Exception {
        var bytes = fixture();
        var changed = (new String(bytes.get("profiles.json"), StandardCharsets.UTF_8) + "\n").getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> ReleaseReadinessBindings.verify(documents(bytes), changed));
    }

    static Map<String, byte[]> fixture() throws IOException {
        var result = new LinkedHashMap<String, byte[]>();
        try (var input = new ZipInputStream(ReleaseReadinessJavaBindingsTest.class.getResourceAsStream("/release-readiness/qualified-evidence.zip"))) {
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) result.put(entry.getName(), input.readAllBytes());
        }
        return result;
    }

    static Map<String, JsonNode> documents(Map<String, byte[]> bytes) throws IOException {
        var result = new LinkedHashMap<String, JsonNode>();
        var mapper = new ObjectMapper();
        for (var entry : bytes.entrySet()) {
            if (entry.getKey().endsWith(".json")) result.put(entry.getKey(), mapper.readTree(entry.getValue()));
        }
        return result;
    }

    static String rehashRun(JsonNode run) {
        String material = run.get("schema").textValue();
        for (String[] field : new String[][] {{"profileCatalog", "profileCatalogHash"}, {"evidence", "evidenceHash"},
                {"hiddenRuleEvidence", "hiddenRuleEvidenceHash"}, {"qualificationEvidence", "qualificationEvidenceHash"},
                {"matrix", "matrixHash"}, {"campaign", "campaignManifestHash"}}) {
            material += "\n" + field[0] + "=" + run.get(field[1]).textValue();
        }
        return de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.hash(material);
    }
}
