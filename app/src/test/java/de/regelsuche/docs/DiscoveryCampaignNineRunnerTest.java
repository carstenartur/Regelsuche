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
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(DiscoveryPromotionPipelineFixtureExtension.class)
class DiscoveryCampaignNineRunnerTest {
    private static final List<String> PRIOR_CAMPAIGNS = List.of(
        "discovery-campaign-1",
        "discovery-campaign-2",
        "discovery-campaign-3",
        "discovery-campaign-5",
        "discovery-campaign-7",
        "discovery-campaign-8"
    );

    @Test
    void campaignNineCasesAreUniqueAndNonEmpty(
        DiscoveryPromotionPipelineFixture fixture
    ) {
        DiscoveryCampaignNineRunner.CampaignReport report =
            fixture.report().campaignNine();

        assertFalse(
            report.results().isEmpty(),
            "campaign 9 must have at least one case"
        );

        Set<String> ids = new HashSet<>();
        for (DiscoveryCampaignNineRunner.CaseResult result : report.results()) {
            assertTrue(
                ids.add(result.id()),
                "duplicate campaign 9 id: " + result.id()
            );
        }

        Set<String> priorCaseIds = fixture.candidateIdsFromCampaigns(
            Set.copyOf(PRIOR_CAMPAIGNS)
        );
        for (DiscoveryCampaignNineRunner.CaseResult result : report.results()) {
            assertFalse(
                priorCaseIds.contains(result.id()),
                "campaign 9 case id duplicates a prior campaign case id: "
                    + result.id()
            );
        }
    }

    @Test
    void campaignNineNoveltyCheckerDetectsAlphaEquivalentPriorCases(
        DiscoveryPromotionPipelineFixture fixture
    ) {
        DiscoveryCampaignNineRunner.CampaignReport report =
            fixture.report().campaignNine();
        List<NoveltyChecker.Candidate> priorCandidates =
            fixture.noveltyCandidatesFromCampaigns(PRIOR_CAMPAIGNS);
        List<NoveltyChecker.Candidate> allCandidates =
            new ArrayList<>(priorCandidates);
        report.results().stream()
            .map(this::candidate)
            .forEach(allCandidates::add);
        List<NoveltyChecker.NoveltyResult> noveltyResults =
            new NoveltyChecker().classifyAll(allCandidates);
        List<NoveltyChecker.NoveltyResult> campaignNineNovelty =
            noveltyResults.subList(
                priorCandidates.size(),
                noveltyResults.size()
            );

        assertTrue(campaignNineNovelty.stream().anyMatch(result ->
                result.status() == NoveltyStatus.ALPHA_EQUIVALENT),
            "campaign 9 validates independent bindings and should be recognized as alpha-equivalent support, not new discovery");
        for (NoveltyChecker.NoveltyResult result : campaignNineNovelty) {
            assertFalse(
                result.status() == NoveltyStatus.DUPLICATE,
                "campaign 9 should not repeat exact normalized input/target pairs: "
                    + result
            );
            assertFalse(
                result.status() == NoveltyStatus.UNKNOWN,
                "campaign 9 candidates must have enough novelty input data: "
                    + result
            );
        }
    }

    @Test
    void campaignNineWritesCandidateReports(
        DiscoveryPromotionPipelineFixture fixture,
        @TempDir Path tempDir
    ) throws Exception {
        new DiscoveryCampaignNineRunner().writeReport(
            tempDir,
            fixture.report().campaignNine()
        );

        assertReproducedFiles(
            fixture.campaignDirectory("discovery-campaign-9"),
            tempDir,
            List.of(
                "discovery-campaign-9.json",
                "campaign-progress.md",
                "discovery-candidates.md",
                "operator-suggestions.md",
                "macro-candidates.md"
            )
        );
    }

    @Test
    void campaignNineProgressReportComparesThreeCampaigns(
        DiscoveryPromotionPipelineFixture fixture
    ) throws Exception {
        DiscoveryCampaignNineRunner.CampaignReport report =
            fixture.report().campaignNine();
        Path campaignDirectory = fixture.campaignDirectory(
            "discovery-campaign-9"
        );

        assertFalse(
            report.progress().isEmpty(),
            "progress summaries must not be empty"
        );
        Set<String> progressIds = new HashSet<>();
        for (DiscoveryCampaignNineRunner.ProgressSummary summary
                : report.progress()) {
            assertTrue(
                progressIds.add(summary.campaignId()),
                "duplicate progress entry: " + summary.campaignId()
            );
        }
        assertTrue(
            progressIds.contains("discovery-campaign-7"),
            "progress must include campaign 7"
        );
        assertTrue(
            progressIds.contains("discovery-campaign-8"),
            "progress must include campaign 8"
        );
        assertTrue(
            progressIds.contains("discovery-campaign-9"),
            "progress must include campaign 9"
        );

        String progressMarkdown = Files.readString(
            campaignDirectory.resolve("campaign-progress.md"),
            StandardCharsets.UTF_8
        );
        assertTrue(progressMarkdown.contains("discovery-campaign-7"));
        assertTrue(progressMarkdown.contains("discovery-campaign-8"));
        assertTrue(progressMarkdown.contains("discovery-campaign-9"));
        assertTrue(
            progressMarkdown.contains("Promotion-ready"),
            "progress table must use 'Promotion-ready' column header"
        );
        assertTrue(
            progressMarkdown.contains("Promotion-ready** means"),
            "progress table must include definition note"
        );
    }

