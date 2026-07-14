package de.regelsuche.experiments.autopilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.experiments.autopilot.AutonomousResearchBrief.AllocationPolicy;
import de.regelsuche.experiments.autopilot.AutonomousResearchBrief.CampaignBudget;
import de.regelsuche.experiments.autopilot.AutonomousResearchBrief.EvidenceStage;
import de.regelsuche.experiments.autopilot.AutonomousResearchBrief.ResourceKind;
import de.regelsuche.experiments.autopilot.AutonomousResearchBrief.StageBudget;
import de.regelsuche.experiments.autopilot.AutonomousResearchBrief.StructuralBounds;
import de.regelsuche.experiments.autopilot.DeterministicCampaignPlanner.AllocationDecision;
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
import java.util.Set;
import org.junit.jupiter.api.Test;

class DeterministicCampaignPlannerTest {
    private final DeterministicCampaignPlanner planner =
        new DeterministicCampaignPlanner();

    @Test
    void researchBriefHasNoTargetOrExpectedAnswerInput() {
        List<String> componentNames = java.util.Arrays.stream(
                AutonomousResearchBrief.class.getRecordComponents())
            .map(component -> component.getName().toLowerCase())
            .toList();

        assertTrue(componentNames.stream().noneMatch(name ->
            name.contains("target") || name.contains("expectedanswer")));
        assertFalse(brief().toCanonicalJson().contains("targetExpression"));
        assertFalse(brief().toCanonicalJson().contains("expectedAnswer"));
    }

    @Test
    void ledgerBalancesConfiguredExecutedSkippedAndRemainingWork() {
        CampaignBudgetLedger ledger = CampaignBudgetLedger.configured(brief());
        CampaignBudgetLedger updated = ledger
            .record(EvidenceStage.VALIDATION, ResourceKind.VALIDATION_CHECKS, 2, 1)
            .record(EvidenceStage.VALIDATION, ResourceKind.WALL_CLOCK_MILLIS, 500, 250);

        CampaignBudgetLedger.BudgetLine checks = updated.lines().stream()
            .filter(line -> line.stage() == EvidenceStage.VALIDATION
                && line.resource() == ResourceKind.VALIDATION_CHECKS)
            .findFirst()
            .orElseThrow();
        assertEquals(
            checks.configured(),
            checks.executed() + checks.skipped() + checks.remaining());
        assertThrows(
            IllegalArgumentException.class,
            () -> updated.record(
                EvidenceStage.VALIDATION,
                ResourceKind.VALIDATION_CHECKS,
                checks.remaining() + 1,
                0));
    }

    @Test
    void allocationIsDeterministicUnderBranchInputPermutation() {
        AutonomousResearchBrief brief = brief();
        CampaignBudgetLedger ledger = CampaignBudgetLedger.configured(brief);
        List<BranchSnapshot> branches = branches();
        List<BranchSnapshot> reversed = new ArrayList<>(branches);
        java.util.Collections.reverse(reversed);

        var first = planner.plan(brief, ledger, branches);
        var second = planner.plan(brief, ledger, reversed);

        assertEquals(first.contentHash(), second.contentHash());
        assertEquals(first.toCanonicalJson(), second.toCanonicalJson());
    }

    @Test
    void stoppedBranchesReceiveNoAdditionalWork() {
        var plan = planner.plan(brief(), CampaignBudgetLedger.configured(brief()), branches());

        AllocationDecision duplicate = decision(plan.decisions(), "duplicate-branch");
        AllocationDecision disproved = decision(plan.decisions(), "disproved-branch");
        AllocationDecision unsafe = decision(plan.decisions(), "unsafe-branch");
        assertEquals(DecisionKind.STOP, duplicate.kind());
        assertEquals(DecisionKind.STOP, disproved.kind());
        assertEquals(DecisionKind.STOP, unsafe.kind());
        assertTrue(duplicate.budgetDeltas().isEmpty());
        assertTrue(disproved.budgetDeltas().isEmpty());
        assertTrue(unsafe.budgetDeltas().isEmpty());
    }

    @Test
    void incompleteEligibleBranchReceivesEvidenceBudgetWithoutInterestingness() {
        var plan = planner.plan(brief(), CampaignBudgetLedger.configured(brief()), branches());

        AllocationDecision decision = decision(plan.decisions(), "needs-validation");
        assertEquals(DecisionKind.ALLOCATE, decision.kind());
        assertEquals(EvidenceStage.VALIDATION.name(), decision.stage());
        assertTrue(decision.reason().contains("interestingness=NOT_EVALUATED"));
        assertTrue(decision.budgetDeltas().stream().anyMatch(delta ->
            delta.resource() == ResourceKind.VALIDATION_CHECKS && delta.amount() == 1));
    }

    @Test
    void planNeverExceedsFactualRemainingBudgetAndNeverAcceptsCandidates()
            throws Exception {
        AutonomousResearchBrief brief = brief();
        CampaignBudgetLedger ledger = CampaignBudgetLedger.configured(brief);
        var plan = planner.plan(brief, ledger, branches());
        Path directory = Path.of("build", "reports", "autopilot-planner");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("brief.json"), brief.toCanonicalJson());
        Files.writeString(directory.resolve("ledger.json"), ledger.toCanonicalJson());
        Files.writeString(directory.resolve("plan.json"), plan.toCanonicalJson());

