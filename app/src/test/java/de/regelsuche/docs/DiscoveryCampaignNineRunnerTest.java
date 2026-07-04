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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscoveryCampaignNineRunnerTest {

    @Test
    void campaignNineCasesAreUniqueAndNonEmpty(@TempDir Path tempDir) throws Exception {
        DiscoveryCampaignNineRunner runner = new DiscoveryCampaignNineRunner();

        DiscoveryCampaignNineRunner.CampaignReport report = runner.writeReport(tempDir);

        assertFalse(report.results().isEmpty(), "campaign 9 must have at least one case");

        Set<String> ids = new HashSet<>();
        for (DiscoveryCampaignNineRunner.CaseResult result : report.results()) {
            assertTrue(ids.add(result.id()), "duplicate campaign 9 id: " + result.id());
        }

        Set<String> priorCaseIds = priorCampaignCaseIds();
        for (DiscoveryCampaignNineRunner.CaseResult result : report.results()) {
            assertFalse(priorCaseIds.contains(result.id()),
                "campaign 9 case id duplicates a prior campaign case id: " + result.id());
        }
    }

    @Test
    void campaignNineWritesCandidateReports(@TempDir Path tempDir) throws Exception {
        DiscoveryCampaignNineRunner runner = new DiscoveryCampaignNineRunner();

        runner.writeReport(tempDir);

        assertTrue(Files.exists(tempDir.resolve("discovery-campaign-9.json")));
        assertTrue(Files.exists(tempDir.resolve("campaign-progress.md")));
        assertTrue(Files.exists(tempDir.resolve("discovery-candidates.md")));
        assertTrue(Files.exists(tempDir.resolve("operator-suggestions.md")));
        assertTrue(Files.exists(tempDir.resolve("macro-candidates.md")));
    }

    @Test
    void campaignNineProgressReportComparesThreeCampaigns(@TempDir Path tempDir) throws Exception {
        DiscoveryCampaignNineRunner runner = new DiscoveryCampaignNineRunner();

        DiscoveryCampaignNineRunner.CampaignReport report = runner.writeReport(tempDir);

        assertFalse(report.progress().isEmpty(), "progress summaries must not be empty");
        Set<String> progressIds = new HashSet<>();
        for (DiscoveryCampaignNineRunner.ProgressSummary summary : report.progress()) {
            assertTrue(progressIds.add(summary.campaignId()), "duplicate progress entry: " + summary.campaignId());
        }
        assertTrue(progressIds.contains("discovery-campaign-7"), "progress must include campaign 7");
        assertTrue(progressIds.contains("discovery-campaign-8"), "progress must include campaign 8");
        assertTrue(progressIds.contains("discovery-campaign-9"), "progress must include campaign 9");

        String progressMarkdown = Files.readString(tempDir.resolve("campaign-progress.md"), StandardCharsets.UTF_8);
        assertTrue(progressMarkdown.contains("discovery-campaign-7"));
        assertTrue(progressMarkdown.contains("discovery-campaign-8"));
        assertTrue(progressMarkdown.contains("discovery-campaign-9"));
        assertTrue(progressMarkdown.contains("Promotion-ready"), "progress table must use 'Promotion-ready' column header");
        assertTrue(progressMarkdown.contains("Promotion-ready** means"), "progress table must include definition note");
    }

    @Test
    void campaignNineIsIntegratedIntoPromotionPipeline(@TempDir Path tempDir) throws Exception {
        DiscoveryPromotionPipelineRunner.PipelineReport pipelineReport =
            new DiscoveryPromotionPipelineRunner().writeReport(tempDir.resolve("pipeline"));

        assertTrue(pipelineReport.promotionRecords().stream()
            .anyMatch(record -> "discovery-campaign-9".equals(record.sourceCampaign())),
            "pipeline must include campaign 9 promotion records");

        assertTrue(pipelineReport.campaignNine() != null,
            "pipeline report must include campaign 9 report");

        Path metricsPath = tempDir.resolve("pipeline").resolve("campaign-metrics.json");
        List<Map<String, Object>> metrics = new ObjectMapper()
            .readValue(metricsPath.toFile(), new TypeReference<>() {});
        assertTrue(metrics.stream().anyMatch(m -> "discovery-campaign-9".equals(m.get("campaign"))),
            "campaign-metrics.json must include campaign 9");

        assertTrue(Files.exists(
            tempDir.resolve("pipeline").resolve("discovery-campaign-9").resolve("discovery-campaign-9.json")));
        assertTrue(Files.exists(
            tempDir.resolve("pipeline").resolve("discovery-campaign-9").resolve("discovery-candidates.md")));
        assertTrue(Files.exists(
            tempDir.resolve("pipeline").resolve("discovery-campaign-9").resolve("operator-suggestions.md")));
        assertTrue(Files.exists(
            tempDir.resolve("pipeline").resolve("discovery-campaign-9").resolve("macro-candidates.md")));
    }

    @Test
    void campaignNineCandidatesReportContainsProvenanceFields(@TempDir Path tempDir) throws Exception {
        DiscoveryCampaignNineRunner runner = new DiscoveryCampaignNineRunner();
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
    void campaignNineCoversDistinctFamilies(@TempDir Path tempDir) throws Exception {
        DiscoveryCampaignNineRunner runner = new DiscoveryCampaignNineRunner();
        DiscoveryCampaignNineRunner.CampaignReport report = runner.writeReport(tempDir);

        Set<String> families = new HashSet<>();
        for (DiscoveryCampaignNineRunner.CaseResult result : report.results()) {
            families.add(result.family());
        }
        assertTrue(families.size() >= 2, "campaign 9 must cover at least 2 distinct families, found: " + families);
    }

    @Test
    void campaignNineCasesHaveOperatorProvenance(@TempDir Path tempDir) throws Exception {
        DiscoveryCampaignNineRunner runner = new DiscoveryCampaignNineRunner();
        DiscoveryCampaignNineRunner.CampaignReport report = runner.writeReport(tempDir);

        Set<String> expectedOperatorIds = Set.of(
            "trig_power_reduction",
            "exp_log_inverse",
            "log_product_assumption",
            "power_root_assumptions"
        );
        for (DiscoveryCampaignNineRunner.CaseResult result : report.results()) {
            if (result.success()) {
                assertFalse(result.shortcutOperatorId().isBlank(),
                    "successful result must have operator provenance: " + result.id());
                assertTrue(expectedOperatorIds.contains(result.shortcutOperatorId()),
                    "shortcutOperatorId '" + result.shortcutOperatorId()
                        + "' not in expected assumption-carrying operator set for: " + result.id());
            }
        }
    }

    private Set<String> priorCampaignCaseIds() {
        Set<String> ids = new HashSet<>();
        new DiscoveryCampaignOneRunner().run().results().stream().map(r -> r.id()).forEach(ids::add);
        new DiscoveryCampaignTwoRunner().run().results().stream().map(r -> r.id()).forEach(ids::add);
        new DiscoveryCampaignThreeRunner().run().results().stream().map(r -> r.id()).forEach(ids::add);
        new DiscoveryCampaignFiveRunner().run().results().stream().map(r -> r.id()).forEach(ids::add);
        new DiscoveryCampaignSevenRunner().run().results().stream().map(r -> r.id()).forEach(ids::add);
        new DiscoveryCampaignEightRunner().run().results().stream().map(r -> r.id()).forEach(ids::add);
        return ids;
    }
}
