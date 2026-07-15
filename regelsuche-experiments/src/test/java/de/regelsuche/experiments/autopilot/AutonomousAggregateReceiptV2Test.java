package de.regelsuche.experiments.autopilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.AggregateDisposition;
import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.CandidateDraft;
import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.RejectedCluster;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.ResourceKind;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class AutonomousAggregateReceiptV2Test {
    @Test
    void supportsZeroToManyCanonicalOutputs() {
        var decision = AutonomousEvidenceDagV2Fixtures.decision();
        var zero = AutonomousEvidenceDagV2.completeCandidateFormation(
            decision,
            AutonomousEvidenceDagV2Fixtures.hash("mining-evidence-zero"),
            List.of(),
            List.of(RejectedCluster.create(
                AutonomousEvidenceDagV2Fixtures.hash("rejected-cluster"),
                "INSUFFICIENT_ALPHA_DIVERSITY",
                List.of("observation-a", "observation-b"))),
            Map.of(ResourceKind.MINING_BATCHES, 1L),
            Map.of());
        var many = AutonomousEvidenceDagV2.completeCandidateFormation(
            decision,
            AutonomousEvidenceDagV2Fixtures.hash("mining-evidence-many"),
            List.of(
                new CandidateDraft(
                    "conjecture-factor",
                    AutonomousEvidenceDagV2Fixtures.hash("candidate-factor"),
                    List.of("observation-b", "observation-a")),
                new CandidateDraft(
                    "conjecture-transfer",
                    AutonomousEvidenceDagV2Fixtures.hash("candidate-transfer"),
                    List.of("observation-c", "observation-b"))),
            List.of(),
            Map.of(
                ResourceKind.MINING_BATCHES, 1L,
                ResourceKind.CANDIDATES, 2L),
            Map.of());

        assertEquals(AggregateDisposition.COMPLETED, zero.disposition());
        assertTrue(zero.outputs().isEmpty());
        assertEquals(1, zero.rejectedClusters().size());
        assertEquals(2, many.outputs().size());
        assertEquals(
            Set.of("conjecture-factor", "conjecture-transfer"),
            many.outputs().stream().map(output -> output.conjectureId())
                .collect(Collectors.toSet()));
        assertEquals(
            many.outputs().stream().map(output -> output.outputBranchId()).sorted().toList(),
            many.outputs().stream().map(output -> output.outputBranchId()).toList());
        assertNotEquals(
            many.outputs().get(0).outputBranchId(),
            many.outputs().get(1).outputBranchId());
    }

    @Test
    void retainsExactlyCandidateSpecificSourceLineage() {
        var receipt = AutonomousEvidenceDagV2.completeCandidateFormation(
            AutonomousEvidenceDagV2Fixtures.decision(),
            AutonomousEvidenceDagV2Fixtures.hash("mining-evidence-lineage"),
            List.of(new CandidateDraft(
                "conjecture-factor",
                AutonomousEvidenceDagV2Fixtures.hash("candidate-factor"),
                List.of("observation-a", "observation-b"))),
            List.of(),
            Map.of(
                ResourceKind.MINING_BATCHES, 1L,
                ResourceKind.CANDIDATES, 1L),
            Map.of());
        var output = receipt.outputs().getFirst();

        assertEquals(
            List.of("observation-a", "observation-b"),
            output.sources().stream().map(source -> source.observationId()).toList());
        assertFalse(output.sources().stream().anyMatch(source ->
            source.observationId().equals("observation-c")));
        assertTrue(output.sources().stream().allMatch(source ->
            source.observationBranchHash().matches("sha256:[0-9a-f]{64}")));
        assertTrue(output.lineageHash().matches("sha256:[0-9a-f]{64}"));
    }

    @Test
    void rejectsInsufficientExternalDuplicateOrOverCapacityOutputs() {
        var decision = AutonomousEvidenceDagV2Fixtures.decision();
        assertThrows(
            IllegalArgumentException.class,
            () -> AutonomousEvidenceDagV2.completeCandidateFormation(
                decision,
                AutonomousEvidenceDagV2Fixtures.hash("mining-one-support"),
                List.of(new CandidateDraft(
                    "conjecture-one-support",
                    AutonomousEvidenceDagV2Fixtures.hash("candidate-one-support"),
                    List.of("observation-a"))),
                List.of(),
                Map.of(
                    ResourceKind.MINING_BATCHES, 1L,
                    ResourceKind.CANDIDATES, 1L),
                Map.of()));
        assertThrows(
            IllegalArgumentException.class,
            () -> AutonomousEvidenceDagV2.completeCandidateFormation(
                decision,
                AutonomousEvidenceDagV2Fixtures.hash("mining-external-support"),
                List.of(new CandidateDraft(
                    "conjecture-external",
                    AutonomousEvidenceDagV2Fixtures.hash("candidate-external"),
                    List.of("observation-a", "observation-outside"))),
                List.of(),
                Map.of(
                    ResourceKind.MINING_BATCHES, 1L,
                    ResourceKind.CANDIDATES, 1L),
                Map.of()));
        assertThrows(
            IllegalArgumentException.class,
            () -> AutonomousEvidenceDagV2.completeCandidateFormation(
                decision,
                AutonomousEvidenceDagV2Fixtures.hash("mining-duplicate-ids"),
                List.of(
                    new CandidateDraft(
                        "conjecture-duplicate",
                        AutonomousEvidenceDagV2Fixtures.hash("candidate-a"),
                        List.of("observation-a", "observation-b")),
                    new CandidateDraft(
                        "conjecture-duplicate",
                        AutonomousEvidenceDagV2Fixtures.hash("candidate-b"),
                        List.of("observation-b", "observation-c"))),
                List.of(),
                Map.of(
                    ResourceKind.MINING_BATCHES, 1L,
                    ResourceKind.CANDIDATES, 2L),
                Map.of()));
        List<CandidateDraft> tooMany = java.util.stream.IntStream.range(0, 4)
            .mapToObj(index -> new CandidateDraft(
                "conjecture-" + index,
                AutonomousEvidenceDagV2Fixtures.hash("candidate-" + index),
                List.of("observation-a", "observation-b")))
            .toList();
        assertThrows(
            IllegalArgumentException.class,
            () -> AutonomousEvidenceDagV2.completeCandidateFormation(
                decision,
                AutonomousEvidenceDagV2Fixtures.hash("mining-over-capacity"),
                tooMany,
                List.of(),
                Map.of(
                    ResourceKind.MINING_BATCHES, 1L,
                    ResourceKind.CANDIDATES, 3L),
                Map.of()));
    }

    @Test
    void backendUnavailableSkipsThePlanWithoutInventingOutputs() {
        var decision = AutonomousEvidenceDagV2Fixtures.decision();

        var receipt = AutonomousEvidenceDagV2.backendUnavailable(
            decision, "MINER_BACKEND_UNAVAILABLE");

        assertEquals(AggregateDisposition.BACKEND_UNAVAILABLE, receipt.disposition());
        assertTrue(receipt.executedResources().isEmpty());
        assertEquals(decision.plannedResources(), receipt.skippedResources());
        assertTrue(receipt.outputs().isEmpty());
        assertFalse(receipt.receiptIsMathematicalEvidence());
        assertEquals("NOT_EVALUATED", receipt.promotionStatus());
        assertEquals("NOT_EVALUATED", receipt.publicEvidenceStatus());
    }
}