        plan.plannedBudget().forEach(item -> assertTrue(
            item.amount() <= ledger.remaining(item.stage(), item.resource())));
        assertFalse(plan.plannerDecisionIsMathematicalEvidence());
        assertEquals("NOT_EVALUATED", plan.promotionStatus());
        assertEquals("NOT_EVALUATED", plan.publicEvidenceStatus());
        assertTrue(plan.decisions().stream().noneMatch(decision ->
            decision.reason().toLowerCase().contains("accepted")));
        assertEquals(
            plan.toCanonicalJson(),
            Files.readString(directory.resolve("plan.json")));
    }

    @Test
    void enabledStageWithoutBudgetAndWrongStageResourcesAreRejected() {
        CampaignBudget missingValidation = new CampaignBudget(Map.of(
            EvidenceStage.GENERATION,
            new StageBudget(Map.of(ResourceKind.GENERATED_STATES, 100L)),
            EvidenceStage.CANDIDATE_FORMATION,
            new StageBudget(Map.of(ResourceKind.CANDIDATES, 2L))));
        assertThrows(
            IllegalArgumentException.class,
            () -> createBrief(missingValidation));
        CampaignBudget misplaced = new CampaignBudget(Map.of(
            EvidenceStage.GENERATION,
            new StageBudget(Map.of(ResourceKind.PROOF_ATTEMPTS, 1L)),
            EvidenceStage.CANDIDATE_FORMATION,
            new StageBudget(Map.of(ResourceKind.CANDIDATES, 2L)),
            EvidenceStage.VALIDATION,
            new StageBudget(Map.of(ResourceKind.VALIDATION_CHECKS, 2L)),
            EvidenceStage.COUNTEREXAMPLE_SEARCH,
            new StageBudget(Map.of(ResourceKind.COUNTEREXAMPLE_ATTEMPTS, 2L)),
            EvidenceStage.PROOF,
            new StageBudget(Map.of(ResourceKind.PROOF_ATTEMPTS, 2L))));
        assertThrows(
            IllegalArgumentException.class,
            () -> createBrief(misplaced));
    }

    private static AllocationDecision decision(
        List<AllocationDecision> decisions,
        String branchId
    ) {
        return decisions.stream()
            .filter(decision -> decision.branchId().equals(branchId))
            .findFirst()
            .orElseThrow();
    }

    private static AutonomousResearchBrief brief() {
        return createBrief(budget());
    }

    private static AutonomousResearchBrief createBrief(CampaignBudget budget) {
        return AutonomousResearchBrief.create(
            "autopilot-characterization-v1",
            List.of("algebra", "rational"),
            List.of("bounded-expression-generator", "structural-seed-generator"),
            new StructuralBounds(6, 128, 12),
            hash("inventory"),
            hash("packs"),
            hash("model"),
            424242L,
            EnumSet.allOf(EvidenceStage.class),
            List.of("symbolic-equivalence", "numeric-counterexample-search"),
            2,
            2,
            true,
            AllocationPolicy.EVIDENCE_COMPLETION_FIRST,
            budget);
    }

    private static CampaignBudget budget() {
        return new CampaignBudget(Map.of(
            EvidenceStage.GENERATION,
            new StageBudget(Map.of(
                ResourceKind.WALL_CLOCK_MILLIS, 10_000L,
                ResourceKind.GENERATED_STATES, 1_000L,
                ResourceKind.EXPLORED_STATES, 500L)),
            EvidenceStage.CANDIDATE_FORMATION,
            new StageBudget(Map.of(
                ResourceKind.WALL_CLOCK_MILLIS, 2_000L,
                ResourceKind.CANDIDATES, 10L)),
            EvidenceStage.VALIDATION,
            new StageBudget(Map.of(
                ResourceKind.WALL_CLOCK_MILLIS, 2_000L,
                ResourceKind.VALIDATION_CHECKS, 8L)),
            EvidenceStage.COUNTEREXAMPLE_SEARCH,
            new StageBudget(Map.of(
                ResourceKind.WALL_CLOCK_MILLIS, 2_000L,
                ResourceKind.COUNTEREXAMPLE_ATTEMPTS, 8L)),
            EvidenceStage.PROOF,
            new StageBudget(Map.of(
                ResourceKind.WALL_CLOCK_MILLIS, 3_000L,
                ResourceKind.PROOF_ATTEMPTS, 4L))));
    }

    private static List<BranchSnapshot> branches() {
        Set<EvidenceStage> all = EnumSet.allOf(EvidenceStage.class);
        return List.of(
            BranchSnapshot.create(
                "needs-validation",
                "algebra",
                BranchStatus.ELIGIBLE_INCOMPLETE,
                all,
                EnumSet.of(
                    EvidenceStage.GENERATION,
                    EvidenceStage.CANDIDATE_FORMATION),
                3,
                400,
                -1),
            BranchSnapshot.create(
                "needs-proof",
                "rational",
                BranchStatus.ELIGIBLE_INCOMPLETE,
                all,
                EnumSet.of(
                    EvidenceStage.GENERATION,
                    EvidenceStage.CANDIDATE_FORMATION,
                    EvidenceStage.VALIDATION,
                    EvidenceStage.COUNTEREXAMPLE_SEARCH),
                4,
                150,
                800),
            BranchSnapshot.create(
                "duplicate-branch",
                "algebra",
                BranchStatus.DUPLICATE,
                all,
                EnumSet.of(
                    EvidenceStage.GENERATION,
                    EvidenceStage.CANDIDATE_FORMATION),
                2,
                0,
                900),
            BranchSnapshot.create(
                "disproved-branch",
                "rational",
                BranchStatus.DISPROVED,
                all,
                EnumSet.of(
                    EvidenceStage.GENERATION,
                    EvidenceStage.CANDIDATE_FORMATION,
                    EvidenceStage.VALIDATION),
                2,
                1000,
                950),
            BranchSnapshot.create(
                "unsafe-branch",
                "algebra",
                BranchStatus.UNSAFE,
                all,
                EnumSet.of(EvidenceStage.GENERATION),
                1,
                800,
                -1));
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
