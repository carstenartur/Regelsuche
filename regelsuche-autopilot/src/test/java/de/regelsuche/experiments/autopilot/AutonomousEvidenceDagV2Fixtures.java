package de.regelsuche.experiments.autopilot;

import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.ObservationBranch;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.EvidenceStage;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.ResourceKind;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.StageBudget;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

final class AutonomousEvidenceDagV2Fixtures {
    private AutonomousEvidenceDagV2Fixtures() {
    }

    static AutonomousResearchBriefV2 brief() {
        return brief(budgets());
    }

    static AutonomousResearchBriefV2 brief(
        Map<EvidenceStage, StageBudget> stageBudgets
    ) {
        return AutonomousResearchBriefV2.create(
            "autopilot-v2-dag-characterization",
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

    static Map<EvidenceStage, StageBudget> budgets() {
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

    static List<ObservationBranch> observations() {
        return List.of(
            observation("obs-branch-a", "family-a", "observation-a", "evidence-a"),
            observation("obs-branch-b", "family-b", "observation-b", "evidence-b"),
            observation("obs-branch-c", "family-c", "observation-c", "evidence-c"));
    }

    static ObservationBranch observation(
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

    static AutonomousEvidenceDagV2.AggregateDecision decision() {
        return decision(brief(), observations());
    }

    static AutonomousEvidenceDagV2.AggregateDecision decision(
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

    static Map<ResourceKind, Long> plannedCandidateFormationResources() {
        return Map.of(
            ResourceKind.WALL_CLOCK_MILLIS, 500L,
            ResourceKind.MINING_BATCHES, 1L,
            ResourceKind.CANDIDATES, 3L);
    }

    static String hash(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
