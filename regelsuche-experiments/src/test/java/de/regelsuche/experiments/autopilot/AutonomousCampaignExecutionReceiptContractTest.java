package de.regelsuche.experiments.autopilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.DeterministicDiscoveryExperimentRunner.SeedRunOutcome;
import de.regelsuche.example.SeedExpression;
import de.regelsuche.experiments.autopilot.AutonomousCampaignExecutionAdapter.ExecutionDisposition;
import de.regelsuche.experiments.autopilot.AutonomousCampaignExecutionAdapter.StageExecution;
import de.regelsuche.experiments.autopilot.AutonomousResearchBrief.AllocationPolicy;
import de.regelsuche.experiments.autopilot.AutonomousResearchBrief.CampaignBudget;
import de.regelsuche.experiments.autopilot.AutonomousResearchBrief.EvidenceStage;
import de.regelsuche.experiments.autopilot.AutonomousResearchBrief.ResourceKind;
import de.regelsuche.experiments.autopilot.AutonomousResearchBrief.StageBudget;
import de.regelsuche.experiments.autopilot.AutonomousResearchBrief.StructuralBounds;
import de.regelsuche.experiments.autopilot.DeterministicCampaignPlanner.BranchSnapshot;
import de.regelsuche.experiments.autopilot.DeterministicCampaignPlanner.BranchStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AutonomousCampaignExecutionReceiptContractTest {
    @Test
    void completedWithoutExecutedDomainWorkCannotAdvanceTheBranch() {
        AutonomousResearchBrief brief = brief();
        CampaignBudgetLedger ledger = CampaignBudgetLedger.configured(brief);
        BranchSnapshot snapshot = BranchSnapshot.create(
            "seed-algebra",
            "algebra",
            BranchStatus.ELIGIBLE_INCOMPLETE,
            EnumSet.allOf(EvidenceStage.class),
            EnumSet.of(EvidenceStage.GENERATION),
            2,
            300,
            -1);
        DeterministicCampaignPlanner planner = new DeterministicCampaignPlanner();
        var plan = planner.plan(brief, ledger, List.of(snapshot));

        var execution = new AutonomousCampaignExecutionAdapter().execute(
            brief,
            ledger,
            plan,
            List.of(snapshot),
            List.of(seed()),
            1,
            (stage, seed) -> new StageExecution(
                new SeedRunOutcome(
                    true,
                    "claimed completion without candidate formation",
                    List.of(),
                    List.of(),
                    List.of(seed.expression()),
                    5L,
                    128L),
                ExecutionDisposition.COMPLETED,
                Map.of(ResourceKind.WALL_CLOCK_MILLIS, 5L),
                Map.of(),
                "no candidate slot was executed"));

        var receipt = execution.receipts().getFirst();
        assertEquals(ExecutionDisposition.BACKEND_UNAVAILABLE, receipt.disposition());
        assertTrue(receipt.executedResources().isEmpty());
        assertEquals(1L, receipt.skipped(ResourceKind.CANDIDATES));
        assertFalse(execution.nextSnapshots().getFirst().completedStages().contains(
            EvidenceStage.CANDIDATE_FORMATION));
    }

    private static AutonomousResearchBrief brief() {
        return AutonomousResearchBrief.create(
            "receipt-contract-v1",
            List.of("algebra"),
            List.of("structural-seed-generator"),
            new StructuralBounds(4, 64, 4),
            hash("inventory"),
            hash("packs"),
            hash("model"),
            17L,
            EnumSet.allOf(EvidenceStage.class),
            List.of("target-free-candidate-formation"),
            2,
            2,
            true,
            AllocationPolicy.EVIDENCE_COMPLETION_FIRST,
            new CampaignBudget(Map.of(
                EvidenceStage.GENERATION,
                new StageBudget(Map.of(
                    ResourceKind.WALL_CLOCK_MILLIS, 1000L,
                    ResourceKind.GENERATED_STATES, 100L,
                    ResourceKind.EXPLORED_STATES, 50L)),
                EvidenceStage.CANDIDATE_FORMATION,
                new StageBudget(Map.of(
                    ResourceKind.WALL_CLOCK_MILLIS, 250L,
                    ResourceKind.CANDIDATES, 1L)),
                EvidenceStage.VALIDATION,
                new StageBudget(Map.of(
                    ResourceKind.WALL_CLOCK_MILLIS, 250L,
                    ResourceKind.VALIDATION_CHECKS, 1L)),
                EvidenceStage.COUNTEREXAMPLE_SEARCH,
                new StageBudget(Map.of(
                    ResourceKind.WALL_CLOCK_MILLIS, 250L,
                    ResourceKind.COUNTEREXAMPLE_ATTEMPTS, 1L)),
                EvidenceStage.PROOF,
                new StageBudget(Map.of(
                    ResourceKind.WALL_CLOCK_MILLIS, 1000L,
                    ResourceKind.PROOF_ATTEMPTS, 1L)))));
    }

    private static SeedExpression seed() {
        return new SeedExpression(
            "seed-algebra",
            "m * 2 + m * 3",
            "structural-seed-generator",
            "algebra",
            List.of("target-free"),
            List.of());
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
