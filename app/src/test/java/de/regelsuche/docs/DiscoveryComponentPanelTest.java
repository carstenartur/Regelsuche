package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
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
        var mapper = new JsonMapper();
        byte[] historical = archive(root, "discovery-component-panel-v1.json.gz");
        byte[] exact = archive(root, "discovery-component-panel-exact-v1.json.gz");
        assertEquals("33300f2dd7f34a53d36ab36d5d241c453a289ab12d1b325310964618a8d169b7", digest(historical));
        assertEquals("fbeb2b16f1a61ff4281c22a9d31996e693e3fcbce709b63f91cdcb4dd971176a", digest(exact));
        assertArrayEquals(exact, replay);
        var report = mapper.readTree(replay);
        verifyValidationRegimeChanges(mapper.readTree(historical), report);
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

    private static byte[] archive(Path root, String name) throws Exception {
        try (var stream = new GZIPInputStream(Files.newInputStream(root.resolve("docs/evidence").resolve(name)))) {
            return stream.readAllBytes();
        }
    }

    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void verifyValidationRegimeChanges(JsonNode historical, JsonNode exact) {
        var historicalPaths = historical.at("/inputs/formationObservations");
        var exactPaths = exact.at("/inputs/formationObservations");
        for (int i = 0; i < historicalPaths.size(); i++) {
            assertEquals(historicalPaths.get(i).get("id"), exactPaths.get(i).get("id"));
            if (i < 12) {
                assertEquals("validated by deterministic numeric samples", historicalPaths.get(i).get("equivalenceEvidence").asText());
                assertEquals("matching exact univariate polynomial normal forms", exactPaths.get(i).get("equivalenceEvidence").asText());
            } else assertEquals(historicalPaths.get(i), exactPaths.get(i));
        }
        assertEquals(historical.at("/inputs/historicalChallengeTraining"), exact.at("/inputs/historicalChallengeTraining"));
        for (var arm : DiscoveryComponentPanel.Arm.values()) {
            var before = historical.get("arms").get(arm.name());
            var after = exact.get("arms").get(arm.name());
            assertEquals(before.get("frozenSchedule"), after.get("frozenSchedule"));
            assertEquals(before.get("selectionAtSlotCeiling"), after.get("selectionAtSlotCeiling"));
            assertEquals(before.get("separateHistoricalChallenge"), after.get("separateHistoricalChallenge"));
            assertEquals("a4fd490ea14deee54a8702b57b2362942b27a0c7310a52959d7923417fbf6567", after.get("inputSha256").asText());
            for (int i = 0; i < after.get("challengesInFrozenOrder").size(); i++) {
                var oldChallenge = before.get("challengesInFrozenOrder").get(i);
                var current = after.get("challengesInFrozenOrder").get(i);
                assertEquals(oldChallenge.get("candidateId"), current.get("candidateId"));
                if (i == 0) {
                    assertEquals("(p1*p2)^-1*p1*p3->p1^-1*p2", current.get("candidateId").asText());
                    assertEquals("NO_COUNTEREXAMPLE_FOUND", oldChallenge.at("/terminalRecheck/status").asText());
                    assertEquals("INCONCLUSIVE", current.at("/terminalRecheck/status").asText());
                    assertEquals("INCONCLUSIVE", current.at("/revisions/0/status").asText());
                } else assertEquals(oldChallenge, current);
            }
        }
    }

    private static void assertChecks(JsonNode report, JsonNode candidate) {
        var checks = candidate.get("challenges");
        assertEquals(report.at("/inputs/maxChallengesPerSlot").asInt(), checks.size() + candidate.get("unusedChallengeSlots").asInt());
        for (var check : checks) assertEquals(report.at("/inputs/budgetPerChallenge"), check.get("budget"));
        assertEquals(checks.get(checks.size()-1).get("result"), candidate.get("terminalRecheck"));
    }
}
