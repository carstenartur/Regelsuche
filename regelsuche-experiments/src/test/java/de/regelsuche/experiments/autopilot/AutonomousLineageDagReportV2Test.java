package de.regelsuche.experiments.autopilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.BranchType;
import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.EvidenceBranch;
import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.MiningCandidate;
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
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AutonomousLineageDagReportV2Test {
    @Test
    void standaloneLineageHashMatchesTheRoundBinding() throws Exception {
        AutonomousResearchBriefV2 brief = brief();
        var decision = AutonomousEvidenceDagV2.candidateFormationDecision(
            brief,
            List.of(observation("obs-a", "family-a"),
                observation("obs-b", "family-b")),
            Map.of(ResourceKind.CANDIDATES, 1L));
        var plan = AutonomousEvidenceDagV2.plan(brief, List.of(decision));
        var report = AutonomousMiningReportV2.create(
            "lineage-campaign",
            hash("inventory"),
            List.of(new MiningCandidate(
                "candidate-lineage",
                List.of("obs-b", "obs-a"),
                hash("convergence"))),
            List.of());
        var execution = AutonomousEvidenceDagV2.executeFormation(decision, report);
        AutonomousLineageDagReportV2 lineage =
            AutonomousLineageDagReportV2.create(execution);
        var round = AutonomousEvidenceDagV2.round(
            plan, execution, hash("next-plan"));
        Path output = Path.of(
            "build", "reports", "autopilot-v2-contracts", "lineage-v2.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, lineage.toCanonicalJson());

        assertEquals(lineage.contentHash(), round.lineageDagHash());
        assertEquals(execution.contentHash(), lineage.executionHash());
        assertEquals(1, lineage.lineages().size());
        assertEquals(
            List.of("obs-a", "obs-b"),
            lineage.lineages().getFirst().sourceBranches().stream()
                .map(AutonomousEvidenceDagV2.SourceBranchRef::branchId)
                .toList());
        assertEquals(lineage.toCanonicalJson(), Files.readString(output));
        assertTrue(lineage.contentHash().matches("sha256:[0-9a-f]{64}"));
    }

    private static EvidenceBranch observation(String id, String family) {
        return new EvidenceBranch(
            id,
            BranchType.OBSERVATION,
            family,
            hash("alpha-" + id),
            hash("snapshot-" + id),
            hash("evidence-" + id),
            true);
    }

    private static AutonomousResearchBriefV2 brief() {
        AutonomousResearchBrief v1 = AutonomousResearchBrief.create(
            "lineage-v1",
            List.of("algebra"),
            List.of("structural-seed-generator"),
            new StructuralBounds(4, 64, 4),
            hash("inventory"),
            hash("packs"),
            hash("model"),
            17L,
            EnumSet.allOf(AutonomousResearchBrief.EvidenceStage.class),
            List.of("target-free-mining"),
            2,
            2,
            true,
            AllocationPolicy.EVIDENCE_COMPLETION_FIRST,
            new CampaignBudget(Map.of(
                AutonomousResearchBrief.EvidenceStage.GENERATION,
                new StageBudget(Map.of(
                    AutonomousResearchBrief.ResourceKind.GENERATED_STATES, 100L)),
                AutonomousResearchBrief.EvidenceStage.CANDIDATE_FORMATION,
                new StageBudget(Map.of(
                    AutonomousResearchBrief.ResourceKind.CANDIDATES, 2L)),
                AutonomousResearchBrief.EvidenceStage.VALIDATION,
                new StageBudget(Map.of(
                    AutonomousResearchBrief.ResourceKind.VALIDATION_CHECKS, 2L)),
                AutonomousResearchBrief.EvidenceStage.COUNTEREXAMPLE_SEARCH,
                new StageBudget(Map.of(
                    AutonomousResearchBrief.ResourceKind.COUNTEREXAMPLE_ATTEMPTS, 2L)),
                AutonomousResearchBrief.EvidenceStage.PROOF,
                new StageBudget(Map.of(
                    AutonomousResearchBrief.ResourceKind.PROOF_ATTEMPTS, 2L)))));
        return AutonomousResearchBriefV2.create(
            "lineage-v2",
            v1,
            EnumSet.allOf(EvidenceStage.class),
            Map.of(
                EvidenceStage.GENERATION,
                budget(ResourceKind.GENERATED_STATES),
                EvidenceStage.CANDIDATE_FORMATION,
                budget(ResourceKind.CANDIDATES),
                EvidenceStage.VALIDATION,
                budget(ResourceKind.VALIDATION_CHECKS),
                EvidenceStage.COUNTEREXAMPLE_SEARCH,
                budget(ResourceKind.COUNTEREXAMPLE_ATTEMPTS),
                EvidenceStage.PROJECT_NOVELTY,
                budget(ResourceKind.NOVELTY_CHECKS),
                EvidenceStage.PROOF,
                budget(ResourceKind.PROOF_ATTEMPTS),
                EvidenceStage.LIFECYCLE_HANDOFF,
                budget(ResourceKind.LIFECYCLE_HANDOFFS)),
            2,
            2,
            2,
            "candidate");
    }

    private static AutonomousResearchBriefV2.StageBudget budget(
        ResourceKind resource
    ) {
        return new AutonomousResearchBriefV2.StageBudget(Map.of(resource, 2L));
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
