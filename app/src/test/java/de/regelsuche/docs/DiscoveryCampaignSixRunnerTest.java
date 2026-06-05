package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscoveryCampaignSixRunnerTest {
    @Test
    void minesRanksAndReportsTopIdentityCandidates(@TempDir Path tempDir) throws Exception {
        DiscoveryCampaignSixRunner runner = new DiscoveryCampaignSixRunner();

        DiscoveryCampaignSixRunner.CampaignReport report = runner.writeReport(tempDir);

        assertEquals("discovery-campaign-6", report.campaignId());
        assertTrue(report.generatedCount() >= 40,
            "campaign 6 should generate a sizable candidate pool");
        assertEquals(20, report.topCandidates().size(), "campaign 6 reports the Top 20 candidates");

        // Ranks are dense, ordered and unique.
        Set<String> ids = new HashSet<>();
        double previousScore = Double.MAX_VALUE;
        for (int index = 0; index < report.topCandidates().size(); index++) {
            DiscoveryCampaignSixRunner.Candidate candidate = report.topCandidates().get(index);
            assertEquals(index + 1, candidate.rank(), "ranks must be dense and 1-based");
            assertTrue(candidate.interestingness() <= previousScore + 1e-9,
                "candidates must be sorted by descending interestingness");
            previousScore = candidate.interestingness();
            assertTrue(ids.add(candidate.id()), "duplicate candidate id: " + candidate.id());
        }

        // Every mined candidate is a genuine identity (equivalence proven deterministically).
        for (DiscoveryCampaignSixRunner.Candidate candidate : report.topCandidates()) {
            assertTrue(candidate.equivalent(), "expected proven equivalence for " + candidate.id());
            assertFalse(candidate.deterministicEvidence().isBlank(),
                "expected deterministic evidence for " + candidate.id());
        }

        assertTrue(report.promotableCount() >= 1, "expected at least one promotable candidate");
        assertTrue(report.topCandidates().stream().anyMatch(DiscoveryCampaignSixRunner.Candidate::promotable),
            "expected a promotable candidate within the Top 20");

        // At least one multi-step derivation (compound substitution requires an expand step).
        assertTrue(report.topCandidates().stream().anyMatch(candidate -> candidate.path().size() >= 4),
            "expected at least one multi-step derivation path");

        assertTrue(Files.exists(tempDir.resolve("discovery-campaign-6.json")));
        Path markdown = tempDir.resolve("identity-mining-report.md");
        assertTrue(Files.exists(markdown));
        String rendered = Files.readString(markdown, StandardCharsets.UTF_8);
        assertTrue(rendered.contains("# Discovery Campaign 6: Open-Ended Identity Mining"));
        assertTrue(rendered.contains("Oracle / proof evidence"));
    }

    @Test
    void identitySubstitutionsAreTreatedAsKnownSeedsNotPromotable() {
        DiscoveryCampaignSixRunner.CampaignReport report = new DiscoveryCampaignSixRunner().run();
        List<DiscoveryCampaignSixRunner.Candidate> baseCases = report.topCandidates().stream()
            .filter(candidate -> "x".equals(candidate.substitution()))
            .toList();
        for (DiscoveryCampaignSixRunner.Candidate candidate : baseCases) {
            assertFalse(candidate.promotable(),
                "base-variable rediscoveries must not be promotable: " + candidate.id());
        }
    }
}
