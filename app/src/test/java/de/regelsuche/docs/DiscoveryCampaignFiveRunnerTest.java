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
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscoveryCampaignFiveRunnerTest {
    @Test
    void campaignFiveStressSuiteIsUniqueReportedAndPipelineIntegrated(@TempDir Path tempDir) throws Exception {
        DiscoveryCampaignFiveRunner runner = new DiscoveryCampaignFiveRunner();

        DiscoveryCampaignFiveRunner.CampaignReport report = runner.writeReport(tempDir);
        Map<String, DiscoveryCampaignFiveRunner.CaseResult> resultsById = report.results().stream()
            .collect(Collectors.toMap(DiscoveryCampaignFiveRunner.CaseResult::id, Function.identity()));

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

        DiscoveryCampaignFiveRunner.CaseResult completeSquarePlusRest = resultsById.get("b-complete-square-plus-rest");
        assertTrue(completeSquarePlusRest.shortcutAssumptions().stream()
            .anyMatch(assumption -> assumption.startsWith("substitution.placeholder.")), completeSquarePlusRest.shortcutAssumptions().toString());
        assertTrue(completeSquarePlusRest.shortcutAssumptions().stream()
            .anyMatch(assumption -> assumption.startsWith("substitution.occurrences.")), completeSquarePlusRest.shortcutAssumptions().toString());
        assertTrue(completeSquarePlusRest.shortcutAssumptions().stream()
            .anyMatch(assumption -> assumption.startsWith("substitution.substituted")), completeSquarePlusRest.shortcutAssumptions().toString());

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

    @Test
    void campaignFiveWritesMoveTreeReportWithRewriteMoves(@TempDir Path tempDir) throws Exception {
        DiscoveryCampaignFiveRunner runner = new DiscoveryCampaignFiveRunner();
        runner.writeReport(tempDir);

        assertTrue(Files.exists(tempDir.resolve("move-tree-report.json")));
        assertTrue(Files.exists(tempDir.resolve("move-tree-report.md")));

        String moveTreeJson = Files.readString(tempDir.resolve("move-tree-report.json"), StandardCharsets.UTF_8);
        assertTrue(moveTreeJson.contains("rewriteMove"), moveTreeJson);

        de.regelsuche.moves.report.MoveTreeReport moveTree = runner.buildMoveTreeReport();
        assertFalse(moveTree.successfulPathMoves().isEmpty(),
            "successful campaign-5 path should contain rewrite moves");
        for (de.regelsuche.moves.RewriteMove move : moveTree.successfulPathMoves()) {
            assertFalse(move.sourceExpression().isBlank());
            assertFalse(move.targetExpression().isBlank());
            assertFalse(move.canonicalBefore().isBlank());
            assertFalse(move.canonicalAfter().isBlank());
        }
        assertTrue(moveTree.successfulPathMoves().stream().anyMatch(move ->
                !move.operatorId().isBlank() || !move.assumptions().isEmpty()),
            "expected successful-path moves to retain edge metadata");
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