    @Test
    void campaignNineIsIntegratedIntoPromotionPipeline(
        DiscoveryPromotionPipelineFixture fixture
    ) throws Exception {
        DiscoveryPromotionPipelineRunner.PipelineReport pipelineReport =
            fixture.report();

        assertTrue(pipelineReport.promotionRecords().stream()
            .anyMatch(record -> "discovery-campaign-9".equals(
                record.sourceCampaign()
            )), "pipeline must include campaign 9 promotion records");
        assertTrue(
            pipelineReport.campaignNine() != null,
            "pipeline report must include campaign 9 report"
        );

        Path metricsPath = fixture.outputDirectory().resolve(
            "campaign-metrics.json"
        );
        List<Map<String, Object>> metrics = new ObjectMapper().readValue(
            metricsPath.toFile(),
            new TypeReference<>() { }
        );
        assertTrue(metrics.stream().anyMatch(metric ->
            "discovery-campaign-9".equals(metric.get("campaign"))),
            "campaign-metrics.json must include campaign 9");

        Path campaignDirectory = fixture.campaignDirectory(
            "discovery-campaign-9"
        );
        assertTrue(Files.exists(
            campaignDirectory.resolve("discovery-campaign-9.json")
        ));
        assertTrue(Files.exists(
            campaignDirectory.resolve("discovery-candidates.md")
        ));
        assertTrue(Files.exists(
            campaignDirectory.resolve("operator-suggestions.md")
        ));
        assertTrue(Files.exists(
            campaignDirectory.resolve("macro-candidates.md")
        ));
    }

    @Test
    void campaignNineCandidatesReportContainsProvenanceFields(
        DiscoveryPromotionPipelineFixture fixture
    ) throws Exception {
        String candidatesMarkdown = Files.readString(
            fixture.campaignDirectory("discovery-campaign-9")
                .resolve("discovery-candidates.md"),
            StandardCharsets.UTF_8
        );
        assertTrue(
            candidatesMarkdown.contains("| Candidate |"),
            "must have Candidate column"
        );
        assertTrue(
            candidatesMarkdown.contains("| Family |"),
            "must have Family column"
        );
        assertTrue(
            candidatesMarkdown.contains("| Stage |"),
            "must have Stage column"
        );
        assertTrue(
            candidatesMarkdown.contains("| Novelty |"),
            "must have Novelty column"
        );
        assertTrue(
            candidatesMarkdown.contains("| Oracle |"),
            "must have Oracle column"
        );
        assertTrue(
            candidatesMarkdown.contains("| Ablation |"),
            "must have Ablation column"
        );
        assertTrue(
            candidatesMarkdown.contains("| Source |"),
            "must have Source column"
        );
        assertTrue(
            candidatesMarkdown.contains("| Pack |"),
            "must have Pack column"
        );
        assertTrue(
            candidatesMarkdown.contains("| Operator |"),
            "must have Operator column"
        );
    }

    @Test
    void campaignNineCoversDistinctFamilies(
        DiscoveryPromotionPipelineFixture fixture
    ) {
        Set<String> families = new HashSet<>();
        for (DiscoveryCampaignNineRunner.CaseResult result
                : fixture.report().campaignNine().results()) {
            families.add(result.family());
        }
        assertTrue(
            families.size() >= 2,
            "campaign 9 must cover at least 2 distinct families, found: "
                + families
        );
    }

    @Test
    void campaignNineCasesHaveOperatorProvenance(
        DiscoveryPromotionPipelineFixture fixture
    ) {
        Set<String> expectedOperatorIds = Set.of(
            "trig_power_reduction",
            "exp_log_inverse",
            "log_product_assumption",
            "power_root_assumptions"
        );
        for (DiscoveryCampaignNineRunner.CaseResult result
                : fixture.report().campaignNine().results()) {
            if (result.success()) {
                assertFalse(
                    result.shortcutOperatorId().isBlank(),
                    "successful result must have operator provenance: "
                        + result.id()
                );
                assertTrue(
                    expectedOperatorIds.contains(result.shortcutOperatorId()),
                    "shortcutOperatorId '" + result.shortcutOperatorId()
                        + "' not in expected assumption-carrying operator set for: "
                        + result.id()
                );
            }
        }
    }

    private void assertReproducedFiles(
        Path retainedDirectory,
        Path reproducedDirectory,
        List<String> fileNames
    ) throws Exception {
        for (String fileName : fileNames) {
            Path expected = retainedDirectory.resolve(fileName);
            Path actual = reproducedDirectory.resolve(fileName);
            assertTrue(Files.isRegularFile(actual), fileName);
            assertTrue(
                Files.mismatch(expected, actual) == -1L,
                fileName + " must reproduce retained fixture bytes"
            );
        }
    }

    private NoveltyChecker.Candidate candidate(
        DiscoveryCampaignNineRunner.CaseResult result
    ) {
        return new NoveltyChecker.Candidate(
            result.id(),
            result.family(),
            result.inputExpression(),
            result.targetExpression(),
            result.shortcutOperatorId(),
            result.rulePath()
        );
    }
}
