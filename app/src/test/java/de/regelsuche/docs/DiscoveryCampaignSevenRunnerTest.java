package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscoveryCampaignSevenRunnerTest {

    @Test
    void campaignSevenCasesAreUniqueAndNonEmpty(@TempDir Path tempDir) throws Exception {
        DiscoveryCampaignSevenRunner runner = new DiscoveryCampaignSevenRunner();

        DiscoveryCampaignSevenRunner.CampaignReport report = runner.writeReport(tempDir);

        assertFalse(report.results().isEmpty(), "campaign 7 must have at least one case");

        Set<String> ids = new HashSet<>();
        for (DiscoveryCampaignSevenRunner.CaseResult result : report.results()) {
            assertTrue(ids.add(result.id()), "duplicate campaign 7 id: " + result.id());
        }

        Set<String> existingPairs = existingInputTargetPairs();
        for (DiscoveryCampaignSevenRunner.CaseResult result : report.results()) {
            String pair = pair(result.inputExpression(), result.targetExpression());
            assertFalse(existingPairs.contains(pair), "duplicate pair from prior campaigns: " + pair);
        }
    }

    @Test
    void campaignSevenWritesCandidateReports(@TempDir Path tempDir) throws Exception {
        DiscoveryCampaignSevenRunner runner = new DiscoveryCampaignSevenRunner();

        runner.writeReport(tempDir);

        assertTrue(Files.exists(tempDir.resolve("discovery-campaign-7.json")));
        assertTrue(Files.exists(tempDir.resolve("campaign-progress.md")));
        assertTrue(Files.exists(tempDir.resolve("discovery-candidates.md")));
        assertTrue(Files.exists(tempDir.resolve("operator-suggestions.md")));
        assertTrue(Files.exists(tempDir.resolve("macro-candidates.md")));
    }

    @Test
    void campaignSevenProgressReportComparesThreeCampaigns(@TempDir Path tempDir) throws Exception {
        DiscoveryCampaignSevenRunner runner = new DiscoveryCampaignSevenRunner();

        DiscoveryCampaignSevenRunner.CampaignReport report = runner.writeReport(tempDir);

        assertFalse(report.progress().isEmpty(), "progress summaries must not be empty");
        Set<String> progressIds = new HashSet<>();
        for (DiscoveryCampaignSevenRunner.ProgressSummary summary : report.progress()) {
            assertTrue(progressIds.add(summary.campaignId()), "duplicate progress entry: " + summary.campaignId());
        }
        assertTrue(progressIds.contains("discovery-campaign-3"), "progress must include campaign 3");
        assertTrue(progressIds.contains("discovery-campaign-5"), "progress must include campaign 5");
        assertTrue(progressIds.contains("discovery-campaign-7"), "progress must include campaign 7");

        String progressMarkdown = Files.readString(tempDir.resolve("campaign-progress.md"), StandardCharsets.UTF_8);
        assertTrue(progressMarkdown.contains("discovery-campaign-3"));
        assertTrue(progressMarkdown.contains("discovery-campaign-5"));
        assertTrue(progressMarkdown.contains("discovery-campaign-7"));
    }

    @Test
    void campaignSevenIsIntegratedIntoPromotionPipeline(@TempDir Path tempDir) throws Exception {
        DiscoveryPromotionPipelineRunner.PipelineReport pipelineReport =
            new DiscoveryPromotionPipelineRunner().writeReport(tempDir.resolve("pipeline"));

        assertTrue(pipelineReport.promotionRecords().stream()
            .anyMatch(record -> "discovery-campaign-7".equals(record.sourceCampaign())),
            "pipeline must include campaign 7 promotion records");

        assertTrue(pipelineReport.campaignSeven() != null,
            "pipeline report must include campaign 7 report");

        Path metricsPath = tempDir.resolve("pipeline").resolve("campaign-metrics.json");
        List<Map<String, Object>> metrics = new ObjectMapper()
            .readValue(metricsPath.toFile(), new TypeReference<>() {});
        assertTrue(metrics.stream().anyMatch(m -> "discovery-campaign-7".equals(m.get("campaign"))),
            "campaign-metrics.json must include campaign 7");

        assertTrue(Files.exists(
            tempDir.resolve("pipeline").resolve("discovery-campaign-7").resolve("discovery-campaign-7.json")));
        assertTrue(Files.exists(
            tempDir.resolve("pipeline").resolve("discovery-campaign-7").resolve("discovery-candidates.md")));
        assertTrue(Files.exists(
            tempDir.resolve("pipeline").resolve("discovery-campaign-7").resolve("operator-suggestions.md")));
        assertTrue(Files.exists(
            tempDir.resolve("pipeline").resolve("discovery-campaign-7").resolve("macro-candidates.md")));
    }

    @Test
    void campaignSevenCandidatesReportContainsProvenanceFields(@TempDir Path tempDir) throws Exception {
        DiscoveryCampaignSevenRunner runner = new DiscoveryCampaignSevenRunner();
        runner.writeReport(tempDir);

        String candidatesMarkdown = Files.readString(
            tempDir.resolve("discovery-candidates.md"), StandardCharsets.UTF_8);
        assertTrue(candidatesMarkdown.contains("| Candidate |"), "must have Candidate column");
        assertTrue(candidatesMarkdown.contains("| Family |"), "must have Family column");
        assertTrue(candidatesMarkdown.contains("| Stage |"), "must have Stage column");
        assertTrue(candidatesMarkdown.contains("| Oracle |"), "must have Oracle column");
        assertTrue(candidatesMarkdown.contains("| Ablation |"), "must have Ablation column");
        assertTrue(candidatesMarkdown.contains("| Source |"), "must have Source column");
        assertTrue(candidatesMarkdown.contains("| Pack |"), "must have Pack column");
        assertTrue(candidatesMarkdown.contains("| Operator |"), "must have Operator column");
    }

    @Test
    void campaignSevenCoversDistinctFamilies(@TempDir Path tempDir) throws Exception {
        DiscoveryCampaignSevenRunner runner = new DiscoveryCampaignSevenRunner();
        DiscoveryCampaignSevenRunner.CampaignReport report = runner.writeReport(tempDir);

        Set<String> families = new HashSet<>();
        for (DiscoveryCampaignSevenRunner.CaseResult result : report.results()) {
            families.add(result.family());
        }
        assertTrue(families.size() >= 2, "campaign 7 must cover at least 2 distinct families, found: " + families);
    }

    private Set<String> existingInputTargetPairs() {
        Set<String> pairs = new HashSet<>();
        collectPairs(pairs, new DiscoveryCampaignOneRunner().run().results().stream()
            .map(result -> pair(result.inputExpression(), result.targetExpression())));
        collectPairs(pairs, new DiscoveryCampaignTwoRunner().run().results().stream()
            .map(result -> pair(result.inputExpression(), result.targetExpression())));
        collectPairs(pairs, new DiscoveryCampaignThreeRunner().run().results().stream()
            .map(result -> pair(result.inputExpression(), result.targetExpression())));
        collectPairs(pairs, new DiscoveryCampaignFiveRunner().run().results().stream()
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
