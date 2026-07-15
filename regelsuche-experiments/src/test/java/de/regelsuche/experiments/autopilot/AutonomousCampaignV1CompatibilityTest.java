package de.regelsuche.experiments.autopilot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.regelsuche.experiments.autopilot.AutonomousResearchBrief.AllocationPolicy;
import de.regelsuche.experiments.autopilot.AutonomousResearchBrief.CampaignBudget;
import de.regelsuche.experiments.autopilot.AutonomousResearchBrief.EvidenceStage;
import de.regelsuche.experiments.autopilot.AutonomousResearchBrief.ResourceKind;
import de.regelsuche.experiments.autopilot.AutonomousResearchBrief.StageBudget;
import de.regelsuche.experiments.autopilot.AutonomousResearchBrief.StructuralBounds;
import de.regelsuche.experiments.autopilot.DeterministicCampaignPlanner.BranchSnapshot;
import de.regelsuche.experiments.autopilot.DeterministicCampaignPlanner.BranchStatus;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AutonomousCampaignV1CompatibilityTest {
    private static final String EXPECTED_BRIEF_HASH =
        "sha256:b1aa8dce6924467390e2a89687678abcd54ba70925e650370faa1b151ae84359";
    private static final String EXPECTED_LEDGER_HASH =
        "sha256:7129908aac01fc0f0ee0cbeef91bc02c6537b4e0981ddbee2ae54953de464e77";
    private static final String EXPECTED_PLAN_HASH =
        "sha256:3a66edc6a6bad32ca5338770104d7edf5dd1b5d6a9ea8804a7ce0d445908be50";

    @Test
    void v2ContractsDoNotChangeV1CanonicalHashes() throws Exception {
        AutonomousResearchBrief brief = brief();
        CampaignBudgetLedger ledger = CampaignBudgetLedger.configured(brief);
        var plan = new DeterministicCampaignPlanner().plan(
            brief, ledger, branches());
        Path output = Path.of(
            "build", "reports", "autopilot-v2-dag", "v1-compatibility.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output,
            "{\"briefHash\":\"" + brief.contentHash()
                + "\",\"ledgerHash\":\"" + ledger.contentHash()
                + "\",\"planHash\":\"" + plan.contentHash() + "\"}");

        assertEquals(EXPECTED_BRIEF_HASH, brief.contentHash());
        assertEquals(EXPECTED_LEDGER_HASH, ledger.contentHash());
        assertEquals(EXPECTED_PLAN_HASH, plan.contentHash());
    }

    private static AutonomousResearchBrief brief() {
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
            budget());
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
