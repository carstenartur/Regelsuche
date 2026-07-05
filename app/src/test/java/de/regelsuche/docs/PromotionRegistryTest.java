package de.regelsuche.docs;

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
            "discovery-campaign-4"
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
            structured
        );

        PromotionRegistry.Registry merged = registry.build(List.of(higherStageStatusOnly, lowerStageStructured));
        PromotionRecord record = merged.records().getFirst();

        assertTrue(record.ablationEvidence().hasStructuredMetrics());
        assertEquals(structured.ablationStatus(), record.ablationEvidence().ablationStatus());
        assertEquals(record.ablationEvidence().ablationStatus(), record.ablationStatus());
    }
}
