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
import de.regelsuche.experiments.autopilot.DeterministicCampaignPlanner.DecisionKind;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AutonomousCampaignExecutionAdapterTest {
    private final DeterministicCampaignPlanner planner =
        new DeterministicCampaignPlanner();
    private final AutonomousCampaignExecutionAdapter adapter =
        new AutonomousCampaignExecutionAdapter();

    @Test
    void executesTwoFamiliesAndReplansFromObservedReceipts() throws Exception {
        AutonomousResearchBrief brief = brief();
        CampaignBudgetLedger ledger = CampaignBudgetLedger.configured(brief);
        List<BranchSnapshot> snapshots = snapshots();
        var firstPlan = planner.plan(brief, ledger, snapshots);

        var round = adapter.executeAndReplan(
            brief,
            ledger,
            firstPlan,
            snapshots,
            seeds(),
            2,
            AutonomousCampaignExecutionAdapterTest::candidateFormation,
            planner);
        Path directory = Path.of(
            "build", "reports", "autopilot-execution");
        round.write(directory);

        assertEquals(2, round.execution().receipts().size());
        assertTrue(round.execution().receipts().stream().allMatch(receipt ->
            receipt.stage() == EvidenceStage.CANDIDATE_FORMATION
                && receipt.disposition() == ExecutionDisposition.COMPLETED
                && receipt.hypothesisCount() == 1));
        assertTrue(round.execution().nextSnapshots().stream().allMatch(snapshot ->
            snapshot.completedStages().contains(EvidenceStage.CANDIDATE_FORMATION)
                && snapshot.status() == BranchStatus.ELIGIBLE_INCOMPLETE));
        assertEquals(
            2L,
            ledger.remaining(EvidenceStage.CANDIDATE_FORMATION, ResourceKind.CANDIDATES)
                - round.execution().updatedLedger().remaining(
                    EvidenceStage.CANDIDATE_FORMATION,
                    ResourceKind.CANDIDATES));
        assertTrue(round.nextPlan().decisions().stream().allMatch(decision ->
            decision.kind() == DecisionKind.ALLOCATE
                && decision.stage().equals(EvidenceStage.VALIDATION.name())));
        assertFalse(round.roundDecisionIsMathematicalEvidence());
        assertEquals("NOT_EVALUATED", round.promotionStatus());
        assertEquals("NOT_EVALUATED", round.publicEvidenceStatus());
        assertEquals(
            round.toCanonicalJson(),
            Files.readString(directory.resolve("round.json")));
    }

    @Test
    void logicalEvidenceIsStableUnderCatalogAndSnapshotPermutation() {
        AutonomousResearchBrief brief = brief();
        CampaignBudgetLedger ledger = CampaignBudgetLedger.configured(brief);
        List<BranchSnapshot> snapshots = snapshots();
        var plan = planner.plan(brief, ledger, snapshots);
        List<BranchSnapshot> reversedSnapshots = new ArrayList<>(snapshots);
        java.util.Collections.reverse(reversedSnapshots);
        List<SeedExpression> reversedSeeds = new ArrayList<>(seeds());
        java.util.Collections.reverse(reversedSeeds);

        var first = adapter.executeAndReplan(
            brief, ledger, plan, snapshots, seeds(), 2,
            AutonomousCampaignExecutionAdapterTest::candidateFormation,
            planner);
        var second = adapter.executeAndReplan(
            brief, ledger, plan, reversedSnapshots, reversedSeeds, 2,
            AutonomousCampaignExecutionAdapterTest::candidateFormation,
            planner);

        assertEquals(
            first.execution().logicalContentHash(),
            second.execution().logicalContentHash());
        assertEquals(first.nextPlan().contentHash(), second.nextPlan().contentHash());
        assertEquals(first.contentHash(), second.contentHash());
    }

    @Test
    void missingSeedIsSkippedWithoutInventingExecutedWork() {
        AutonomousResearchBrief brief = brief();
        CampaignBudgetLedger ledger = CampaignBudgetLedger.configured(brief);
        List<BranchSnapshot> snapshots = snapshots();
        var plan = planner.plan(brief, ledger, snapshots);

        var execution = adapter.execute(
            brief,
            ledger,
            plan,
            snapshots,
            List.of(seeds().getFirst()),
            1,
            AutonomousCampaignExecutionAdapterTest::candidateFormation);
        var missing = execution.receipts().stream()
            .filter(receipt -> receipt.branchId().equals("seed-rational"))
            .findFirst()
            .orElseThrow();

        assertEquals(ExecutionDisposition.BACKEND_UNAVAILABLE, missing.disposition());
        assertTrue(missing.executedResources().isEmpty());
        assertEquals(1L, missing.skipped(ResourceKind.CANDIDATES));
        assertTrue(execution.nextSnapshots().stream()
            .filter(snapshot -> snapshot.branchId().equals("seed-rational"))
            .noneMatch(snapshot -> snapshot.completedStages().contains(
                EvidenceStage.CANDIDATE_FORMATION)));
    }

    @Test
    void disprovedOutcomeBecomesATerminalBranchBeforeReplanning() {
        AutonomousResearchBrief brief = brief();
        CampaignBudgetLedger ledger = CampaignBudgetLedger.configured(brief);
        List<BranchSnapshot> snapshots = snapshots();
        var plan = planner.plan(brief, ledger, snapshots);

        var round = adapter.executeAndReplan(
            brief,
            ledger,
            plan,
            snapshots,
            seeds(),
            1,
            (stage, seed) -> seed.stableKey().equals("seed-algebra")
                ? disproved(stage, seed)
                : candidateFormation(stage, seed),
            planner);

        BranchSnapshot disproved = round.execution().nextSnapshots().stream()
            .filter(snapshot -> snapshot.branchId().equals("seed-algebra"))
            .findFirst()
            .orElseThrow();
        assertEquals(BranchStatus.DISPROVED, disproved.status());
        assertTrue(round.nextPlan().decisions().stream().anyMatch(decision ->
            decision.branchId().equals("seed-algebra")
                && decision.kind() == DecisionKind.STOP));
    }

    private static StageExecution candidateFormation(
        EvidenceStage stage,
        SeedExpression seed
    ) {
        assertEquals(EvidenceStage.CANDIDATE_FORMATION, stage);
        SeedRunOutcome outcome = new SeedRunOutcome(
            true,
            "one target-free candidate formed for " + seed.stableKey(),
            List.of("candidate-" + seed.stableKey()),
            List.of(),
            List.of(seed.expression()),
            10L,
            1_024L);
        return new StageExecution(
            outcome,
            ExecutionDisposition.COMPLETED,
            Map.of(
                ResourceKind.WALL_CLOCK_MILLIS, 10L,
                ResourceKind.CANDIDATES, 1L),
            Map.of(),
            "candidate formation completed");
    }

    private static StageExecution disproved(
        EvidenceStage stage,
        SeedExpression seed
    ) {
        assertEquals(EvidenceStage.CANDIDATE_FORMATION, stage);
        SeedRunOutcome outcome = new SeedRunOutcome(
            false,
            "candidate disproved during stage execution",
            List.of(),
            List.of("counterexample for " + seed.stableKey()),
            List.of(seed.expression()),
            8L,
            1_024L);
        return new StageExecution(
            outcome,
            ExecutionDisposition.DISPROVED,
            Map.of(
                ResourceKind.WALL_CLOCK_MILLIS, 8L,
                ResourceKind.CANDIDATES, 1L),
            Map.of(),
            "counterexample blocks further work");
    }

    private static AutonomousResearchBrief brief() {
        return AutonomousResearchBrief.create(
            "two-family-autonomous-round-v1",
            List.of("algebra", "rational"),
            List.of("structural-seed-generator"),
            new StructuralBounds(6, 128, 8),
            hash("inventory"),
            hash("packs"),
            hash("model"),
            773L,
            EnumSet.allOf(EvidenceStage.class),
            List.of("target-free-candidate-formation"),
            2,
            2,
            true,
            AllocationPolicy.EVIDENCE_COMPLETION_FIRST,
            budget());
    }

    private static CampaignBudget budget() {
        return new CampaignBudget(Map.of(
            EvidenceStage.GENERATION,
            new StageBudget(Map.of(
                ResourceKind.WALL_CLOCK_MILLIS, 2_000L,
                ResourceKind.GENERATED_STATES, 200L,
                ResourceKind.EXPLORED_STATES, 100L)),
            EvidenceStage.CANDIDATE_FORMATION,
            new StageBudget(Map.of(
                ResourceKind.WALL_CLOCK_MILLIS, 1_000L,
                ResourceKind.CANDIDATES, 2L)),
            EvidenceStage.VALIDATION,
            new StageBudget(Map.of(
                ResourceKind.WALL_CLOCK_MILLIS, 1_000L,
                ResourceKind.VALIDATION_CHECKS, 2L)),
            EvidenceStage.COUNTEREXAMPLE_SEARCH,
            new StageBudget(Map.of(
                ResourceKind.WALL_CLOCK_MILLIS, 1_000L,
                ResourceKind.COUNTEREXAMPLE_ATTEMPTS, 2L)),
            EvidenceStage.PROOF,
            new StageBudget(Map.of(
                ResourceKind.WALL_CLOCK_MILLIS, 2_000L,
                ResourceKind.PROOF_ATTEMPTS, 2L))));
    }

    private static List<BranchSnapshot> snapshots() {
        Set<EvidenceStage> mandatory = EnumSet.allOf(EvidenceStage.class);
        return List.of(
            BranchSnapshot.create(
                "seed-algebra",
                "algebra",
                BranchStatus.ELIGIBLE_INCOMPLETE,
                mandatory,
                EnumSet.of(EvidenceStage.GENERATION),
                2,
                300,
                -1),
            BranchSnapshot.create(
                "seed-rational",
                "rational",
                BranchStatus.ELIGIBLE_INCOMPLETE,
                mandatory,
                EnumSet.of(EvidenceStage.GENERATION),
                2,
                350,
                -1));
    }

    private static List<SeedExpression> seeds() {
        return List.of(
            new SeedExpression(
                "seed-algebra",
                "m * 2 + m * 3",
                "structural-seed-generator",
                "algebra",
                List.of("target-free"),
                List.of()),
            new SeedExpression(
                "seed-rational",
                "(p / q) * 4 + (p / q) * 5",
                "structural-seed-generator",
                "rational",
                List.of("target-free"),
                List.of("q != 0")));
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
