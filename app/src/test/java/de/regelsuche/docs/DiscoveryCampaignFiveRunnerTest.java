package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscoveryCampaignFiveRunnerTest {
    @Test
    void campaignFiveStressSuiteIsUniqueReportedAndPipelineIntegrated(@TempDir Path tempDir) throws Exception {
        DiscoveryCampaignFiveRunner runner = new DiscoveryCampaignFiveRunner();

        DiscoveryCampaignFiveRunner.CampaignReport report = runner.writeReport(tempDir);

        assertTrue(report.results().size() >= 20, "campaign 5 should contain at least 20 cases");
        assertTrue(report.results().size() <= 30, "campaign 5 should stay in curated 20-30 range");

        Set<String> ids = new HashSet<>();
        for (DiscoveryCampaignFiveRunner.CaseResult result : report.results()) {
            assertTrue(ids.add(result.id()), "duplicate campaign 5 id: " + result.id());
        }

        Set<String> existingPairs = existingInputTargetPairs();
        for (DiscoveryCampaignFiveRunner.CaseResult result : report.results()) {
            String pair = pair(result.inputExpression(), result.targetExpression());
            assertFalse(existingPairs.contains(pair), "duplicate pair from campaign 1-3: " + pair);
        }

        assertTrue(report.results().stream().anyMatch(result ->
            result.shortcutAssumptions().stream().anyMatch(assumption -> assumption.startsWith("substitution."))),
            "expected at least one case with substitution evidence on selected shortcut edge");

        assertTrue(report.results().stream().anyMatch(result -> result.rulePath().size() >= 2),
            "expected at least one case with multi-step rule path");

        assertTrue(report.results().stream().anyMatch(result ->
            result.promotionStage().atLeast(PromotionStage.PROMOTED)
                || result.promotionStage().atLeast(PromotionStage.VALIDATED)));

        assertTrue(Files.exists(tempDir.resolve("discovery-campaign-5.json")));
        assertTrue(Files.exists(tempDir.resolve("hidden-structure-report.md")));

        DiscoveryPromotionPipelineRunner.PipelineReport pipelineReport =
            new DiscoveryPromotionPipelineRunner().writeReport(tempDir.resolve("pipeline"));

        assertTrue(pipelineReport.promotionRecords().stream()
            .anyMatch(record -> "discovery-campaign-5".equals(record.sourceCampaign())));

        String metrics = Files.readString(tempDir.resolve("pipeline").resolve("campaign-metrics.json"), StandardCharsets.UTF_8);
        assertTrue(metrics.contains("\"campaign\" : \"discovery-campaign-5\""));

        String gallery = Files.readString(tempDir.resolve("pipeline").resolve("gallery-2.0.md"), StandardCharsets.UTF_8);
        List<String> galleryCandidates = gallery.lines()
            .filter(line -> line.startsWith("| ") && !line.contains("Candidate | Stage") && !line.contains("---"))
            .map(line -> line.split("\\|")[1].trim())
            .toList();
        for (String candidateId : galleryCandidates) {
            assertTrue(pipelineReport.promotionRecords().stream()
                .filter(record -> record.candidateId().equals(candidateId))
                .anyMatch(PromotionRecord::galleryEligible), candidateId);
        }
    }

    private Set<String> existingInputTargetPairs() {
        Set<String> pairs = new HashSet<>();
        collectPairs(pairs, new DiscoveryCampaignOneRunner().run().results().stream()
            .map(result -> pair(result.inputExpression(), result.targetExpression())));
        collectPairs(pairs, new DiscoveryCampaignTwoRunner().run().results().stream()
            .map(result -> pair(result.inputExpression(), result.targetExpression())));
        collectPairs(pairs, new DiscoveryCampaignThreeRunner().run().results().stream()
            .map(result -> pair(result.inputExpression(), result.targetExpression())));
        return pairs;
    }

    private void collectPairs(Set<String> pairs, Stream<String> values) {
        values.forEach(pairs::add);
    }

    private String pair(String left, String right) {
        return left + " -> " + right;
    }
}
