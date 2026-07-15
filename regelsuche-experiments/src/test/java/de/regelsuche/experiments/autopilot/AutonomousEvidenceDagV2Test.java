package de.regelsuche.experiments.autopilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.AggregateDisposition;
import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.CandidateDraft;
import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.ObservationBranch;
import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.RejectedCluster;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.EvidenceStage;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.ResourceKind;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.StageBudget;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AutonomousEvidenceDagV2Test {
    @Test
    void v2BriefAddsNoveltyAndLifecycleWithoutTargetFields() {
        AutonomousResearchBriefV2 brief = brief();
        List<String> componentNames = java.util.Arrays.stream(
                AutonomousResearchBriefV2.class.getRecordComponents())
            .map(component -> component.getName().toLowerCase())
            .toList();

        assertTrue(brief.stageBudgets().containsKey(EvidenceStage.PROJECT_NOVELTY));
        assertTrue(brief.stageBudgets().containsKey(EvidenceStage.LIFECYCLE_HANDOFF));
        assertTrue(componentNames.stream().noneMatch(name ->
            name.contains("target") || name.contains("expectedanswer")));
        assertFalse(brief.toCanonicalJson().contains("targetExpression"));
        assertFalse(brief.toCanonicalJson().contains("expectedAnswer"));
    }

    @Test
    void aggregateDecisionIsStableUnderObservationPermutation() {
        AutonomousResearchBriefV2 brief = brief();
        List<ObservationBranch> observations = observations();
        List<ObservationBranch> reversed = new ArrayList<>(observations);
        java.util.Collections.reverse(reversed);

        var first = AutonomousEvidenceDagV2.planCandidateFormation(
            brief,
            "mine-batch-1",
            observations,
            plannedCandidateFormationResources(),
            "mine structurally independent untargeted observations");
        var second = AutonomousEvidenceDagV2.planCandidateFormation(
            brief,
            "mine-batch-1",
            reversed,
            Map.of(
                ResourceKind.CANDIDATES, 3L,
                ResourceKind.WALL_CLOCK_MILLIS, 500L,
                ResourceKind.MINING_BATCHES, 1L),
            "mine structurally independent untargeted observations");

        assertEquals(first.contentHash(), second.contentHash());
        assertEquals(first.toCanonicalJson(), second.toCanonicalJson());
        assertEquals(
            List.of("obs-branch-a", "obs-branch-b", "obs-branch-c"),
            first.inputs().stream().map(ObservationBranch::branchId).toList());
    }

    @Test
    void completedAggregateReceiptSupportsZeroToManyOutputs() {
        AutonomousResearchBriefV2 brief = brief();
        var decision = decision(brief, observations());

        var zero = AutonomousEvidenceDagV2.completeCandidateFormation(
            decision,
            hash("mining-evidence-zero"),
            List.of(),
            List.of(RejectedCluster.create(
                hash("rejected-cluster"),
                "INSUFFICIENT_ALPHA_DIVERSITY",
                List.of("observation-a", "observation-b"))),
            Map.of(ResourceKind.MINING_BATCHES, 1L),
            Map.of());
        var many = AutonomousEvidenceDagV2.completeCandidateFormation(
            decision,
            hash("mining-evidence-many"),
            List.of(
                new CandidateDraft(
                    "conjecture-factor",
                    hash("candidate-factor"),
                    List.of("observation-b", "observation-a")),
                new CandidateDraft(
                    "conjecture-transfer",
                    hash("candidate-transfer"),
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
            List.of("conjecture-factor", "conjecture-transfer"),
            many.outputs().stream().map(output -> output.conjectureId()).toList());
        assertNotEquals(
            many.outputs().get(0).outputBranchId(),
            many.outputs().get(1).outputBranchId());
    }

    @Test
    void eachCandidateLineageContainsExactlyItsSupportingObservations() {
        AutonomousResearchBriefV2 brief = brief();
        var decision = decision(brief, observations());
        var receipt = AutonomousEvidenceDagV2.completeCandidateFormation(
            decision,
            hash("mining-evidence-lineage"),
            List.of(new CandidateDraft(
                "conjecture-factor",
                hash("candidate-factor"),
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
    void insufficientDuplicateOrExternalSupportCreatesNoCandidate() {
        AutonomousResearchBriefV2 brief = brief();
        var decision = decision(brief, observations());

        assertThrows(
            IllegalArgumentException.class,
            () -> AutonomousEvidenceDagV2.completeCandidateFormation(
                decision,
                hash("mining-one-support"),
                List.of(new CandidateDraft(
                    "conjecture-one-support",
                    hash("candidate-one-support"),
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
                hash("mining-external-support"),
                List.of(new CandidateDraft(
                    "conjecture-external",
                    hash("candidate-external"),
                    List.of("observation-a", "observation-outside"))),
                List.of(),
                Map.of(
                    ResourceKind.MINING_BATCHES, 1L,
                    ResourceKind.CANDIDATES, 1L),
                Map.of()));
        assertThrows(
            IllegalArgumentException.class,
            () -> AutonomousEvidenceDagV2.planCandidateFormation(
                brief,
                "duplicate-evidence-batch",
                List.of(
                    observation(
                        "duplicate-a", "family-a", "duplicate-observation-a",
                        "same-evidence"),
                    observation(
                        "duplicate-b", "family-b", "duplicate-observation-b",
                        "same-evidence")),
                plannedCandidateFormationResources(),
                "duplicate evidence must not count twice"));
    }

    @Test
    void targetedObservationAndWrongStageResourcesAreRejected() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new ObservationBranch(
                "targeted-branch",
                AutonomousEvidenceDagV2.BranchType.OBSERVATION,
                "family-a",
                "targeted-observation",
                hash("targeted-snapshot"),
                hash("targeted-evidence"),
                "TARGETED",
                hash("targeted-content")));
        Map<EvidenceStage, StageBudget> budgets = new java.util.EnumMap<>(budgets());
        budgets.put(
            EvidenceStage.PROJECT_NOVELTY,
            new StageBudget(Map.of(ResourceKind.PROOF_ATTEMPTS, 1L)));
        assertThrows(
            IllegalArgumentException.class,
            () -> createBrief(budgets));
    }

    @Test
    void backendUnavailableSkipsThePlannedBatchWithoutOutputs() {
        var decision = decision(brief(), observations());

        var receipt = AutonomousEvidenceDagV2.backendUnavailable(
            decision, "MINER_BACKEND_UNAVAILABLE");

        assertEquals(AggregateDisposition.BACKEND_UNAVAILABLE, receipt.disposition());
        assertTrue(receipt.executedResources().isEmpty());
        assertEquals(decision.plannedResources(), receipt.skippedResources());
        assertTrue(receipt.outputs().isEmpty());
    }

    @Test
    void dagSnapshotIsStableAndRetainsRejectedEvidence() throws Exception {
        AutonomousResearchBriefV2 brief = brief();
        List<ObservationBranch> observations = observations();
        var decision = decision(brief, observations);
        var receipt = AutonomousEvidenceDagV2.completeCandidateFormation(
            decision,
            hash("mining-evidence-dag"),
            List.of(new CandidateDraft(
                "conjecture-factor",
                hash("candidate-factor"),
                List.of("observation-a", "observation-b"))),
            List.of(RejectedCluster.create(
                hash("rejected-cluster-dag"),
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

    private static AutonomousEvidenceDagV2.AggregateDecision decision(
        AutonomousResearchBriefV2 brief,
        List<ObservationBranch> observations
    ) {
        return AutonomousEvidenceDagV2.planCandidateFormation(
            brief,
            "mine-batch-1",
            observations,
            plannedCandidateFormationResources(),
            "mine structurally independent untargeted observations");
    }

    private static Map<ResourceKind, Long> plannedCandidateFormationResources() {
        return Map.of(
            ResourceKind.WALL_CLOCK_MILLIS, 500L,
            ResourceKind.MINING_BATCHES, 1L,
            ResourceKind.CANDIDATES, 3L);
    }

    private static AutonomousResearchBriefV2 brief() {
        return createBrief(budgets());
    }

    private static AutonomousResearchBriefV2 createBrief(
        Map<EvidenceStage, StageBudget> stageBudgets
    ) {
        return AutonomousResearchBriefV2.create(
            "autopilot-v2-dag-characterization",
            "sha256:b1aa8dce6924467390e2a89687678abcd54ba70925e650370faa1b151ae84359",
            List.of("algebra", "rational", "functional"),
            List.of("untargeted-search-generator"),
            hash("inventory-v2"),
            hash("packs-v2"),
            hash("model-v2"),
            336L,
            2,
            2,
            2,
            "campaign-336",
            stageBudgets);
    }

    private static Map<EvidenceStage, StageBudget> budgets() {
        return Map.of(
            EvidenceStage.GENERATION,
            new StageBudget(Map.of(
                ResourceKind.WALL_CLOCK_MILLIS, 2_000L,
                ResourceKind.GENERATED_STATES, 500L,
                ResourceKind.EXPLORED_STATES, 250L,
                ResourceKind.OBSERVATIONS, 12L)),
            EvidenceStage.CANDIDATE_FORMATION,
            new StageBudget(Map.of(
                ResourceKind.WALL_CLOCK_MILLIS, 1_000L,
                ResourceKind.MINING_BATCHES, 2L,
                ResourceKind.CANDIDATES, 6L)),
            EvidenceStage.VALIDATION,
            new StageBudget(Map.of(
                ResourceKind.WALL_CLOCK_MILLIS, 2_000L,
                ResourceKind.VALIDATION_CHECKS, 200L)),
            EvidenceStage.COUNTEREXAMPLE_SEARCH,
            new StageBudget(Map.of(
                ResourceKind.WALL_CLOCK_MILLIS, 2_000L,
                ResourceKind.COUNTEREXAMPLE_ATTEMPTS, 100L)),
            EvidenceStage.PROJECT_NOVELTY,
            new StageBudget(Map.of(
                ResourceKind.WALL_CLOCK_MILLIS, 1_000L,
                ResourceKind.NOVELTY_COMPARISONS, 100L)),
            EvidenceStage.PROOF,
            new StageBudget(Map.of(
                ResourceKind.WALL_CLOCK_MILLIS, 3_000L,
                ResourceKind.PROOF_ATTEMPTS, 6L)),
            EvidenceStage.LIFECYCLE_HANDOFF,
            new StageBudget(Map.of(
                ResourceKind.WALL_CLOCK_MILLIS, 1_000L,
                ResourceKind.LIFECYCLE_HANDOFFS, 6L)));
    }

    private static List<ObservationBranch> observations() {
        return List.of(
            observation("obs-branch-a", "family-a", "observation-a", "evidence-a"),
            observation("obs-branch-b", "family-b", "observation-b", "evidence-b"),
            observation("obs-branch-c", "family-c", "observation-c", "evidence-c"));
    }

    private static ObservationBranch observation(
        String branchId,
        String familyId,
        String observationId,
        String evidenceSeed
    ) {
        return ObservationBranch.create(
            branchId,
            familyId,
            observationId,
            hash("snapshot-" + branchId),
            hash(evidenceSeed));
    }

    private static String hash(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
