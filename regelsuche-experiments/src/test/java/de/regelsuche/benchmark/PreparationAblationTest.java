package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PreparationAblationTest {
    @Test
    void runsAllFourProfilesOnTheFrozenPublicPanelAndRetainsEveryAttempt() throws Exception {
        String revision = System.getenv("REGELSUCHE_PREPARATION_REVISION");
        if (revision == null) {
            var git = new ProcessBuilder("git", "rev-parse", "HEAD").redirectErrorStream(true).start();
            revision = new String(git.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
            assertEquals(0, git.waitFor(), revision);
        }
        var report = PreparationAblation.measure(revision);
        var profiles = report.required("profiles");
        assertEquals(4, profiles.size());
        var targets = Map.of(
            SymPyRuleAmplificationExperiment.PYTHAGOREAN_RULE_ID, "1",
            SymPyRuleAmplificationExperiment.DIFFERENCE_OF_SQUARES_RULE_ID, "(x - y) * (x + y)",
            SymPyRuleAmplificationExperiment.TELESCOPING_RULE_ID, "1 / n - 1 / (n + 1)");
        var totals = new java.util.ArrayList<Integer>();
        var hashes = new HashSet<String>();
        for (JsonNode profile : profiles) {
            assertTrue(hashes.add(profile.required("identity").textValue()));
            assertEquals(11, profile.required("cases").size());
            var accepted = 0;
            for (JsonNode row : profile.required("cases")) {
                assertEquals(3, row.required("outcomes").size());
                assertEquals(row.required("outcomesSha256"), row.required("additionalReplayLedgerSha256"));
                for (JsonNode outcome : row.required("outcomes")) {
                    assertTrue(outcome.required("stages").size() > 0);
                    if (outcome.has("candidate")) {
                        assertEquals(row.required("expectedPrincipal"), outcome.required("ruleId"), row.toString());
                        assertFalse(row.required("nearMiss").asBoolean(), row.toString());
                        var candidate = outcome.required("candidate");
                        assertEquals(row.required("expectedResultAssumptions"), candidate.required("assumptions"));
                        assertEquals(targets.get(outcome.required("ruleId").textValue()), candidate.required("expression").textValue());
                        var lineage = candidate.required("primitiveRuleIds");
                        assertTrue(lineage.size() > 0);
                        assertEquals(outcome.required("ruleId"), lineage.get(lineage.size() - 1));
                        boolean prepared = candidate.required("preparationDepth").intValue() > 0;
                        assertEquals(prepared ? "PREPARED" : "DIRECT_MATCH_AVAILABLE", outcome.required("status").textValue());
                        if ("LOCAL_BRIDGE".equals(outcome.required("selectedStage").textValue())) {
                            assertTrue(outcome.required("certificateHash").textValue().matches("sha256:[0-9a-f]{64}"));
                            assertTrue(candidate.required("preparationDepth").intValue() > 0);
                        }
                        accepted++;
                    } else {
                        String expected = "SAFE_PREPARATION_PLUS_LOCAL_BRIDGE".equals(profile.required("profile").textValue())
                            ? "NO_BRIDGE_IN_COMPLETE_FROZEN_CLOSURE" : "NO_MATCH_IN_ENABLED_STAGES";
                        assertEquals(expected, outcome.required("status").textValue(), row.toString());
                    }
                }
            }
            totals.add(accepted);
        }
        assertEquals(List.of(3, 4, 4, 8), totals);
        var output = Path.of(Boolean.getBoolean("regelsuche.maven.reactor") ? "target" : "build",
            "reports", "preparation-ablation", "comparison.json");
        Files.createDirectories(output.getParent());
        Files.write(output, new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(report));
    }
}
