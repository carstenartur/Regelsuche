package de.regelsuche.experiments.autopilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.AggregateDecision;
import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.BranchType;
import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.DecisionScope;
import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.DecisionStatus;
import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.EvidenceBranch;
import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.ExecutionStatus;
import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.MiningCandidate;
import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.RejectedCluster;
import de.regelsuche.experiments.autopilot.AutonomousResearchBrief.AllocationPolicy;
import de.regelsuche.experiments.autopilot.AutonomousResearchBrief.CampaignBudget;
import de.regelsuche.experiments.autopilot.AutonomousResearchBrief.StageBudget;
import de.regelsuche.experiments.autopilot.AutonomousResearchBrief.StructuralBounds;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.EvidenceStage;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.ResourceKind;
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

class AutonomousEvidenceDagV2Test {
    private static final String V1_BRIEF_HASH =
        "sha256:b1aa8dce6924467390e2a89687678abcd54ba70925e650370faa1b151ae84359";
    private static final String V1_LEDGER_HASH =
        "sha256:7129908aac01fc0f0ee0cbeef91bc02c6537b4e0981ddbee2ae54953de464e77";
    private static final String V1_PLAN_HASH =
        "sha256:3a66edc6a6bad32ca5338770104d7edf5dd1b5d6a9ea8804a7ce0d445908be50";

    @Test
    void v1GoldenHashesRemainUnchangedBeforeV2IsConstructed() {
        AutonomousResearchBrief brief = v1Brief();
        CampaignBudgetLedger ledger = CampaignBudgetLedger.configured(brief);
        var plan = new DeterministicCampaignPlanner().plan(
            brief, ledger, v1Branches());

        assertEquals(V1_BRIEF_HASH, brief.contentHash());
        assertEquals(V1_LEDGER_HASH, ledger.contentHash());
        assertEquals(V1_PLAN_HASH, plan.contentHash());
    }

    @Test
    void v2AddsExplicitNoveltyAndLifecycleStagesWithoutPromotion() {
        AutonomousResearchBriefV2 brief = v2Brief();

        assertEquals(V1_BRIEF_HASH, brief.v1BriefHash());
        assertTrue(brief.enabledStages().contains(EvidenceStage.PROJECT_NOVELTY));
        assertTrue(brief.enabledStages().contains(EvidenceStage.LIFECYCLE_HANDOFF));
        assertEquals("NOT_EVALUATED", brief.promotionStatus());
        assertEquals("NOT_EVALUATED", brief.publicEvidenceStatus());
        assertFalse(brief.toCanonicalJson().contains("targetExpression"));
        assertFalse(brief.toCanonicalJson().contains("expectedAnswer"));
    }

    @Test
    void aggregateDecisionAndOutputsAreStableUnderInputPermutation() {
        AutonomousResearchBriefV2 brief = v2Brief();
        List<EvidenceBranch> inputs = observations();
        List<EvidenceBranch> reversedInputs = new ArrayList<>(inputs);
        java.util.Collections.reverse(reversedInputs);

        AggregateDecision firstDecision = decision(brief, inputs);
        AggregateDecision secondDecision = decision(brief, reversedInputs);
        assertEquals(DecisionScope.AGGREGATE, firstDecision.scope());
        assertEquals(DecisionStatus.READY, firstDecision.status());
        assertEquals(firstDecision.contentHash(), secondDecision.contentHash());
        assertEquals(firstDecision.toCanonicalJson(), secondDecision.toCanonicalJson());

        var firstReport = report(false);
        var secondReport = report(true);
        var firstReceipt = AutonomousEvidenceDagV2.executeFormation(
            firstDecision, firstReport);
        var secondReceipt = AutonomousEvidenceDagV2.executeFormation(
            secondDecision, secondReport);

        assertEquals(firstReport.reportHash(), secondReport.reportHash());
        assertEquals(firstReceipt.contentHash(), secondReceipt.contentHash());
        assertEquals(firstReceipt.toCanonicalJson(), secondReceipt.toCanonicalJson());
        assertEquals(ExecutionStatus.COMPLETED, firstReceipt.status());
        assertEquals(
            List.of("candidate:candidate-1", "candidate:candidate-2"),
            firstReceipt.outputBranches().stream()
                .map(AutonomousEvidenceDagV2.OutputBranch::branchId)
                .toList());
    }

