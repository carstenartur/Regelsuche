package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscoveryComponentPanelTest {
    @Test void reproducesTheRetainedMatrixWithMatchedBudgetsAndUnchangedRechecks(@TempDir Path tmp) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("settings.gradle"))) root = root.getParent();
        assertNotNull(root);
        Path output = tmp.resolve("result.json");
        DiscoveryComponentPanel.main(new String[]{output.toString()});
        byte[] replay = Files.readAllBytes(output);
        try (var compressed = new GZIPInputStream(Files.newInputStream(
                root.resolve("docs/evidence/discovery-component-panel-v1.json.gz")))) {
            assertArrayEquals(compressed.readAllBytes(), replay);
        }
        var report = new JsonMapper().readTree(replay);
        var arms = report.get("arms");
        var full = arms.get("PRODUCTION");
        assertEquals(23, report.at("/inputs/formationObservations").size());
        assertEquals(12, report.at("/inputs/historicalChallengeTraining").size());
        assertEquals(5, full.get("frozenSchedule").size());
        assertEquals(23, arms.get("NO_FAN_IN").get("miningDispatches").size());
        assertEquals(0, arms.get("NO_FAN_IN").get("frozenSchedule").size());
        assertEquals(full.get("frozenSchedule"), arms.get("NO_REFINEMENT").get("frozenSchedule"));
        assertNotEquals(full.get("frozenSchedule"), arms.get("NO_NOVELTY_SCHEDULING").get("frozenSchedule"));
        assertEquals(full.get("selectionAtSlotCeiling"), arms.get("NO_NOVELTY_SCHEDULING").get("selectionAtSlotCeiling"));
        for (JsonNode arm : arms) {
            assertEquals(full.get("inputSha256"), arm.get("inputSha256"));
            assertEquals(report.at("/inputs/candidateSlots").asInt(),
                arm.get("unusedCandidateSlots").asInt() + arm.get("frozenSchedule").size());
            Set<String> processed = new HashSet<>();
            for (var dispatch : arm.get("miningDispatches")) for (var id : dispatch.get("observationIds")) assertTrue(processed.add(id.asText()));
            assertEquals(23, processed.size());
            for (var candidate : arm.get("challengesInFrozenOrder")) assertChecks(report, candidate);
            assertChecks(report, arm.get("separateHistoricalChallenge"));
            assertEquals("NO_COUNTEREXAMPLE_FOUND", arm.at("/separateHistoricalChallenge/terminalRecheck/status").asText());
        }
    }

    private static void assertChecks(JsonNode report, JsonNode candidate) {
        var checks = candidate.get("challenges");
        assertEquals(report.at("/inputs/maxChallengesPerSlot").asInt(), checks.size() + candidate.get("unusedChallengeSlots").asInt());
        for (var check : checks) assertEquals(report.at("/inputs/budgetPerChallenge"), check.get("budget"));
        assertEquals(checks.get(checks.size()-1).get("result"), candidate.get("terminalRecheck"));
    }
}
