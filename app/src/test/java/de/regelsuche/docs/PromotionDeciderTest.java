package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PromotionDeciderTest {
    private final PromotionDecider decider = new PromotionDecider();

    @Test
    void promotionGatePromotesOnlyWhenAllSignalsPass() {
        PromotionRecord promoted = decider.decide(new PromotionObservation(
            "candidate-a",
            "discovery-campaign-3",
            "2026-03-01",
            "factorization",
            true,
            "AGREE",
            "DEGRADED",
            "common_subexpression_discovery",
            "sympy-polynomial-basic",
            List.of(),
            "systematic common-factor discovery",
            List.of("rule-a", "rule-b"),
            true,
            false,
            false,
            true
        ));
        PromotionRecord blocked = decider.decide(new PromotionObservation(
            "candidate-b",
            "discovery-campaign-3",
            "2026-03-01",
            "factorization",
            true,
            "AGREE",
            "UNCHANGED",
            "",
            "sympy-polynomial-basic",
            List.of(),
            "ablation did not degrade",
            List.of("rule-a"),
            true,
            false,
            false,
            false
        ));

        assertEquals(PromotionStage.PROMOTED, promoted.stage());
        assertTrue(promoted.promotionEligible());
        assertTrue(promoted.promotionBlockers().isEmpty());

        assertEquals(PromotionStage.VALIDATED, blocked.stage());
        assertFalse(blocked.promotionEligible());
        assertTrue(blocked.promotionBlockers().contains("ablation=UNCHANGED"));
    }
}
