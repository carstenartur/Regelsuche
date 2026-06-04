package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.transform.CompleteSquareBridgeOperator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscoveryCampaignTwoRunnerTest {
    @Test
    void campaignTwoTurnsBlockersIntoSuccessesAndWritesComparisonReports(@TempDir Path tempDir) {
        DiscoveryCampaignTwoRunner runner = new DiscoveryCampaignTwoRunner();

        DiscoveryCampaignTwoRunner.CampaignReport report = runner.writeReport(tempDir);
        Map<String, DiscoveryCampaignTwoRunner.CaseResult> results = report.results().stream()
            .collect(Collectors.toMap(DiscoveryCampaignTwoRunner.CaseResult::id, Function.identity()));

        assertTrue(Files.exists(tempDir.resolve("discovery-campaign-2.json")));
        assertTrue(Files.exists(tempDir.resolve("regression-comparison.md")));
        assertTrue(Files.exists(tempDir.resolve("blockers.md")));
        assertTrue(Files.exists(tempDir.resolve("promoted-candidates.md")));
        assertTrue(Files.exists(tempDir.resolve("new-operator-suggestions.md")));

        DiscoveryCampaignTwoRunner.CaseResult trig = results.get("trig-pythagorean");
        assertEquals("blocked", trig.beforeStatus());
        assertTrue(trig.success(), trig.failureReason());
        assertEquals("sympy-derived", trig.shortcutSource());
        assertEquals("sympy-trig-basic", trig.shortcutPackId());
        assertEquals("trig_pythagorean_identity", trig.shortcutOperatorId());
        assertEquals("DEGRADED", trig.ablationStatus());

        DiscoveryCampaignTwoRunner.CaseResult log = results.get("log-product-assumptions");
        assertEquals("blocked", log.beforeStatus());
        assertTrue(log.success(), log.failureReason());
        assertTrue(log.shortcutAssumptions().contains("a > 0"), log.shortcutAssumptions().toString());
        assertTrue(log.shortcutAssumptions().contains("b > 0"), log.shortcutAssumptions().toString());

        DiscoveryCampaignTwoRunner.CaseResult substitution = results.get("substitution-hidden-structure");
        assertTrue(substitution.success(), substitution.failureReason());
        assertTrue(substitution.rulePath().contains(CompleteSquareBridgeOperator.RULE_ID), substitution.rulePath().toString());

        assertTrue(report.comparison().stream().anyMatch(row ->
            row.caseId().equals("trig-pythagorean")
                && row.beforeStatus().equals("blocked")
                && row.afterStatus().equals("success")));
    }
}
