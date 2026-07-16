package de.regelsuche.release;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.experiments.autopilot.AutonomousProductionCampaignRunner;
import de.regelsuche.mining.OpenTargetConjectureEvaluator;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.EvaluationPlan;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.EvaluationStatus;
import de.regelsuche.validation.CounterexampleSearchService.CounterexampleBudget;
import org.junit.jupiter.api.Test;

/** Characterizes every independent blocker of the release qualification. */
class AutonomousCandidateQualificationDiagnosticsTest {
    @Test
    void reportsEachQualificationAxisIndependently() {
        var campaign = new AutonomousProductionCampaignRunner().runPinned(2);
        var conjecture = campaign.lifecycle().conjecture();
        var split = new ProductionCandidateQualificationSplitAudit().audit(campaign);
        var evaluation = new OpenTargetConjectureEvaluator().evaluate(
            conjecture,
            new EvaluationPlan(
                ProductionCandidateQualificationCatalog.REVISION,
                ProductionCandidateQualificationCatalog.positives().stream()
                    .map(ProductionCandidateQualificationCatalog.PositiveCase::asHoldout)
                    .toList(),
                ProductionCandidateQualificationCatalog.negatives(),
                new CounterexampleBudget(
                    256,
                    true,
                    false,
                    campaign.lifecycle().mining().generation().brief().deterministicSeed()
                        ^ 359L,
                    true,
                    true,
                    0,
                    0L)));
        var utility = new ProductionCandidateUtilityEvaluator().evaluate(conjecture);

        assertAll(
            () -> assertTrue(split.passed(), split::toCanonicalJson),
            () -> assertEquals(
                EvaluationStatus.ACCEPTED_FOR_PROOF,
                evaluation.status(),
                evaluation::toString),
            () -> assertTrue(evaluation.holdoutsComplete(), evaluation::toString),
            () -> assertTrue(evaluation.allHoldoutsPassed(), evaluation::toString),
            () -> assertEquals(
                "NO_COUNTEREXAMPLE_FOUND",
                evaluation.counterexample().status(),
                evaluation::toString),
            () -> assertTrue(
                evaluation.counterexample().inferredAssumptions().isEmpty(),
                evaluation::toString),
            () -> assertTrue(
                evaluation.counterexample().assignments().isEmpty(),
                evaluation::toString),
            () -> assertTrue(utility.pairedUtilityEvaluated(), utility::toCanonicalJson),
            () -> assertEquals(0, utility.correctnessRegressionCount(),
                utility::toCanonicalJson),
            () -> assertTrue(utility.beneficial(), utility::toCanonicalJson));
    }
}
