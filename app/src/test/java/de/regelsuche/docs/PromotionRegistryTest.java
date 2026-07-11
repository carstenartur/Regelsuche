package de.regelsuche.docs;

import de.regelsuche.proof.ProofPolicy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PromotionRegistryTest {
    @Test
    void mergePreservesEvidenceFieldsAndDeterministicallyUnionsCollections() {
        PromotionRegistry registry = new PromotionRegistry();
        PromotionRecord lowerStage = new PromotionRecord(
            "candidate-1",
            "campaign-a",
            "2026-01-01",
            "family",
            PromotionStage.CANDIDATE,
            "x + y",
            "(x + y)",
            "AGREE",
            "oracle-left",
            "UNCHANGED",
            "source-op",
            "pack-a",
            List.of("substitution.placeholder.A=x+y", "substitution.occurrences.A=1"),
            "left rationale",
            List.of("step-a"),
            false,
            List.of("blocker-a"),
            true,
            false,
            false,
            true,
            "",
            List.of("macro-left"),
            false,
            "",
            AblationEvidence.statusOnly("UNCHANGED"),
            ProofPolicy.PROOF_OPTIONAL,
            ""
        );
        PromotionRecord higherStage = new PromotionRecord(
            "candidate-1",
            "campaign-b",
            "2026-01-02",
            "family",
            PromotionStage.REUSED,
            "",
            "",
            "AGREE",
            "",
            "DEGRADED",
            "",
            "",
            List.of("substitution.placeholder.B=z", "substitution.occurrences.A=1"),
            "right rationale",
            List.of("step-b"),
            true,
            List.of("blocker-b", "blocker-a"),
            true,
            false,
            false,
            true,
            "macro-generated",
            List.of("macro-right"),
            true,
            "discovery-campaign-4",
            AblationEvidence.statusOnly("DEGRADED"),
            ProofPolicy.PROOF_OPTIONAL,
            ""
        );

        PromotionRegistry.Registry merged = registry.build(List.of(higherStage, lowerStage));
        PromotionRecord record = merged.records().getFirst();

        assertEquals(PromotionStage.REUSED, record.stage());
        assertEquals("campaign-b", record.sourceCampaign());

        assertEquals("x + y", record.originalExpression());
        assertEquals("(x + y)", record.discoveredStructure());
        assertEquals("oracle-left", record.oracleEvidence());

        assertEquals(List.of("substitution.occurrences.A=1", "substitution.placeholder.A=x+y", "substitution.placeholder.B=z"),
            record.assumptions());
        assertEquals(List.of("blocker-a", "blocker-b"), record.promotionBlockers());
        assertEquals(List.of("macro-left", "macro-right"), record.reusedMacroIds());
        assertTrue(record.measuredImprovement());
        assertEquals("discovery-campaign-4", record.reuseCampaign());
        assertEquals(record.ablationEvidence().ablationStatus(), record.ablationStatus());
    }

    @Test
    void mergePrefersStructuredAblationEvidenceAndKeepsStatusConsistent() {
        PromotionRegistry registry = new PromotionRegistry();
        PromotionRecord higherStageStatusOnly = new PromotionRecord(
            "candidate-2",
            "campaign-higher",
            "2026-01-02",
            "family",
            PromotionStage.REUSED,
            "",
            "",
            "AGREE",
            "",
            "DEGRADED",
            "",
            "",
            List.of(),
            "",
            List.of(),
            true,
            List.of(),
            true,
            false,
            false,
            false,
            "",
            List.of(),
            false,
            "",
            AblationEvidence.statusOnly("DEGRADED"),
            ProofPolicy.PROOF_OPTIONAL,
            ""
        );
        AblationEvidence structured = AblationEvidence.compare(
            true,
            4,
            100,
            true,
            4,
            100,
            "same search cost"
        );
        PromotionRecord lowerStageStructured = new PromotionRecord(
            "candidate-2",
            "campaign-lower",
            "2026-01-01",
            "family",
            PromotionStage.CANDIDATE,
            "x + y",
            "(x + y)",
            "AGREE",
            "",
            "DEGRADED",
            "",
            "",
            List.of(),
            "",
            List.of(),
            false,
            List.of(),
            true,
            false,
            false,
            false,
            "",
            List.of(),
            false,
            "",
            structured,
            ProofPolicy.PROOF_OPTIONAL,
            ""
        );

        PromotionRegistry.Registry merged = registry.build(List.of(higherStageStatusOnly, lowerStageStructured));
        PromotionRecord record = merged.records().getFirst();

        assertTrue(record.ablationEvidence().hasStructuredMetrics());
        assertEquals(structured.ablationStatus(), record.ablationEvidence().ablationStatus());
        assertEquals(record.ablationEvidence().ablationStatus(), record.ablationStatus());
    }

    @Test
    void mergePreservesStricterProofPolicyAndCarriesForwardExecutionStatus() {
        PromotionRegistry registry = new PromotionRegistry();
        PromotionRecord optional = new PromotionRecord(
            "candidate-3",
            "campaign-a",
            "2026-01-01",
            "family",
            PromotionStage.CANDIDATE,
            "x + y",
            "(x + y)",
            "AGREE",
            "",
            "UNCHANGED",
            "",
            "",
            List.of(),
            "",
            List.of(),
            false,
            List.of(),
            true,
            false,
            false,
            false,
            "",
            List.of(),
            false,
            "",
            AblationEvidence.statusOnly("UNCHANGED"),
            ProofPolicy.PROOF_OPTIONAL,
            "PROVER_CONFIRMED"
        );
        PromotionRecord requiredForPromotion = new PromotionRecord(
            "candidate-3",
            "campaign-b",
            "2026-01-02",
            "family",
            PromotionStage.REUSED,
            "",
            "",
            "AGREE",
            "",
            "DEGRADED",
            "",
            "",
            List.of(),
            "",
            List.of(),
            true,
            List.of(),
            true,
            false,
            false,
            false,
            "",
            List.of(),
            false,
            "",
            AblationEvidence.statusOnly("DEGRADED"),
            ProofPolicy.PROOF_REQUIRED_FOR_PROMOTION,
            "SCRIPT_GENERATED"
        );

        PromotionRegistry.Registry merged = registry.build(List.of(optional, requiredForPromotion));
        PromotionRecord record = merged.records().getFirst();

        assertEquals(ProofPolicy.PROOF_REQUIRED_FOR_PROMOTION, record.proofPolicy(),
            "merged record should use the stricter of the two policies");
        assertEquals("PROVER_CONFIRMED", record.proverExecutionStatus(),
            "merged record should carry forward PROVER_CONFIRMED over SCRIPT_GENERATED");
    }

    @Test
    void mergePreservesStrictestProofPolicyForPublicEvidence() {
        PromotionRegistry registry = new PromotionRegistry();
        PromotionRecord requiredForPromotion = new PromotionRecord(
            "candidate-4",
            "campaign-a",
            "2026-01-01",
            "family",
            PromotionStage.CANDIDATE,
            "x + y",
            "(x + y)",
            "AGREE",
            "",
            "UNCHANGED",
            "",
            "",
            List.of(),
            "",
            List.of(),
            false,
            List.of(),
            true,
            false,
            false,
            false,
            "",
            List.of(),
            false,
            "",
            AblationEvidence.statusOnly("UNCHANGED"),
            ProofPolicy.PROOF_REQUIRED_FOR_PROMOTION,
            "PROVER_FAILED"
        );
        PromotionRecord requiredForPublic = new PromotionRecord(
            "candidate-4",
            "campaign-b",
            "2026-01-02",
            "family",
            PromotionStage.REUSED,
            "",
            "",
            "AGREE",
            "",
            "DEGRADED",
            "",
            "",
            List.of(),
            "",
            List.of(),
            true,
            List.of(),
            true,
            false,
            false,
            false,
            "",
            List.of(),
            false,
            "",
            AblationEvidence.statusOnly("DEGRADED"),
            ProofPolicy.PROOF_REQUIRED_FOR_PUBLIC_EVIDENCE,
            "PROVER_TIMEOUT"
        );

        PromotionRegistry.Registry merged = registry.build(List.of(requiredForPromotion, requiredForPublic));
        PromotionRecord record = merged.records().getFirst();

        assertEquals(ProofPolicy.PROOF_REQUIRED_FOR_PUBLIC_EVIDENCE, record.proofPolicy(),
            "PROOF_REQUIRED_FOR_PUBLIC_EVIDENCE is stricter than PROOF_REQUIRED_FOR_PROMOTION");
        assertEquals("PROVER_FAILED", record.proverExecutionStatus(),
            "when neither is PROVER_CONFIRMED, prefer any non-SCRIPT_GENERATED status (left wins)");
    }
}
