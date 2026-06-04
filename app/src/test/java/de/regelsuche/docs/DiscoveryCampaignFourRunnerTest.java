package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DiscoveryCampaignFourRunnerTest {
    @Test
    void promotedMacrosRemainReusableAndImproveLaterCampaign() {
        List<PromotionRecord> promotedRecords = List.of(
            new PromotionRecord(
                "complete-square-family",
                "discovery-campaign-1",
                "2026-01-01",
                "polynomial",
                PromotionStage.PROMOTED,
                "x^2 + 10*x + 21",
                "(x + 3) * (x + 7)",
                "AGREE",
                "sympy: equivalent",
                "DEGRADED",
                "complete_square_bridge",
                "sympy-polynomial-basic",
                List.of(),
                "quadratic completion shortcut",
                List.of("complete_square_bridge", "ast_square_difference_factor"),
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
            ),
            new PromotionRecord(
                "sophie-germain-variant",
                "discovery-campaign-1",
                "2026-01-01",
                "polynomial",
                PromotionStage.PROMOTED,
                "x^4 + 64",
                "(x^2 - 4*x + 8) * (x^2 + 4*x + 8)",
                "AGREE",
                "sympy: equivalent",
                "DEGRADED",
                "sophie_germain_bridge",
                "sympy-polynomial-basic",
                List.of(),
                "hidden-structure Sophie-Germain shortcut",
                List.of("hypothesis_difference_of_squares_preparation", "ast_square_difference_factor"),
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
            )
        );

        DiscoveryCampaignFourRunner.CampaignReport report = new DiscoveryCampaignFourRunner().run(promotedRecords);

        assertFalse(report.results().isEmpty());
        assertTrue(report.results().stream().allMatch(result -> !result.generatedMacroId().isBlank()));
        assertTrue(report.results().stream().allMatch(result -> !result.reusedMacroIds().isEmpty()));
        assertTrue(report.results().stream().anyMatch(DiscoveryCampaignFourRunner.CaseResult::measuredImprovement));
    }
}
