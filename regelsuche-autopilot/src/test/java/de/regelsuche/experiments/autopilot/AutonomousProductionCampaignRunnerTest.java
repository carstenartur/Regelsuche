package de.regelsuche.experiments.autopilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.experiments.autopilot.AutonomousCandidateLifecycleV2.LifecycleOutcome;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.EvidenceStage;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.ResourceKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AutonomousProductionCampaignRunnerTest {
    private final AutonomousProductionCampaignRunner runner =
        new AutonomousProductionCampaignRunner();
    private AutonomousProductionCampaignRunner.CampaignRun sequential;
    private AutonomousProductionCampaignRunner.CampaignRun parallel;

    @BeforeAll
    void runPinnedCampaigns() {
        sequential = runner.runPinned(1);
        parallel = runner.runPinned(4);
    }

    @Test
    void completesFeedbackRoundAndDeterministicEmptyNextPlan() throws Exception {
        var run = parallel;

        assertEquals("COMPLETED", run.status());
        assertEquals(2, run.seedFamilyCount());
        assertEquals(12, run.observationCount());
        assertEquals(1, run.candidateCount());
        assertEquals(1, run.rejectedClusterCount());
        assertEquals(LifecycleOutcome.COMPLETED,
            run.lifecycle().lifecycleDecision().outcome());
        assertTrue(run.nextPlan().decisions().isEmpty());
        assertEquals("CAMPAIGN_COMPLETE", run.feedback().disposition());
        assertTrue(run.feedback().usesOnlyRetainedEvidence());
        assertTrue(run.feedback().eligibleBranchIds().isEmpty());
        assertEquals(0, run.feedback().nextDecisionCount());
        assertEquals(run.nextPlan().contentHash(), run.feedback().nextPlanHash());
        assertEquals(run.nextPlan().contentHash(), run.round().nextPlanHash());
        assertEquals(run.lifecycle().mining().plan().contentHash(),
            run.round().planHash());
        assertEquals(run.lifecycle().mining().fullBatch().execution().contentHash(),
            run.round().executionHash());
        assertEquals(run.lifecycle().mining().fullBatch().lineage().contentHash(),
            run.round().lineageHash());
        assertEquals(List.of(run.lifecycle().lifecycleDecision().contentHash()),
            run.round().lifecycleDecisionHashes());

        assertEquals(10, run.resourceLedger().entries().size());
        assertEquals(12L, run.resourceLedger().entry(
            EvidenceStage.GENERATION, ResourceKind.OBSERVATIONS).executed());
        assertEquals(2L, run.resourceLedger().entry(
            EvidenceStage.CANDIDATE_FORMATION,
            ResourceKind.MINING_BATCHES).executed());
        assertEquals(1L, run.resourceLedger().entry(
            EvidenceStage.CANDIDATE_FORMATION,
            ResourceKind.CANDIDATES).executed());
        assertEquals(6L, run.resourceLedger().entry(
            EvidenceStage.VALIDATION,
            ResourceKind.VALIDATION_CHECKS).executed());
        assertEquals(77L, run.resourceLedger().entry(
            EvidenceStage.COUNTEREXAMPLE_SEARCH,
            ResourceKind.COUNTEREXAMPLE_ATTEMPTS).executed());
        run.resourceLedger().entries().forEach(entry -> assertEquals(
            entry.configured(),
            entry.executed() + entry.skipped() + entry.remaining()));

        assertTrue(run.artifacts().size() >= 35);
        assertFalse(run.targetProvided());
        assertFalse(run.campaignCompletionIsMathematicalEvidence());
        assertFalse(run.externalNoveltyEvaluated());
        assertEquals("NOT_EVALUATED", run.promotionStatus());
        assertEquals("NOT_EVALUATED", run.publicEvidenceStatus());

        Path output = Path.of(
            "build", "reports", "autopilot-production-campaign");
        runner.write(output, run);
        for (String file : List.of(
                "production-lifecycle-run.json",
                "next-plan-v2.json",
                "campaign-round-v2.json",
                "feedback-reallocation.json",
                "campaign-resource-ledger.json",
                "production-campaign-manifest.json")) {
            Path artifact = output.resolve(file);
            assertTrue(Files.isRegularFile(artifact), file);
            assertTrue(Files.size(artifact) > 0L, file);
        }
    }

    @Test
    void completeCampaignEvidenceIsStableAcrossGenerationParallelism() {
        assertEquals(sequential.lifecycle().contentHash(),
            parallel.lifecycle().contentHash());
        assertEquals(sequential.nextPlan().contentHash(),
            parallel.nextPlan().contentHash());
        assertEquals(sequential.round().contentHash(),
            parallel.round().contentHash());
        assertEquals(sequential.feedback().contentHash(),
            parallel.feedback().contentHash());
        assertEquals(sequential.resourceLedger().contentHash(),
            parallel.resourceLedger().contentHash());
        assertEquals(sequential.artifacts(), parallel.artifacts());
        assertEquals(sequential.contentHash(), parallel.contentHash());
    }
}
