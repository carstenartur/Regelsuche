package de.regelsuche.experiments.autopilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.CandidateDraft;
import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.ObservationBranch;
import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.RejectedCluster;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.ResourceKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AutonomousEvidenceDagSnapshotV2Test {
    @Test
    void isStableUnderInputPermutationAndRetainsRejectedEvidence()
            throws Exception {
        AutonomousResearchBriefV2 brief = AutonomousEvidenceDagV2Fixtures.brief();
        List<ObservationBranch> observations = AutonomousEvidenceDagV2Fixtures.observations();
        var decision = AutonomousEvidenceDagV2Fixtures.decision(brief, observations);
        var receipt = AutonomousEvidenceDagV2.completeCandidateFormation(
            decision,
            AutonomousEvidenceDagV2Fixtures.hash("mining-evidence-dag"),
            List.of(new CandidateDraft(
                "conjecture-factor",
                AutonomousEvidenceDagV2Fixtures.hash("candidate-factor"),
                List.of("observation-a", "observation-b"))),
            List.of(RejectedCluster.create(
                AutonomousEvidenceDagV2Fixtures.hash("rejected-cluster-dag"),
                "INSUFFICIENT_SUPPORT",
                List.of("observation-c"))),
            Map.of(
                ResourceKind.MINING_BATCHES, 1L,
                ResourceKind.CANDIDATES, 1L),
            Map.of());
        var first = AutonomousEvidenceDagV2.snapshot(
            brief, observations, List.of(decision), List.of(receipt));
        List<ObservationBranch> reversed = new ArrayList<>(observations);
        java.util.Collections.reverse(reversed);
        var second = AutonomousEvidenceDagV2.snapshot(
            brief, reversed, List.of(decision), List.of(receipt));
        Path directory = Path.of("build", "reports", "autopilot-v2-dag");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("brief-v2.json"), brief.toCanonicalJson());
        Files.writeString(
            directory.resolve("aggregate-decision.json"),
            decision.toCanonicalJson());
        Files.writeString(
            directory.resolve("aggregate-receipt.json"),
            receipt.toCanonicalJson());
        Files.writeString(directory.resolve("dag.json"), first.toCanonicalJson());

        assertEquals(first.contentHash(), second.contentHash());
        assertEquals(first.toCanonicalJson(), second.toCanonicalJson());
        assertEquals(1, receipt.rejectedClusters().size());
        assertFalse(first.dagIsMathematicalEvidence());
        assertEquals("NOT_EVALUATED", first.promotionStatus());
        assertEquals("NOT_EVALUATED", first.publicEvidenceStatus());
        assertEquals(first.toCanonicalJson(), Files.readString(directory.resolve("dag.json")));
    }

    @Test
    void rejectsReceiptWithoutRetainedDecisionOrObservationInputs() {
        AutonomousResearchBriefV2 brief = AutonomousEvidenceDagV2Fixtures.brief();
        List<ObservationBranch> observations = AutonomousEvidenceDagV2Fixtures.observations();
        var decision = AutonomousEvidenceDagV2Fixtures.decision(brief, observations);
        var receipt = AutonomousEvidenceDagV2.completeCandidateFormation(
            decision,
            AutonomousEvidenceDagV2Fixtures.hash("mining-evidence-orphan"),
            List.of(),
            List.of(),
            Map.of(ResourceKind.MINING_BATCHES, 1L),
            Map.of());

        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> AutonomousEvidenceDagV2.snapshot(
                brief, observations, List.of(), List.of(receipt)));
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> AutonomousEvidenceDagV2.snapshot(
                brief,
                observations.subList(0, 2),
                List.of(decision),
                List.of(receipt)));
    }
}
