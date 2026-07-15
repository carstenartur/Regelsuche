package de.regelsuche.experiments.autopilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.ResourceKind;
import de.regelsuche.mining.OpenTargetConjectureEvidence;
import de.regelsuche.mining.OpenTargetConjectureEvidence.CampaignContext;
import de.regelsuche.mining.OpenTargetConjectureEvidence.SeedProvenance;
import de.regelsuche.mining.OpenTargetConjectureMiner.ConvergenceEvidence;
import de.regelsuche.mining.OpenTargetConjectureMiner.MiningReport;
import de.regelsuche.mining.OpenTargetConjectureMiner.OpenTargetConjecture;
import de.regelsuche.mining.OpenTargetConjectureMiner.PathEvidence;
import de.regelsuche.mining.OpenTargetConjectureMiner.RejectedCluster;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenTargetAutopilotV2BindingTest {
    @Test
    void bindsCanonicalProductionEvidenceToExactCandidateLineage() throws IOException {
        var brief = AutonomousEvidenceDagV2Fixtures.brief();
        var decision = AutonomousEvidenceDagV2Fixtures.decision();
        var binding = OpenTargetAutopilotV2Binding.completeCandidateFormation(
            brief,
            decision,
            evidence(brief.inventoryHash(), true),
            Map.of(
                ResourceKind.MINING_BATCHES, 1L,
                ResourceKind.CANDIDATES, 1L),
            Map.of());

        assertEquals("campaign-open-target-336", binding.campaignId());
        assertEquals(brief.inventoryHash(), binding.ruleInventoryHash());
        assertFalse(binding.targetProvided());
        assertFalse(binding.bindingIsMathematicalEvidence());
        assertEquals("NOT_EVALUATED", binding.promotionStatus());
        assertEquals("NOT_EVALUATED", binding.publicEvidenceStatus());
        assertEquals(binding.miningEvidenceHash(), binding.receipt().miningEvidenceHash());
        assertEquals(1, binding.receipt().outputs().size());
        assertEquals(
            List.of("observation-a", "observation-b"),
            binding.receipt().outputs().getFirst().sources().stream()
                .map(AutonomousEvidenceDagV2.SourceLink::observationId)
                .toList());
        assertEquals(
            List.of("obs-branch-a", "obs-branch-b"),
            binding.receipt().outputs().getFirst().sources().stream()
                .map(AutonomousEvidenceDagV2.SourceLink::sourceBranchId)
                .toList());
        assertTrue(binding.toCanonicalJson().contains("\"targetProvided\":false"));
        assertTrue(binding.toCanonicalJson().contains(binding.miningEvidenceHash()));

        Path output = Path.of(
            "build", "reports", "autopilot-v2-dag", "production-binding.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, binding.toCanonicalJson(), StandardCharsets.UTF_8);
    }

    @Test
    void bindingIsStableUnderAggregateInputPermutation() {
        var brief = AutonomousEvidenceDagV2Fixtures.brief();
        var observations = new ArrayList<>(AutonomousEvidenceDagV2Fixtures.observations());
        Collections.reverse(observations);
        var permutedDecision = AutonomousEvidenceDagV2Fixtures.decision(brief, observations);
        var evidence = evidence(brief.inventoryHash(), true);

        var original = OpenTargetAutopilotV2Binding.completeCandidateFormation(
            brief,
            AutonomousEvidenceDagV2Fixtures.decision(),
            evidence,
            Map.of(
                ResourceKind.MINING_BATCHES, 1L,
                ResourceKind.CANDIDATES, 1L),
            Map.of());
        var permuted = OpenTargetAutopilotV2Binding.completeCandidateFormation(
            brief,
            permutedDecision,
            evidence,
            Map.of(
                ResourceKind.MINING_BATCHES, 1L,
                ResourceKind.CANDIDATES, 1L),
            Map.of());

        assertEquals(original.decisionHash(), permuted.decisionHash());
        assertEquals(original.miningEvidenceHash(), permuted.miningEvidenceHash());
        assertEquals(original.receipt().contentHash(), permuted.receipt().contentHash());
        assertEquals(original.contentHash(), permuted.contentHash());
    }

    @Test
    void rejectsInventoryOrObservationBatchMismatch() {
        var brief = AutonomousEvidenceDagV2Fixtures.brief();
        var decision = AutonomousEvidenceDagV2Fixtures.decision();

        assertThrows(
            IllegalArgumentException.class,
            () -> OpenTargetAutopilotV2Binding.completeCandidateFormation(
                brief,
                decision,
                evidence(AutonomousEvidenceDagV2Fixtures.hash("other-inventory"), true),
                Map.of(ResourceKind.MINING_BATCHES, 1L),
                Map.of()));
        assertThrows(
            IllegalArgumentException.class,
            () -> OpenTargetAutopilotV2Binding.completeCandidateFormation(
                brief,
                decision,
                evidence(brief.inventoryHash(), false),
                Map.of(ResourceKind.MINING_BATCHES, 1L),
                Map.of()));
    }

    private static OpenTargetConjectureEvidence evidence(
        String inventoryHash,
        boolean includeRejectedObservation
    ) {
        ConvergenceEvidence observationA = convergence(
            "observation-a", "family-a", "sha256:alpha-a");
        ConvergenceEvidence observationB = convergence(
            "observation-b", "family-b", "sha256:alpha-b");
        OpenTargetConjecture conjecture = new OpenTargetConjecture(
            "open-target-conjecture-production-binding",
            "A + B",
            "B + A",
            2,
            2,
            List.of("family-a", "family-b"),
            List.of("observation-a", "observation-b"),
            List.of(observationA, observationB),
            List.of(),
            Map.of(),
            "OBSERVED_CONJECTURE",
            "EQUIVALENCE_PRESERVING_CONVERGENT_PATHS");
        List<RejectedCluster> rejected = includeRejectedObservation
            ? List.of(new RejectedCluster(
                "cluster-observation-c",
                List.of("observation-c"),
                0,
                0,
                "no-independent-equivalence-preserving-convergence"))
            : List.of();
        List<SeedProvenance> seeds = new ArrayList<>(List.of(
            seed("observation-a"),
            seed("observation-b")));
        if (includeRejectedObservation) {
            seeds.add(seed("observation-c"));
        }
        return new OpenTargetConjectureEvidence(
            new CampaignContext(
                "campaign-open-target-336",
                "0.2-test",
                "revision-336",
                inventoryHash,
                new SearchHeuristic(4, 100, 1),
                seeds),
            new MiningReport(
                "regelsuche.open-target-conjecture-mining/v1",
                false,
                List.of(conjecture),
                rejected));
    }

    private static ConvergenceEvidence convergence(
        String observationId,
        String family,
        String alphaFingerprint
    ) {
        return new ConvergenceEvidence(
            observationId,
            family,
            GoalStatus.UNTARGETED,
            "x + 0",
            "x",
            AutonomousEvidenceDagV2Fixtures.hash("canonical-" + observationId),
            1,
            alphaFingerprint,
            AutonomousEvidenceDagV2Fixtures.hash("value-" + observationId),
            "neutral-addition",
            List.of(new PathEvidence(
                "path-" + observationId,
                List.of("x + 0", "x"),
                List.of("add-zero"),
                List.of(),
                1,
                1)));
    }

    private static SeedProvenance seed(String observationId) {
        return new SeedProvenance(
            observationId,
            "seed-" + observationId,
            "untargeted-search-generator",
            Map.of("familyBlind", "true"));
    }
}
