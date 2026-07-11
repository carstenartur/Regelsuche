package de.regelsuche.docs;

import de.regelsuche.proof.ProofPolicy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublicEvidenceGateTest {
    private final PublicEvidenceGate gate = new PublicEvidenceGate();

    @Test
    void acceptsNewAndVariantCandidates() {
        PromotionRecord record = record("candidate-a", PromotionStage.PROMOTED, "AGREE", "DEGRADED", false, false, true, "op", "pack", List.of("rule"));

        assertTrue(gate.evaluate(record, NoveltyStatus.NEW).accepted());
        assertTrue(gate.evaluate(record, NoveltyStatus.VARIANT).accepted());
        assertTrue(record.galleryEligible(NoveltyStatus.NEW));
        assertTrue(record.galleryEligible(NoveltyStatus.VARIANT));
    }

    @Test
    void rejectsFallbackMissingAblationOracleDisagreementAndDuplicateNovelty() {
        assertReason(record("fallback", PromotionStage.PROMOTED, "AGREE", "DEGRADED", false, true, true, "op", "pack", List.of("rule")), NoveltyStatus.NEW, "fallback=true");
        assertReason(record("ablation", PromotionStage.PROMOTED, "AGREE", "UNCHANGED", false, false, true, "op", "pack", List.of("rule")), NoveltyStatus.NEW, "ablation=UNCHANGED");
        assertReason(record("oracle", PromotionStage.PROMOTED, "DISAGREE", "DEGRADED", false, false, true, "op", "pack", List.of("rule")), NoveltyStatus.NEW, "oracle=DISAGREE");
        assertReason(record("duplicate", PromotionStage.PROMOTED, "AGREE", "DEGRADED", false, false, true, "op", "pack", List.of("rule")), NoveltyStatus.DUPLICATE, "novelty=DUPLICATE");
    }

    @Test
    void rejectsStatusOnlyAblationEvenWhenStatusSaysDegraded() {
        PromotionRecord statusOnly = new PromotionRecord(
            "status-only",
            "campaign",
            "2026-01-01",
            "family",
            PromotionStage.PROMOTED,
            "x^2 + 6*x + 5",
            "(x + 1) * (x + 5)",
            "AGREE",
            "oracle evidence",
            "DEGRADED",
            "op",
            "pack",
            List.of(),
            "rationale",
            List.of("rule"),
            true,
            List.of(),
            true,
            false,
            false,
            true,
            "",
            List.of(),
            false,
            "",
            AblationEvidence.statusOnly("DEGRADED"),
            ProofPolicy.PROOF_OPTIONAL,
            ""
        );

        PublicEvidenceGate.GateDecision decision = gate.evaluate(statusOnly, NoveltyStatus.NEW);

        assertFalse(decision.accepted());
        assertTrue(decision.rejectionReasons().contains("ablation=missing-structured"));
    }

    @Test
    void rejectsMissingSearchEvidence() {
        PromotionRecord record = record("missing", PromotionStage.PROMOTED, "AGREE", "DEGRADED", false, false, false, "", "", List.of());

        PublicEvidenceGate.GateDecision decision = gate.evaluate(record, NoveltyStatus.NEW);

        assertFalse(decision.accepted());
        assertTrue(decision.rejectionReasons().contains("pathSource!=REGELSUCHE_SEARCH"));
        assertTrue(decision.rejectionReasons().contains("visible-graph=insufficient"));
        assertTrue(decision.rejectionReasons().contains("operator=missing"));
        assertTrue(decision.rejectionReasons().contains("pack=missing"));
    }

    @Test
    void missingProvenanceAloneDoesNotTriggerVisibleGraphInsufficient() {
        PromotionRecord record = record("prov-only", PromotionStage.PROMOTED, "AGREE", "DEGRADED", false, false, true, "", "", List.of("rule"));

        PublicEvidenceGate.GateDecision decision = gate.evaluate(record, NoveltyStatus.NEW);

        assertFalse(decision.accepted());
        assertTrue(decision.rejectionReasons().contains("operator=missing"));
        assertTrue(decision.rejectionReasons().contains("pack=missing"));
        assertFalse(decision.rejectionReasons().contains("visible-graph=insufficient"),
            decision.rejectionReasons().toString());
    }

    @Test
    void writesRejectionReport(@TempDir Path tempDir) throws Exception {
        PromotionRecord accepted = record("accepted", PromotionStage.PROMOTED, "AGREE", "DEGRADED", false, false, true, "op", "pack", List.of("rule"));
        PromotionRecord rejected = record("blocked", PromotionStage.PROMOTED, "AGREE", "DEGRADED", false, true, true, "op", "pack", List.of("rule"));

        PublicEvidenceGate.GateReport report = gate.write(tempDir, List.of(accepted, rejected));

        assertEquals(1, report.acceptedCount());
        assertEquals(1, report.rejectedCount());
        assertTrue(Files.exists(tempDir.resolve("public-evidence-gate.json")));
        assertTrue(Files.exists(tempDir.resolve("public-evidence-rejections.md")));
        String markdown = Files.readString(tempDir.resolve("public-evidence-rejections.md"), StandardCharsets.UTF_8);
        assertTrue(markdown.contains("blocked"));
        assertTrue(markdown.contains("fallback=true"));
    }

    private void assertReason(PromotionRecord record, NoveltyStatus novelty, String reason) {
        PublicEvidenceGate.GateDecision decision = gate.evaluate(record, novelty);
        assertFalse(decision.accepted());
        assertTrue(decision.rejectionReasons().contains(reason), decision.rejectionReasons().toString());
    }

    private PromotionRecord record(
        String id,
        PromotionStage stage,
        String oracle,
        String ablation,
        boolean curated,
        boolean fallback,
        boolean evidence,
        String operator,
        String pack,
        List<String> path
    ) {
        AblationEvidence ablationEvidence = "UNCHANGED".equals(ablation)
            ? AblationEvidence.compare(true, 3, 30, true, 3, 30, "test ablation")
            : AblationEvidence.compare(true, 1, 5, true, 3, 30, "test ablation");
        return new PromotionRecord(
            id,
            "campaign",
            "2026-01-01",
            "family",
            stage,
            "x^2 + 6*x + 5",
            "(x + 1) * (x + 5)",
            oracle,
            "oracle evidence",
            ablation,
            operator,
            pack,
            List.of(),
            "rationale",
            path,
            "DEGRADED".equals(ablation),
            List.of(),
            evidence,
            curated,
            fallback,
            true,
            "",
            List.of(),
            false,
            "",
            ablationEvidence,
            ProofPolicy.PROOF_OPTIONAL,
            ""
        );
    }
}