    @Test
    void eachCandidateLineageContainsExactlyItsSupportingObservationBranches() {
        AggregateDecision decision = decision(v2Brief(), observations());
        var receipt = AutonomousEvidenceDagV2.executeFormation(
            decision, report(false));

        var first = receipt.lineages().stream()
            .filter(lineage -> lineage.candidateId().equals("candidate-1"))
            .findFirst()
            .orElseThrow();
        var second = receipt.lineages().stream()
            .filter(lineage -> lineage.candidateId().equals("candidate-2"))
            .findFirst()
            .orElseThrow();

        assertEquals(
            List.of("obs-a", "obs-b"),
            first.sourceBranches().stream()
                .map(AutonomousEvidenceDagV2.SourceBranchRef::branchId)
                .toList());
        assertEquals(
            List.of("obs-b", "obs-c"),
            second.sourceBranches().stream()
                .map(AutonomousEvidenceDagV2.SourceBranchRef::branchId)
                .toList());
        assertTrue(first.sourceBranches().stream().allMatch(source ->
            source.snapshotHash().matches("sha256:[0-9a-f]{64}")
                && source.evidenceHash().matches("sha256:[0-9a-f]{64}")));
    }

    @Test
    void zeroConjecturesRemainAnExplicitSuccessfulAggregateObservation() {
        AggregateDecision decision = decision(v2Brief(), observations());
        var report = AutonomousMiningReportV2.create(
            "campaign-zero",
            hash("inventory"),
            List.of(),
            List.of(new RejectedCluster(
                "cluster-too-generic",
                "insufficient structural specificity",
                hash("rejected-cluster"))));

        var receipt = AutonomousEvidenceDagV2.executeFormation(decision, report);

        assertEquals(ExecutionStatus.ZERO_OUTPUT, receipt.status());
        assertTrue(receipt.outputBranches().isEmpty());
        assertTrue(receipt.lineages().isEmpty());
        assertEquals(1, receipt.rejectedClusters().size());
        assertEquals("NOT_EVALUATED", receipt.promotionStatus());
        assertEquals("NOT_EVALUATED", receipt.publicEvidenceStatus());
    }

    @Test
    void insufficientOrDuplicateSupportCannotCreateCandidateBranches() {
        AggregateDecision blocked = decision(
            v2Brief(), List.of(observations().getFirst()));
        assertEquals(DecisionStatus.BLOCKED, blocked.status());
        assertTrue(blocked.blockers().stream().anyMatch(blocker ->
            blocker.startsWith("insufficient-input-count=")));
        var blockedReceipt = AutonomousEvidenceDagV2.executeFormation(
            blocked,
            AutonomousMiningReportV2.create(
                "blocked-campaign", hash("inventory"), List.of(), List.of()));
        assertEquals(ExecutionStatus.BLOCKED, blockedReceipt.status());

        AggregateDecision ready = decision(v2Brief(), observations());
        MiningCandidate duplicateSupport = new MiningCandidate(
            "candidate-duplicate-support",
            List.of("obs-a", "obs-a"),
            hash("duplicate-support-evidence"));
        var rejectedReceipt = AutonomousEvidenceDagV2.executeFormation(
            ready,
            AutonomousMiningReportV2.create(
                "duplicate-support-campaign",
                hash("inventory"),
                List.of(duplicateSupport),
                List.of()));
        assertEquals(ExecutionStatus.ZERO_OUTPUT, rejectedReceipt.status());
        assertTrue(rejectedReceipt.outputBranches().isEmpty());
        assertEquals(1, rejectedReceipt.candidateRejections().size());
        assertTrue(rejectedReceipt.candidateRejections().getFirst().blockers()
            .contains("duplicate-supporting-observation"));
    }

