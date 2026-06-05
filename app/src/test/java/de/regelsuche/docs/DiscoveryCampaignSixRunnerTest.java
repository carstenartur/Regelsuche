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
    void reportsCountableMoveEnumerationProbe(@TempDir Path tempDir) throws Exception {
        DiscoveryCampaignSixRunner runner = new DiscoveryCampaignSixRunner();

        DiscoveryCampaignSixRunner.CampaignReport report = runner.writeReport(tempDir);

        assertEquals("discovery-campaign-6", report.campaignId());
        assertEquals(4, report.cases().size());
        assertTrue(report.cases().stream().allMatch(DiscoveryCampaignSixRunner.CaseResult::expectedMovePresent));
        assertTrue(report.cases().stream().allMatch(DiscoveryCampaignSixRunner.CaseResult::depth1SearchObserved));

        DiscoveryCampaignSixRunner.CaseResult cancellation = report.cases().stream()
            .filter(result -> result.id().equals("cancellation-plus-one"))
            .findFirst()
            .orElseThrow();
        assertTrue(cancellation.comparison().moveCandidates().stream()
            .anyMatch(candidate -> "+1".equals(candidate.parameters().get("cancel"))));
        assertTrue(cancellation.comparison().moveOnlyCandidates().stream()
            .anyMatch(candidate -> candidate.transformedExpression().contains("+ 1 = 0 + 1")));

        DiscoveryCampaignSixRunner.CaseResult completeSquare = report.cases().stream()
            .filter(result -> result.id().equals("complete-square"))
            .findFirst()
            .orElseThrow();
        assertTrue(completeSquare.comparison().moveCandidates().stream()
            .anyMatch(candidate -> "3".equals(candidate.parameters().get("shift"))
                && "-4".equals(candidate.parameters().get("residue"))));
        assertTrue(completeSquare.comparison().overlaps().stream()
            .anyMatch(candidate -> candidate.transformedExpression().equals("(x + 3) ^ 2 - 4")));

        assertTrue(Files.exists(tempDir.resolve("discovery-campaign-6.json")));
        Path markdown = tempDir.resolve("countable-move-enumeration-report.md");
        assertTrue(Files.exists(markdown));
        String rendered = Files.readString(markdown, StandardCharsets.UTF_8);
        assertTrue(rendered.contains("# Discovery Campaign 6: Countable Move Enumeration Probe"));
        assertTrue(rendered.contains("Move-Enumerator-Kandidaten"));
        assertTrue(rendered.contains("Nur aus Move-Enumeration"));
    }
}
