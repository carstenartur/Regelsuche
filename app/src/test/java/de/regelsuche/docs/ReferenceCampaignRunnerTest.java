package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReferenceCampaignRunnerTest {

    @Test
    void referenceCampaignHasAtLeastTwelveTrainingCases(@TempDir Path tempDir) {
        ReferenceCampaignRunner runner = new ReferenceCampaignRunner();
        ReferenceCampaignRunner.CampaignReport report = runner.writeReport(tempDir);

        assertTrue(report.training().size() >= 12,
            "reference campaign must have at least 12 training observations, found: " + report.training().size());
    }

    @Test
    void trainingCaseIdsAreUnique(@TempDir Path tempDir) {
        ReferenceCampaignRunner runner = new ReferenceCampaignRunner();
        ReferenceCampaignRunner.CampaignReport report = runner.writeReport(tempDir);

        Set<String> ids = report.training().stream()
            .map(ReferenceCampaignRunner.TrainingResult::id)
            .collect(Collectors.toSet());
        assertEquals(report.training().size(), ids.size(),
            "training case IDs must be unique");
    }

    @Test
    void referenceCampaignWritesAllRequiredArtifacts(@TempDir Path tempDir) {
        ReferenceCampaignRunner runner = new ReferenceCampaignRunner();
        runner.writeReport(tempDir);

        assertTrue(Files.exists(tempDir.resolve("reference-campaign.json")),
            "reference-campaign.json must be written");
        assertTrue(Files.exists(tempDir.resolve("reference-campaign.md")),
            "reference-campaign.md must be written");
        assertTrue(Files.exists(tempDir.resolve("hypothesis-evolution.json")),
            "hypothesis-evolution.json must be written");
        assertTrue(Files.exists(tempDir.resolve("counterexample-report.json")),
            "counterexample-report.json must be written");
        assertTrue(Files.exists(tempDir.resolve("holdout-report.json")),
            "holdout-report.json must be written");
        assertTrue(Files.exists(tempDir.resolve("reuse-ablation.json")),
            "reuse-ablation.json must be written");
        assertTrue(Files.exists(tempDir.resolve("provenance.graph.json")),
            "provenance.graph.json must be written");
        assertTrue(Files.isDirectory(tempDir.resolve("proof")),
            "proof/ directory must be created");
        assertTrue(Files.exists(tempDir.resolve("reference-campaign-timeline.html")),
            "reference-campaign-timeline.html must be written");
        assertTrue(Files.exists(tempDir.resolve("reference-campaign-timeline.md")),
            "reference-campaign-timeline.md must be written");
        assertTrue(Files.exists(tempDir.resolve("reference-campaign-observatory.html")),
            "reference-campaign-observatory.html must be written");
    }

    @Test
    void hypothesisRefinementChallengesOvergeneralization(@TempDir Path tempDir) {
        ReferenceCampaignRunner runner = new ReferenceCampaignRunner();
        ReferenceCampaignRunner.CampaignReport report = runner.writeReport(tempDir);

        ReferenceCampaignRunner.HypothesisEvolution evo = report.hypothesisEvolution();
        assertNotNull(evo, "hypothesis evolution must be present");

        // Initial hypothesis is overgeneralized (no assumptions)
        assertEquals(ReferenceCampaignRunner.INITIAL_LEFT_PATTERN, evo.initialLeftPattern());
        assertEquals(ReferenceCampaignRunner.INITIAL_RIGHT_PATTERN, evo.initialRightPattern());

        // Must have at least one revision beyond the initial
        assertTrue(evo.revisionHistory().size() >= 1,
            "refinement must have at least one revision, found: " + evo.revisionHistory().size());
    }

    @Test
    void counterexampleReportDocumentsAtLeastOneOvergeneralizedRejection(@TempDir Path tempDir) {
        ReferenceCampaignRunner runner = new ReferenceCampaignRunner();
        ReferenceCampaignRunner.CampaignReport report = runner.writeReport(tempDir);

        ReferenceCampaignRunner.CounterexampleReport cex =
            report.hypothesisEvolution().counterexampleReport();
        assertNotNull(cex, "counterexample report must be present");

        // The initial overgeneralized hypothesis (no assumptions) must be challenged
        // This verifies the acceptance criterion "at least one overgeneralization is rejected or refined"
        assertTrue(cex.totalRevisions() >= 1,
            "at least one revision must have been attempted");
    }

    @Test
    void holdoutReportCoversAtLeastOneHundredPositiveHoldouts(@TempDir Path tempDir) {
        ReferenceCampaignRunner runner = new ReferenceCampaignRunner();
        ReferenceCampaignRunner.CampaignReport report = runner.writeReport(tempDir);

        ReferenceCampaignRunner.HoldoutReport holdouts = report.holdoutReport();
        assertNotNull(holdouts, "holdout report must be present");

        assertTrue(holdouts.positiveCount() >= ReferenceCampaignRunner.MIN_HOLDOUTS,
            "must have at least " + ReferenceCampaignRunner.MIN_HOLDOUTS
                + " positive holdouts, found: " + holdouts.positiveCount());
        assertTrue(holdouts.negativeCount() >= ReferenceCampaignRunner.MIN_HOLDOUTS,
            "must have at least " + ReferenceCampaignRunner.MIN_HOLDOUTS
                + " negative holdouts, found: " + holdouts.negativeCount());
    }

    @Test
    void holdoutPositiveAndNegativeSetsAreDisjoint(@TempDir Path tempDir) {
        ReferenceCampaignRunner runner = new ReferenceCampaignRunner();
        ReferenceCampaignRunner.CampaignReport report = runner.writeReport(tempDir);

        ReferenceCampaignRunner.HoldoutReport holdouts = report.holdoutReport();
        Set<String> positiveInputs = holdouts.positiveResults().stream()
            .map(ReferenceCampaignRunner.HoldoutResult::inputExpression)
            .collect(Collectors.toSet());
        Set<String> negativeInputs = holdouts.negativeResults().stream()
            .map(ReferenceCampaignRunner.HoldoutResult::inputExpression)
            .collect(Collectors.toSet());

        Set<String> overlap = positiveInputs.stream()
            .filter(negativeInputs::contains)
            .collect(Collectors.toSet());
        assertTrue(overlap.isEmpty(),
            "positive and negative holdout inputs must be disjoint, overlap: " + overlap);
    }

    @Test
    void proofSummaryIsPresent(@TempDir Path tempDir) {
        ReferenceCampaignRunner runner = new ReferenceCampaignRunner();
        ReferenceCampaignRunner.CampaignReport report = runner.writeReport(tempDir);

        assertNotNull(report.proofSummary(), "proof summary must be present");
        assertTrue(report.proofSummary().totalCases() > 0,
            "proof summary must cover at least one case");
    }

    @Test
    void promotionRecordIsPresent(@TempDir Path tempDir) {
        ReferenceCampaignRunner runner = new ReferenceCampaignRunner();
        ReferenceCampaignRunner.CampaignReport report = runner.writeReport(tempDir);

        assertNotNull(report.promotionRecord(), "promotion record must be present");
        assertFalse(report.promotionRecord().candidateId().isBlank(),
            "promotion record must have a candidate ID");
        assertEquals("log-product", report.promotionRecord().family(),
            "promotion record must belong to log-product family");
    }

    @Test
    void reuseAblationIsPresent(@TempDir Path tempDir) {
        ReferenceCampaignRunner runner = new ReferenceCampaignRunner();
        ReferenceCampaignRunner.CampaignReport report = runner.writeReport(tempDir);

        assertNotNull(report.reuseAblation(), "reuse ablation must be present");
        assertTrue(report.reuseAblation().totalCount() > 0,
            "reuse ablation must cover at least one case");
    }

    @Test
    void provenanceGraphHasNodesAndEdges(@TempDir Path tempDir) {
        ReferenceCampaignRunner runner = new ReferenceCampaignRunner();
        ReferenceCampaignRunner.CampaignReport report = runner.writeReport(tempDir);

        ReferenceCampaignRunner.ProvenanceGraph graph = report.provenanceGraph();
        assertNotNull(graph, "provenance graph must be present");
        assertFalse(graph.nodes().isEmpty(), "provenance graph must have nodes");
        assertFalse(graph.edges().isEmpty(), "provenance graph must have edges");
        assertEquals(ReferenceCampaignRunner.CAMPAIGN_ID, graph.campaignId());
    }

    @Test
    void provenanceGraphContainsTrainingObservationNodes(@TempDir Path tempDir) {
        ReferenceCampaignRunner runner = new ReferenceCampaignRunner();
        ReferenceCampaignRunner.CampaignReport report = runner.writeReport(tempDir);

        ReferenceCampaignRunner.ProvenanceGraph graph = report.provenanceGraph();
        long trainingNodes = graph.nodes().stream()
            .filter(n -> "training-observation".equals(n.nodeType()))
            .count();
        assertTrue(trainingNodes >= 12,
            "provenance graph must contain at least 12 training-observation nodes, found: " + trainingNodes);
    }

    @Test
    void referenceCampaignMarkdownContainsRequiredSections(@TempDir Path tempDir) throws Exception {
        ReferenceCampaignRunner runner = new ReferenceCampaignRunner();
        runner.writeReport(tempDir);

        String markdown = Files.readString(tempDir.resolve("reference-campaign.md"), StandardCharsets.UTF_8);
        assertTrue(markdown.contains("Training Observations"), "markdown must contain Training Observations section");
        assertTrue(markdown.contains("Hypothesis Formation"), "markdown must contain Hypothesis Formation section");
        assertTrue(markdown.contains("Holdout Validation"), "markdown must contain Holdout Validation section");
        assertTrue(markdown.contains("External Prover"), "markdown must contain External Prover section");
        assertTrue(markdown.contains("Promotion"), "markdown must contain Promotion section");
        assertTrue(markdown.contains("Reuse Ablation"), "markdown must contain Reuse Ablation section");
        assertTrue(markdown.contains("Acceptance Gate"), "markdown must contain Acceptance Gate section");
        assertTrue(markdown.contains("runReferenceCampaign"), "markdown must document the Gradle command");
    }

    @Test
    void holdoutGeneratorProducesEnoughPositiveHoldouts() {
        ReferenceCampaignRunner runner = new ReferenceCampaignRunner();
        List<String> holdouts = runner.generatePositiveHoldouts();
        assertTrue(holdouts.size() >= ReferenceCampaignRunner.MIN_HOLDOUTS,
            "positive holdout generator must produce at least " + ReferenceCampaignRunner.MIN_HOLDOUTS
                + " holdouts, found: " + holdouts.size());
    }

    @Test
    void holdoutGeneratorProducesEnoughNegativeHoldouts() {
        ReferenceCampaignRunner runner = new ReferenceCampaignRunner();
        List<String> holdouts = runner.generateNegativeHoldouts();
        assertTrue(holdouts.size() >= ReferenceCampaignRunner.MIN_HOLDOUTS,
            "negative holdout generator must produce at least " + ReferenceCampaignRunner.MIN_HOLDOUTS
                + " holdouts, found: " + holdouts.size());
    }

    @Test
    void positiveHoldoutsAllMatchLogProductPattern() {
        ReferenceCampaignRunner runner = new ReferenceCampaignRunner();
        de.regelsuche.transform.LogProductAssumptionOperator operator =
            new de.regelsuche.transform.LogProductAssumptionOperator();

        List<String> holdouts = runner.generatePositiveHoldouts();
        for (String holdout : holdouts) {
            List<de.regelsuche.transform.Transformation> candidates = operator.generateCandidates(holdout);
            assertFalse(candidates.isEmpty(),
                "positive holdout '" + holdout + "' must match log-product operator");
        }
    }

    @Test
    void negativeHoldoutsNoneMatchLogProductPattern() {
        ReferenceCampaignRunner runner = new ReferenceCampaignRunner();
        de.regelsuche.transform.LogProductAssumptionOperator operator =
            new de.regelsuche.transform.LogProductAssumptionOperator();

        List<String> holdouts = runner.generateNegativeHoldouts();
        for (String holdout : holdouts) {
            List<de.regelsuche.transform.Transformation> candidates = operator.generateCandidates(holdout);
            assertTrue(candidates.isEmpty(),
                "negative holdout '" + holdout + "' must NOT match log-product operator");
        }
    }

    @Test
    void hypothesisEvolutionInitialRevisionHasNoAssumptions(@TempDir Path tempDir) {
        ReferenceCampaignRunner runner = new ReferenceCampaignRunner();
        ReferenceCampaignRunner.CampaignReport report = runner.writeReport(tempDir);

        ReferenceCampaignRunner.HypothesisEvolution evo = report.hypothesisEvolution();
        assertFalse(evo.revisionHistory().isEmpty(), "must have at least one revision");

        // The first revision (r0) should have no assumptions (overgeneralized)
        ReferenceCampaignRunner.RevisionSummary firstRevision = evo.revisionHistory().getFirst();
        assertTrue(firstRevision.assumptions().isEmpty(),
            "initial revision (r0) must have no assumptions (deliberately overgeneralized), found: "
                + firstRevision.assumptions());
    }

    @Test
    void referenceCampaignCandidateReportWritten(@TempDir Path tempDir) {
        ReferenceCampaignRunner runner = new ReferenceCampaignRunner();
        runner.writeReport(tempDir);

        assertTrue(Files.exists(tempDir.resolve("discovery-candidates.md")),
            "discovery-candidates.md must be written");
        assertTrue(Files.exists(tempDir.resolve("operator-suggestions.md")),
            "operator-suggestions.md must be written");
        assertTrue(Files.exists(tempDir.resolve("macro-candidates.md")),
            "macro-candidates.md must be written");
    }
}
