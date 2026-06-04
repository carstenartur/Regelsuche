package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscoveryCampaignOneRunnerTest {
    @Test
    void campaignReportCapturesShortcutProvenanceAssumptionsAndAblation(@TempDir Path tempDir) throws Exception {
        DiscoveryCampaignOneRunner runner = new DiscoveryCampaignOneRunner();

        DiscoveryCampaignOneRunner.CampaignReport report = runner.writeReport(tempDir);
        Map<String, DiscoveryCampaignOneRunner.CaseResult> results = report.results().stream()
            .collect(Collectors.toMap(DiscoveryCampaignOneRunner.CaseResult::id, Function.identity()));

        assertEquals(8, results.size());
        assertTrue(Files.exists(tempDir.resolve("discovery-campaign-1.json")));
        assertTrue(Files.exists(tempDir.resolve("discovery-campaign-1.md")));
        assertTrue(Files.exists(tempDir.resolve("sympy-qa").resolve("summary.json")));

        DiscoveryCampaignOneRunner.CaseResult completeSquare = results.get("complete-square-family");
        assertTrue(completeSquare.success(), completeSquare.failureReason());
        assertEquals("operator", completeSquare.shortcutSource());
        assertEquals("operator-derived", completeSquare.shortcutPackId());
        assertEquals("complete_square_bridge", completeSquare.shortcutOperatorId());
        assertEquals("DEGRADED", completeSquare.ablationStatus());

        DiscoveryCampaignOneRunner.CaseResult trig = results.get("trig-pythagorean");
        assertTrue(!trig.success(), "trig case should currently document a blocker");
        assertTrue(report.blockers().stream().anyMatch(blocker -> blocker.contains("trig-pythagorean")));

        DiscoveryCampaignOneRunner.CaseResult rationalization = results.get("rationalization-assumptions");
        assertTrue(rationalization.success(), rationalization.failureReason());
        assertEquals("operator", rationalization.shortcutSource());
        assertEquals("operator-derived", rationalization.shortcutPackId());
        assertEquals("rationalization", rationalization.shortcutOperatorId());
        assertTrue(rationalization.shortcutAssumptions().contains("x != 1"), rationalization.shortcutAssumptions().toString());

        DiscoveryCampaignOneRunner.CaseResult log = results.get("log-product-assumptions");
        assertTrue(!log.success(), "log case should currently document an assumptions blocker");
        assertTrue(report.blockers().stream().anyMatch(blocker -> blocker.contains("log-product-assumptions")));
    }
}
