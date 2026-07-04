package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscoveryCampaignEightRunnerTest {

    @Test
    void campaignEightCasesAreUniqueAndNonEmpty(@TempDir Path tempDir) throws Exception {
        DiscoveryCampaignEightRunner runner = new DiscoveryCampaignEightRunner();

        DiscoveryCampaignEightRunner.CampaignReport report = runner.writeReport(tempDir);

        assertFalse(report.results().isEmpty(), "campaign 8 must have at least one case");

        Set<String> ids = new HashSet<>();
        for (DiscoveryCampaignEightRunner.CaseResult result : report.results()) {
            assertTrue(ids.add(result.id()), "duplicate campaign 8 id: " + result.id());
        }

        List<NoveltyChecker.Candidate> priorCandidates = priorCampaignCandidates();
        List<NoveltyChecker.Candidate> allCandidates = new ArrayList<>(priorCandidates);
        report.results().stream().map(this::candidate).forEach(allCandidates::add);
        List<NoveltyChecker.NoveltyResult> noveltyResults = new NoveltyChecker().classifyAll(allCandidates);

        for (NoveltyChecker.NoveltyResult result : noveltyResults.subList(priorCandidates.size(), noveltyResults.size())) {
            assertFalse(result.status() == NoveltyStatus.DUPLICATE,
                "campaign 8 must not repeat a prior normalized input/target pair: " + result);
            assertFalse(result.status() == NoveltyStatus.ALPHA_EQUIVALENT,
                "campaign 8 must not add alpha-equivalent duplicates: " + result);
            assertFalse(result.status() == NoveltyStatus.KNOWN_RULE,
                "campaign 8 must not be classified as a known-rule-only candidate: " + result);
            assertFalse(result.status() == NoveltyStatus.UNKNOWN,
                "campaign 8 candidates must have enough novelty input data: " + result);
        }
    }

    @Test
    void campaignEightWritesCandidateReports(@TempDir Path tempDir) throws Exception {
        DiscoveryCampaignEightRunner runner = new DiscoveryCampaignEightRunner();

        runner.writeReport(tempDir);

        assertTrue(Files.exists(tempDir.resolve("discovery-campaign-8.json")));
        assertTrue(Files.exists(tempDir.resolve("campaign-progress.md")));
        assertTrue(Files.exists(tempDir.resolve("discovery-candidates.md")));
        assertTrue(Files.exists(tempDir.resolve("operator-suggestions.md")));
        assertTrue(Files.exists(tempDir.resolve("macro-candidates.md")));
    }

    @Test
    void campaignEightProgressReportComparesThreeCampaigns(@TempDir Path tempDir) throws Exception {
        DiscoveryCampaignEightRunner runner = new DiscoveryCampaignEightRunner();

        DiscoveryCampaignEightRunner.CampaignReport report = runner.writeReport(tempDir);

        assertFalse(report.progress().isEmpty(), "progress summaries must not be empty");
        Set<String> progressIds = new HashSet<>();
        for (DiscoveryCampaignEightRunner.ProgressSummary summary : report.progress()) {
            assertTrue(progressIds.add(summary.campaignId()), "duplicate progress entry: " + summary.campaignId());
        }
        assertTrue(progressIds.contains("discovery-campaign-5"), "progress must include campaign 5");
        assertTrue(progressIds.contains("discovery-campaign-7"), "progress must include campaign 7");
        assertTrue(progressIds.contains("discovery-campaign-8"), "progress must include campaign 8");

        String progressMarkdown = Files.readString(tempDir.resolve("campaign-progress.md"), StandardCharsets.UTF_8);
        assertTrue(progressMarkdown.contains("discovery-campaign-5"));
        assertTrue(progressMarkdown.contains("discovery-campaign-7"));
        assertTrue(progressMarkdown.contains("discovery-campaign-8"));
        assertTrue(progressMarkdown.contains("Promotion-ready"), "progress table must use 'Promotion-ready' column header");
        assertTrue(progressMarkdown.contains("Promotion-ready** means"), "progress table must include definition note");
    }

    @Test
    void campaignEightIsIntegratedIntoPromotionPipeline(@TempDir Path tempDir) throws Exception {
        DiscoveryPromotionPipelineRunner.PipelineReport pipelineReport =
            new DiscoveryPromotionPipelineRunner().writeReport(tempDir.resolve("pipeline"));

        assertTrue(pipelineReport.promotionRecords().stream()
            .anyMatch(record -> "discovery-campaign-8".equals(record.sourceCampaign())),
            "pipeline must include campaign 8 promotion records");

        assertTrue(pipelineReport.campaignEight() != null,
            "pipeline report must include campaign 8 report");

        Path metricsPath = tempDir.resolve("pipeline").resolve("campaign-metrics.json");
        List<Map<String, Object>> metrics = new ObjectMapper()
            .readValue(metricsPath.toFile(), new TypeReference<>() {});
        assertTrue(metrics.stream().anyMatch(m -> "discovery-campaign-8".equals(m.get("campaign"))),
            "campaign-metrics.json must include campaign 8");

        assertTrue(Files.exists(
            tempDir.resolve("pipeline").resolve("discovery-campaign-8").resolve("discovery-campaign-8.json")));
        assertTrue(Files.exists(
            tempDir.resolve("pipeline").resolve("discovery-campaign-8").resolve("discovery-candidates.md")));
        assertTrue(Files.exists(
            tempDir.resolve("pipeline").resolve("discovery-campaign-8").resolve("operator-suggestions.md")));
        assertTrue(Files.exists(
            tempDir.resolve("pipeline").resolve("discovery-campaign-8").resolve("macro-candidates.md")));
    }

    @Test
    void campaignEightCandidatesReportContainsProvenanceFields(@TempDir Path tempDir) throws Exception {
        DiscoveryCampaignEightRunner runner = new DiscoveryCampaignEightRunner();
        runner.writeReport(tempDir);

        String candidatesMarkdown = Files.readString(
            tempDir.resolve("discovery-candidates.md"), StandardCharsets.UTF_8);
        assertTrue(candidatesMarkdown.contains("| Candidate |"), "must have Candidate column");
        assertTrue(candidatesMarkdown.contains("| Family |"), "must have Family column");
        assertTrue(candidatesMarkdown.contains("| Stage |"), "must have Stage column");
        assertTrue(candidatesMarkdown.contains("| Novelty |"), "must have Novelty column");
        assertTrue(candidatesMarkdown.contains("| Oracle |"), "must have Oracle column");
        assertTrue(candidatesMarkdown.contains("| Ablation |"), "must have Ablation column");
        assertTrue(candidatesMarkdown.contains("| Source |"), "must have Source column");
        assertTrue(candidatesMarkdown.contains("| Pack |"), "must have Pack column");
        assertTrue(candidatesMarkdown.contains("| Operator |"), "must have Operator column");
    }

    @Test
    void campaignEightCoversDistinctFamilies(@TempDir Path tempDir) throws Exception {
        DiscoveryCampaignEightRunner runner = new DiscoveryCampaignEightRunner();
        DiscoveryCampaignEightRunner.CampaignReport report = runner.writeReport(tempDir);

        Set<String> families = new HashSet<>();
        for (DiscoveryCampaignEightRunner.CaseResult result : report.results()) {
            families.add(result.family());
        }
        assertTrue(families.size() >= 2, "campaign 8 must cover at least 2 distinct families, found: " + families);
    }

    private List<NoveltyChecker.Candidate> priorCampaignCandidates() {
        List<NoveltyChecker.Candidate> candidates = new ArrayList<>();
        new DiscoveryCampaignOneRunner().run().results().stream().map(this::candidate).forEach(candidates::add);
        new DiscoveryCampaignTwoRunner().run().results().stream().map(this::candidate).forEach(candidates::add);
        new DiscoveryCampaignThreeRunner().run().results().stream().map(this::candidate).forEach(candidates::add);
        new DiscoveryCampaignFiveRunner().run().results().stream().map(this::candidate).forEach(candidates::add);
        new DiscoveryCampaignSevenRunner().run().results().stream().map(this::candidate).forEach(candidates::add);
        return candidates;
    }

    private NoveltyChecker.Candidate candidate(DiscoveryCampaignOneRunner.CaseResult result) {
        return candidate(result.id(), result.family(), result.inputExpression(), result.targetExpression(), result.shortcutOperatorId(), result.rulePath());
    }

    private NoveltyChecker.Candidate candidate(DiscoveryCampaignTwoRunner.CaseResult result) {
        return candidate(result.id(), result.family(), result.inputExpression(), result.targetExpression(), result.shortcutOperatorId(), result.rulePath());
    }

    private NoveltyChecker.Candidate candidate(DiscoveryCampaignThreeRunner.CaseResult result) {
        return candidate(result.id(), result.family(), result.inputExpression(), result.targetExpression(), result.shortcutOperatorId(), result.rulePath());
    }

    private NoveltyChecker.Candidate candidate(DiscoveryCampaignFiveRunner.CaseResult result) {
        return candidate(result.id(), result.family(), result.inputExpression(), result.targetExpression(), result.shortcutOperatorId(), result.rulePath());
    }

    private NoveltyChecker.Candidate candidate(DiscoveryCampaignSevenRunner.CaseResult result) {
        return candidate(result.id(), result.family(), result.inputExpression(), result.targetExpression(), result.shortcutOperatorId(), result.rulePath());
    }

    private NoveltyChecker.Candidate candidate(DiscoveryCampaignEightRunner.CaseResult result) {
        return candidate(result.id(), result.family(), result.inputExpression(), result.targetExpression(), result.shortcutOperatorId(), result.rulePath());
    }

    private NoveltyChecker.Candidate candidate(
        String id,
        String family,
        String input,
        String target,
        String operator,
        List<String> rulePath
    ) {
        return new NoveltyChecker.Candidate(id, family, input, target, operator, rulePath);
    }
}
