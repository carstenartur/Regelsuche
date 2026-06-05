package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscoveryCampaignSixRunnerTest {
    @Test
    void reportsCountableMoveSearchProbe(@TempDir Path tempDir) throws Exception {
        DiscoveryCampaignSixRunner runner = new DiscoveryCampaignSixRunner();

        DiscoveryCampaignSixRunner.CampaignReport report = runner.writeReport(tempDir);

        assertEquals("discovery-campaign-6", report.campaignId());
        assertEquals(4, report.cases().size());
        assertTrue(report.cases().stream().allMatch(result -> result.depth1CandidateProbe().expectedMovePresent()));
        assertTrue(report.relatedFollowUpIssues().stream().anyMatch(issue -> issue.contains("#102")));

        DiscoveryCampaignSixRunner.CaseResult cancellation = report.cases().stream()
            .filter(result -> result.id().equals("cancellation-plus-one"))
            .findFirst()
            .orElseThrow();
        assertEquals("Move-only", cancellation.depth1CandidateProbe().expectedMoveCoverage());
        assertEquals("Missing normalizer", cancellation.architectureNote());
        assertTrue(cancellation.multiStepSearch().failureReason().contains("TARGET_NOT_REACHED")
            || cancellation.multiStepSearch().failureReason().contains("MAX_STATES_REACHED"));

        DiscoveryCampaignSixRunner.CaseResult completeSquare = report.cases().stream()
            .filter(result -> result.id().equals("complete-square"))
            .findFirst()
            .orElseThrow();
        assertTrue(completeSquare.multiStepSearch().success());
        assertTrue(completeSquare.multiStepSearch().pathLength() >= 1);
        assertTrue(completeSquare.multiStepSearch().ordinalPath().size() == completeSquare.multiStepSearch().pathLength());

        assertTrue(Files.exists(tempDir.resolve("discovery-campaign-6.json")));
        Path markdown = tempDir.resolve("countable-move-enumeration-report.md");
        assertTrue(Files.exists(markdown));
        String rendered = Files.readString(markdown, StandardCharsets.UTF_8);
        assertTrue(rendered.contains("# Discovery Campaign 6: Countable Move Search Probe"));
        assertTrue(rendered.contains("Depth-1 Candidate Summary"));
        assertTrue(rendered.contains("Multi-step Search Result"));
        assertTrue(rendered.contains("Classic-vs-Move Vergleich"));
        assertTrue(rendered.contains("Related follow-up issues"));
    }
}
