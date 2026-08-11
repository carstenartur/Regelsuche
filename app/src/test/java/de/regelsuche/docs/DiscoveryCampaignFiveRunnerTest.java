package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(DiscoveryPromotionPipelineFixtureExtension.class)
class DiscoveryCampaignFiveRunnerTest {
    private static final Set<String> PRIOR_CAMPAIGNS = Set.of(
        "discovery-campaign-1",
        "discovery-campaign-2",
        "discovery-campaign-3"
    );

    @Test
    void campaignFiveStressSuiteIsUniqueReportedAndPipelineIntegrated(
        DiscoveryPromotionPipelineFixture fixture
    ) throws Exception {
        DiscoveryCampaignFiveRunner.CampaignReport report =
            fixture.report().campaignFive();
        Path campaignDirectory = fixture.campaignDirectory(
            "discovery-campaign-5"
        );
        Map<String, DiscoveryCampaignFiveRunner.CaseResult> resultsById =
            report.results().stream().collect(Collectors.toMap(
                DiscoveryCampaignFiveRunner.CaseResult::id,
                Function.identity()
            ));

        assertTrue(
            report.results().size() >= 20,
            "campaign 5 should contain at least 20 cases"
        );
        assertTrue(
            report.results().size() <= 30,
            "campaign 5 should stay in curated 20-30 range"
        );

        Set<String> ids = new HashSet<>();
        for (DiscoveryCampaignFiveRunner.CaseResult result : report.results()) {
            assertTrue(
                ids.add(result.id()),
                "duplicate campaign 5 id: " + result.id()
            );
        }

        Set<String> existingPairs =
            fixture.inputTargetPairsFromCampaigns(PRIOR_CAMPAIGNS);
        for (DiscoveryCampaignFiveRunner.CaseResult result : report.results()) {
            String pair = DiscoveryPromotionPipelineFixture.inputTargetPair(
                result.inputExpression(),
                result.targetExpression()
            );
            assertFalse(
                existingPairs.contains(pair),
                "duplicate pair from campaign 1-3: " + pair
            );
        }

        assertTrue(report.results().stream().anyMatch(result ->
            result.shortcutAssumptions().stream().anyMatch(assumption ->
                assumption.startsWith("substitution."))),
            "expected at least one case with substitution evidence on selected shortcut edge");

        DiscoveryCampaignFiveRunner.CaseResult completeSquarePlusRest =
            resultsById.get("b-complete-square-plus-rest");
        assertTrue(completeSquarePlusRest.shortcutAssumptions().stream()
            .anyMatch(assumption -> assumption.startsWith(
                "substitution.placeholder."
            )), completeSquarePlusRest.shortcutAssumptions().toString());
        assertTrue(completeSquarePlusRest.shortcutAssumptions().stream()
            .anyMatch(assumption -> assumption.startsWith(
                "substitution.occurrences."
            )), completeSquarePlusRest.shortcutAssumptions().toString());
        assertTrue(completeSquarePlusRest.shortcutAssumptions().stream()
            .anyMatch(assumption -> assumption.startsWith(
                "substitution.substituted"
            )), completeSquarePlusRest.shortcutAssumptions().toString());

        assertTrue(
            report.results().stream().anyMatch(result ->
                result.rulePath().size() >= 2),
            "expected at least one case with multi-step rule path"
        );
        assertTrue(report.results().stream().anyMatch(result ->
            result.promotionStage().atLeast(PromotionStage.PROMOTED)
                || result.promotionStage().atLeast(PromotionStage.VALIDATED)));

        assertTrue(Files.exists(
            campaignDirectory.resolve("discovery-campaign-5.json")
        ));
        assertTrue(Files.exists(
            campaignDirectory.resolve("hidden-structure-report.md")
        ));

        DiscoveryPromotionPipelineRunner.PipelineReport pipelineReport =
            fixture.report();
        assertTrue(pipelineReport.promotionRecords().stream()
            .anyMatch(record -> "discovery-campaign-5".equals(
                record.sourceCampaign()
            )));

        String metrics = Files.readString(
            fixture.outputDirectory().resolve("campaign-metrics.json"),
            StandardCharsets.UTF_8
        );
        assertTrue(metrics.contains(
            "\"campaign\" : \"discovery-campaign-5\""
        ));

        String gallery = Files.readString(
            fixture.outputDirectory().resolve("gallery-2.0.md"),
            StandardCharsets.UTF_8
        );
        List<String> galleryCandidates = gallery.lines()
            .filter(line -> line.startsWith("| ")
                && !line.contains("Candidate | Stage")
                && !line.contains("---"))
            .map(line -> line.split("\\|")[1].trim())
            .toList();
        Set<String> acceptedIds = new PublicEvidenceGate()
            .evaluate(pipelineReport.promotionRecords())
            .accepted()
            .stream()
            .map(PublicEvidenceGate.GateDecision::candidateId)
            .collect(Collectors.toSet());
        for (String candidateId : galleryCandidates) {
            assertTrue(acceptedIds.contains(candidateId), candidateId);
        }
    }

    @Test
    void campaignFiveWritesMoveTreeReportWithRewriteMoves(
        DiscoveryPromotionPipelineFixture fixture
    ) throws Exception {
        Path campaignDirectory = fixture.campaignDirectory(
            "discovery-campaign-5"
        );
        assertTrue(Files.exists(
            campaignDirectory.resolve("move-tree-report.json")
        ));
        assertTrue(Files.exists(
            campaignDirectory.resolve("move-tree-report.md")
        ));

        String moveTreeJson = Files.readString(
            campaignDirectory.resolve("move-tree-report.json"),
            StandardCharsets.UTF_8
        );
        assertTrue(moveTreeJson.contains("rewriteMove"), moveTreeJson);

        DiscoveryCampaignFiveRunner runner = new DiscoveryCampaignFiveRunner();
        de.regelsuche.moves.report.MoveTreeReport moveTree =
            runner.buildMoveTreeReport();
        assertFalse(
            moveTree.successfulPathMoves().isEmpty(),
            "successful campaign-5 path should contain rewrite moves"
        );
        for (de.regelsuche.moves.RewriteMove move
                : moveTree.successfulPathMoves()) {
            assertFalse(move.sourceExpression().isBlank());
            assertFalse(move.targetExpression().isBlank());
            assertFalse(move.canonicalBefore().isBlank());
            assertFalse(move.canonicalAfter().isBlank());
        }
        assertTrue(moveTree.successfulPathMoves().stream().anyMatch(move ->
                !move.operatorId().isBlank() || !move.assumptions().isEmpty()),
            "expected successful-path moves to retain edge metadata");
    }
}