    @Test
    void writesCanonicalV2PlanExecutionLineageAndRoundArtifacts() throws Exception {
        AutonomousResearchBriefV2 brief = v2Brief();
        AggregateDecision decision = decision(brief, observations());
        var plan = AutonomousEvidenceDagV2.plan(brief, List.of(decision));
        var receipt = AutonomousEvidenceDagV2.executeFormation(
            decision, report(false));
        var round = AutonomousEvidenceDagV2.round(
            plan, receipt, hash("next-v2-plan"));
        Path directory = Path.of("build", "reports", "autopilot-v2-contracts");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("brief-v2.json"), brief.toCanonicalJson());
        Files.writeString(directory.resolve("plan-v2.json"), plan.toCanonicalJson());
        Files.writeString(directory.resolve("execution-v2.json"),
            receipt.toCanonicalJson());
        Files.writeString(directory.resolve("round-v2.json"), round.toCanonicalJson());

        assertEquals(AutonomousEvidenceDagV2.PLAN_SCHEMA, plan.schema());
        assertEquals(AutonomousEvidenceDagV2.EXECUTION_SCHEMA, receipt.schema());
        assertEquals(AutonomousEvidenceDagV2.ROUND_SCHEMA, round.schema());
        assertFalse(round.roundDecisionIsMathematicalEvidence());
        assertEquals("NOT_EVALUATED", round.promotionStatus());
        assertEquals("NOT_EVALUATED", round.publicEvidenceStatus());
        assertEquals(brief.toCanonicalJson(),
            Files.readString(directory.resolve("brief-v2.json")));
        assertEquals(receipt.toCanonicalJson(),
            Files.readString(directory.resolve("execution-v2.json")));
    }

    private static AggregateDecision decision(
        AutonomousResearchBriefV2 brief,
        List<EvidenceBranch> inputs
    ) {
        return AutonomousEvidenceDagV2.candidateFormationDecision(
            brief,
            inputs,
            Map.of(
                ResourceKind.WALL_CLOCK_MILLIS, 2_000L,
                ResourceKind.CANDIDATES, 4L));
    }

    private static AutonomousResearchBriefV2 v2Brief() {
        return AutonomousResearchBriefV2.create(
            "aggregate-open-target-v2",
            v1Brief(),
            EnumSet.allOf(EvidenceStage.class),
            Map.of(
                EvidenceStage.GENERATION, v2Budget(
                    ResourceKind.WALL_CLOCK_MILLIS, 10_000L,
                    ResourceKind.GENERATED_STATES, 1_000L,
                    ResourceKind.EXPLORED_STATES, 500L),
                EvidenceStage.CANDIDATE_FORMATION, v2Budget(
                    ResourceKind.WALL_CLOCK_MILLIS, 2_000L,
                    ResourceKind.CANDIDATES, 10L),
                EvidenceStage.VALIDATION, v2Budget(
                    ResourceKind.WALL_CLOCK_MILLIS, 2_000L,
                    ResourceKind.VALIDATION_CHECKS, 8L),
                EvidenceStage.COUNTEREXAMPLE_SEARCH, v2Budget(
                    ResourceKind.WALL_CLOCK_MILLIS, 2_000L,
                    ResourceKind.COUNTEREXAMPLE_ATTEMPTS, 8L),
                EvidenceStage.PROJECT_NOVELTY, v2Budget(
                    ResourceKind.WALL_CLOCK_MILLIS, 1_000L,
                    ResourceKind.NOVELTY_CHECKS, 4L),
                EvidenceStage.PROOF, v2Budget(
                    ResourceKind.WALL_CLOCK_MILLIS, 3_000L,
                    ResourceKind.PROOF_ATTEMPTS, 4L),
                EvidenceStage.LIFECYCLE_HANDOFF, v2Budget(
                    ResourceKind.WALL_CLOCK_MILLIS, 1_000L,
                    ResourceKind.LIFECYCLE_HANDOFFS, 4L)),
            2,
            2,
            2,
            "candidate");
    }

    private static AutonomousResearchBriefV2.StageBudget v2Budget(
        ResourceKind firstResource,
        long firstAmount,
        ResourceKind secondResource,
        long secondAmount
    ) {
        return new AutonomousResearchBriefV2.StageBudget(Map.of(
            firstResource, firstAmount,
            secondResource, secondAmount));
    }

    private static AutonomousResearchBriefV2.StageBudget v2Budget(
        ResourceKind firstResource,
        long firstAmount,
        ResourceKind secondResource,
        long secondAmount,
        ResourceKind thirdResource,
        long thirdAmount
    ) {
        return new AutonomousResearchBriefV2.StageBudget(Map.of(
            firstResource, firstAmount,
            secondResource, secondAmount,
            thirdResource, thirdAmount));
    }

    private static List<EvidenceBranch> observations() {
        return List.of(
            observation("obs-a", "algebra-a", "alpha-a"),
            observation("obs-b", "algebra-b", "alpha-b"),
            observation("obs-c", "rational-c", "alpha-c"));
    }

    private static EvidenceBranch observation(
        String branchId,
        String family,
        String alpha
    ) {
        return new EvidenceBranch(
            branchId,
            BranchType.OBSERVATION,
            family,
            hash(alpha),
            hash("snapshot-" + branchId),
            hash("evidence-" + branchId),
            true);
    }

    private static AutonomousEvidenceDagV2.MiningReport report(boolean reversed) {
        List<MiningCandidate> candidates = new ArrayList<>(List.of(
            new MiningCandidate(
                "candidate-1",
                List.of("obs-b", "obs-a"),
                hash("candidate-1-evidence")),
            new MiningCandidate(
                "candidate-2",
                List.of("obs-c", "obs-b"),
                hash("candidate-2-evidence"))));
        List<RejectedCluster> rejected = new ArrayList<>(List.of(
            new RejectedCluster(
                "cluster-alpha-only",
                "alpha rename only",
                hash("cluster-alpha-only"))));
        if (reversed) {
            java.util.Collections.reverse(candidates);
            java.util.Collections.reverse(rejected);
        }
        return AutonomousMiningReportV2.create(
            "campaign-two-candidates",
            hash("inventory"),
            candidates,
            rejected);
    }

    private static AutonomousResearchBrief v1Brief() {
        return AutonomousResearchBrief.create(
            "autopilot-characterization-v1",
            List.of("algebra", "rational"),
            List.of("bounded-expression-generator", "structural-seed-generator"),
            new StructuralBounds(6, 128, 12),
            hash("inventory"),
            hash("packs"),
            hash("model"),
            424242L,
            EnumSet.allOf(AutonomousResearchBrief.EvidenceStage.class),
            List.of("symbolic-equivalence", "numeric-counterexample-search"),
            2,
            2,
            true,
            AllocationPolicy.EVIDENCE_COMPLETION_FIRST,
            v1Budget());
    }

    private static CampaignBudget v1Budget() {
        return new CampaignBudget(Map.of(
            AutonomousResearchBrief.EvidenceStage.GENERATION,
            new StageBudget(Map.of(
                AutonomousResearchBrief.ResourceKind.WALL_CLOCK_MILLIS, 10_000L,
                AutonomousResearchBrief.ResourceKind.GENERATED_STATES, 1_000L,
                AutonomousResearchBrief.ResourceKind.EXPLORED_STATES, 500L)),
            AutonomousResearchBrief.EvidenceStage.CANDIDATE_FORMATION,
            new StageBudget(Map.of(
                AutonomousResearchBrief.ResourceKind.WALL_CLOCK_MILLIS, 2_000L,
                AutonomousResearchBrief.ResourceKind.CANDIDATES, 10L)),
            AutonomousResearchBrief.EvidenceStage.VALIDATION,
            new StageBudget(Map.of(
                AutonomousResearchBrief.ResourceKind.WALL_CLOCK_MILLIS, 2_000L,
                AutonomousResearchBrief.ResourceKind.VALIDATION_CHECKS, 8L)),
            AutonomousResearchBrief.EvidenceStage.COUNTEREXAMPLE_SEARCH,
            new StageBudget(Map.of(
                AutonomousResearchBrief.ResourceKind.WALL_CLOCK_MILLIS, 2_000L,
                AutonomousResearchBrief.ResourceKind.COUNTEREXAMPLE_ATTEMPTS, 8L)),
            AutonomousResearchBrief.EvidenceStage.PROOF,
            new StageBudget(Map.of(
                AutonomousResearchBrief.ResourceKind.WALL_CLOCK_MILLIS, 3_000L,
                AutonomousResearchBrief.ResourceKind.PROOF_ATTEMPTS, 4L))));
    }

    private static List<DeterministicCampaignPlanner.BranchSnapshot> v1Branches() {
        Set<AutonomousResearchBrief.EvidenceStage> all =
            EnumSet.allOf(AutonomousResearchBrief.EvidenceStage.class);
        return List.of(
            v1Branch("needs-validation", "algebra", all,
                EnumSet.of(
                    AutonomousResearchBrief.EvidenceStage.GENERATION,
                    AutonomousResearchBrief.EvidenceStage.CANDIDATE_FORMATION),
                3, 400, -1,
                DeterministicCampaignPlanner.BranchStatus.ELIGIBLE_INCOMPLETE),
            v1Branch("needs-proof", "rational", all,
                EnumSet.of(
                    AutonomousResearchBrief.EvidenceStage.GENERATION,
                    AutonomousResearchBrief.EvidenceStage.CANDIDATE_FORMATION,
                    AutonomousResearchBrief.EvidenceStage.VALIDATION,
                    AutonomousResearchBrief.EvidenceStage.COUNTEREXAMPLE_SEARCH),
                4, 150, 800,
                DeterministicCampaignPlanner.BranchStatus.ELIGIBLE_INCOMPLETE),
            v1Branch("duplicate-branch", "algebra", all,
                EnumSet.of(
                    AutonomousResearchBrief.EvidenceStage.GENERATION,
                    AutonomousResearchBrief.EvidenceStage.CANDIDATE_FORMATION),
                2, 0, 900,
                DeterministicCampaignPlanner.BranchStatus.DUPLICATE),
            v1Branch("disproved-branch", "rational", all,
                EnumSet.of(
                    AutonomousResearchBrief.EvidenceStage.GENERATION,
                    AutonomousResearchBrief.EvidenceStage.CANDIDATE_FORMATION,
                    AutonomousResearchBrief.EvidenceStage.VALIDATION),
                2, 1000, 950,
                DeterministicCampaignPlanner.BranchStatus.DISPROVED),
            v1Branch("unsafe-branch", "algebra", all,
                EnumSet.of(AutonomousResearchBrief.EvidenceStage.GENERATION),
                1, 800, -1,
                DeterministicCampaignPlanner.BranchStatus.UNSAFE));
    }

    private static DeterministicCampaignPlanner.BranchSnapshot v1Branch(
        String id,
        String family,
        Set<AutonomousResearchBrief.EvidenceStage> mandatory,
        Set<AutonomousResearchBrief.EvidenceStage> completed,
        int support,
        int risk,
        int interestingness,
        DeterministicCampaignPlanner.BranchStatus status
    ) {
        return DeterministicCampaignPlanner.BranchSnapshot.create(
            id, family, status, mandatory, completed,
            support, risk, interestingness);
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
