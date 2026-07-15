package de.regelsuche.experiments.autopilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.CandidateDraft;
import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.RejectedCluster;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.ResourceKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AutonomousCampaignArtifactsV2Test {
    @Test
    void retainsCanonicalPlanExecutionLineageRoundAndNextPlanArtifacts()
        throws Exception {
        var brief = AutonomousEvidenceDagV2Fixtures.brief();
        var observations = AutonomousEvidenceDagV2Fixtures.observations();
        var decision = AutonomousEvidenceDagV2Fixtures.decision(brief, observations);
        var receipt = AutonomousEvidenceDagV2.completeCandidateFormation(
            decision,
            AutonomousEvidenceDagV2Fixtures.hash("campaign-artifact-mining"),
            List.of(new CandidateDraft(
                "conjecture-artifact",
                AutonomousEvidenceDagV2Fixtures.hash("campaign-artifact-candidate"),
                List.of("observation-a", "observation-b"))),
            List.of(RejectedCluster.create(
                AutonomousEvidenceDagV2Fixtures.hash("campaign-artifact-rejected"),
                "INSUFFICIENT_SUPPORT",
                List.of("observation-c"))),
            Map.of(
                ResourceKind.MINING_BATCHES, 1L,
                ResourceKind.CANDIDATES, 1L),
            Map.of());

        var plan = AutonomousCampaignArtifactsV2.plan(brief, List.of(decision));
        var execution = AutonomousCampaignArtifactsV2.execution(plan, receipt);
        var lineage = AutonomousCampaignArtifactsV2.lineage(receipt);
        var nextPlan = AutonomousCampaignArtifactsV2.plan(brief, List.of());
        var round = AutonomousCampaignArtifactsV2.round(
            plan, execution, lineage, List.of(), nextPlan);

        assertEquals(AutonomousCampaignArtifactsV2.PLAN_SCHEMA, plan.schema());
        assertEquals(AutonomousCampaignArtifactsV2.EXECUTION_SCHEMA,
            execution.schema());
        assertEquals(AutonomousCampaignArtifactsV2.LINEAGE_SCHEMA,
            lineage.schema());
        assertEquals(AutonomousCampaignArtifactsV2.ROUND_SCHEMA, round.schema());
        assertEquals(receipt.miningEvidenceHash(), execution.miningEvidenceHash());
        assertEquals(receipt.miningEvidenceHash(), lineage.miningEvidenceHash());
        assertEquals(1, lineage.candidates().size());
        assertEquals(
            List.of("observation-a", "observation-b"),
            lineage.candidates().getFirst().sources().stream()
                .map(AutonomousEvidenceDagV2.SourceLink::observationId)
                .toList());
        assertEquals(nextPlan.contentHash(), round.nextPlanHash());
        assertFalse(plan.plannerDecisionIsMathematicalEvidence());
        assertFalse(execution.executionIsMathematicalEvidence());
        assertFalse(lineage.lineageIsMathematicalEvidence());
        assertFalse(round.roundDecisionIsMathematicalEvidence());
        assertEquals("NOT_EVALUATED", round.promotionStatus());
        assertEquals("NOT_EVALUATED", round.publicEvidenceStatus());

        Path directory = Path.of("build", "reports", "autopilot-v2-dag");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("plan-v2.json"), plan.toCanonicalJson());
        Files.writeString(
            directory.resolve("execution-v2.json"), execution.toCanonicalJson());
        Files.writeString(
            directory.resolve("lineage-v2.json"), lineage.toCanonicalJson());
        Files.writeString(directory.resolve("round-v2.json"), round.toCanonicalJson());
        Files.writeString(
            directory.resolve("next-plan-v2.json"), nextPlan.toCanonicalJson());

        assertTrue(Files.size(directory.resolve("plan-v2.json")) > 0L);
        assertTrue(Files.size(directory.resolve("execution-v2.json")) > 0L);
        assertTrue(Files.size(directory.resolve("lineage-v2.json")) > 0L);
        assertTrue(Files.size(directory.resolve("round-v2.json")) > 0L);
        assertTrue(Files.size(directory.resolve("next-plan-v2.json")) > 0L);
    }

    @Test
    void remainsStableUnderObservationAndDecisionPermutation() {
        var brief = AutonomousEvidenceDagV2Fixtures.brief();
        var originalObservations = AutonomousEvidenceDagV2Fixtures.observations();
        var reversedObservations = new ArrayList<>(originalObservations);
        Collections.reverse(reversedObservations);
        var firstDecision = AutonomousEvidenceDagV2Fixtures.decision(
            brief, originalObservations);
        var secondDecision = AutonomousEvidenceDagV2Fixtures.decision(
            brief, reversedObservations);

        var first = AutonomousCampaignArtifactsV2.plan(
            brief, List.of(firstDecision));
        var second = AutonomousCampaignArtifactsV2.plan(
            brief, List.of(secondDecision));

        assertEquals(firstDecision.contentHash(), secondDecision.contentHash());
        assertEquals(first.contentHash(), second.contentHash());
        assertEquals(first.toCanonicalJson(), second.toCanonicalJson());
    }

    @Test
    void rejectsOrphanExecutionLineageOrNextPlan() {
        var brief = AutonomousEvidenceDagV2Fixtures.brief();
        var decision = AutonomousEvidenceDagV2Fixtures.decision();
        var receipt = AutonomousEvidenceDagV2.completeCandidateFormation(
            decision,
            AutonomousEvidenceDagV2Fixtures.hash("orphan-mining"),
            List.of(),
            List.of(),
            Map.of(ResourceKind.MINING_BATCHES, 1L),
            Map.of());
        var emptyPlan = AutonomousCampaignArtifactsV2.plan(brief, List.of());

        assertThrows(
            IllegalArgumentException.class,
            () -> AutonomousCampaignArtifactsV2.execution(emptyPlan, receipt));
    }
}
