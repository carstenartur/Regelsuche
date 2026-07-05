package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
