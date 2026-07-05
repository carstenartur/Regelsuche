package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class AblationEvidenceTest {

    @Test
    void comparisonIsDegradedWhenDisabledRunFails() {
        AblationEvidence evidence = AblationEvidence.compare(
            true,
            2,
            10,
            false,
            -1,
            -1,
            "candidate enables path"
        );

        assertEquals("DEGRADED", evidence.ablationStatus());
        assertTrue(evidence.promotionReady());
        assertEquals(1.0d, evidence.improvementRatio());
    }

    @Test
    void comparisonIsDegradedWhenPathOrStatesImprove() {
        AblationEvidence evidence = AblationEvidence.compare(
            true,
            2,
            20,
            true,
            4,
            100,
            "shorter path"
        );

        assertEquals("DEGRADED", evidence.ablationStatus());
        assertTrue(evidence.promotionReady());
        assertTrue(evidence.improvementRatio() > 0.0d);
        assertTrue(evidence.hasStructuredMetrics());
    }

    @Test
    void comparisonIsUnchangedWhenCandidateDoesNotImproveSearch() {
        AblationEvidence evidence = AblationEvidence.compare(
            true,
            4,
            100,
            true,
            4,
            100,
            "same search cost"
        );

        assertEquals("UNCHANGED", evidence.ablationStatus());
        assertFalse(evidence.promotionReady());
        assertEquals(0.0d, evidence.improvementRatio());
    }

    @Test
    void comparisonIsBlockedWhenEnabledRunFails() {
        AblationEvidence evidence = AblationEvidence.compare(
            false,
            -1,
            12,
            true,
            2,
            5,
            "enabled run failed"
        );

        assertEquals("BLOCKED", evidence.ablationStatus());
        assertFalse(evidence.promotionReady());
    }

    @Test
    void statusOnlyEvidenceKeepsLegacyStatusButMarksMetricsUnknown() {
        AblationEvidence evidence = AblationEvidence.statusOnly("degraded", "legacy campaign status");

        assertEquals("DEGRADED", evidence.ablationStatus());
        assertTrue(evidence.promotionReady());
        assertFalse(evidence.hasStructuredMetrics());
        assertTrue(evidence.compactSummary().contains("pathLength=unknown"));
    }

    @Test
    void promotionRecordWithReuseConvertsMacroReuseMetricsIntoAblationEvidence() {
        PromotionRecord record = new PromotionRecord(
            "candidate-a",
            "discovery-campaign-1",
            "2026-01-01",
            "polynomial",
            PromotionStage.PROMOTED,
            "x^2 + 6*x + 5",
            "(x + 3)^2 - 4",
            "AGREE",
            "oracle evidence",
            "DEGRADED",
            "complete_square_bridge",
            "sympy-polynomial-basic",
            List.of(),
            "rationale",
            List.of("complete_square_bridge"),
            true,
            List.of(),
            true,
            false,
            false,
            true,
            "",
            List.of(),
            false,
            ""
        );
        DiscoveryCampaignFourRunner.CaseResult reuse = new DiscoveryCampaignFourRunner.CaseResult(
            "discovery-campaign-4",
            "candidate-a",
            "discovery-campaign-1",
            "complete-square-factorization",
            "macro.id",
            List.of("macro.id"),
            new DiscoveryCampaignFourRunner.RunMetrics(true, 5, 100, 1),
            new DiscoveryCampaignFourRunner.RunMetrics(true, 2, 20, 0),
            true
        );

        PromotionRecord reused = record.withReuse(reuse);

        assertEquals(PromotionStage.REUSED, reused.stage());
        assertEquals("DEGRADED", reused.ablationEvidence().ablationStatus());
        assertTrue(reused.ablationEvidence().hasStructuredMetrics());
        assertEquals(2, reused.ablationEvidence().withCandidate().pathLength());
        assertEquals(5, reused.ablationEvidence().withoutCandidate().pathLength());
    }
}
