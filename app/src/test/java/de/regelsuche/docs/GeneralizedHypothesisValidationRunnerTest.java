package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.transform.RepeatedSubexpressionFactorizationHypothesisOperator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeneralizedHypothesisValidationRunnerTest {

    @Test
    void minedPatternHypothesesAreValidatedOnGeneratedHoldouts(@TempDir Path tempDir) throws Exception {
        List<PromotionRecord> support = List.of(
            supportRecord("support-factor-x", "x * y + x * z", "x * (y + z)"),
            supportRecord("support-factor-a", "a * b + a * c", "a * (b + c)")
        );
        DiscoveryCandidateStore.CandidateStoreReport storeReport = new DiscoveryCandidateStore().build(support);
        PatternHypothesisMiner.PatternHypothesisReport patternReport = new PatternHypothesisMiner().mine(storeReport);

        GeneralizedHypothesisValidationRunner.ValidationReport validationReport =
            new GeneralizedHypothesisValidationRunner().write(tempDir, patternReport);

        assertFalse(patternReport.hypotheses().isEmpty(), "support examples should produce a generalized hypothesis");
        assertFalse(validationReport.validatedHypotheses().isEmpty(), "generalized hypothesis should be holdout-validated");
        GeneralizedHypothesisValidationRunner.ValidatedHypothesis validated = validationReport.validatedHypotheses().stream()
            .filter(hypothesis -> RepeatedSubexpressionFactorizationHypothesisOperator.RULE_ID.equals(hypothesis.operatorId()))
            .findFirst()
            .orElseThrow();
        assertFalse(validated.holdoutResults().isEmpty(), "validation must use generated holdout cases");
        assertTrue(validated.ablationEvidence().hasStructuredMetrics(), "holdout validation must carry structured ablation metrics");
        assertTrue(validated.publicEvidenceAccepted(),
            "validated generalized hypothesis should pass the public evidence gate: " + validated.publicEvidenceRejectionReasons());
        assertTrue(Files.exists(tempDir.resolve("validated-hypotheses.json")));
        assertTrue(Files.exists(tempDir.resolve("validated-hypotheses.md")));
    }

    private PromotionRecord supportRecord(String id, String input, String target) {
        AblationEvidence ablation = AblationEvidence.compare(
            true,
            1,
            10,
            false,
            0,
            100,
            "support example for generated hypothesis validation"
        );
        return new PromotionRecord(
            id,
            "support-campaign",
            "2026-12-01",
            "factorization",
            PromotionStage.PROMOTED,
            input,
            target,
            "AGREE",
            "support fixture oracle agreement",
            ablation.ablationStatus(),
            RepeatedSubexpressionFactorizationHypothesisOperator.RULE_ID,
            "sympy-polynomial-basic",
            List.of(),
            "support fixture",
            List.of(RepeatedSubexpressionFactorizationHypothesisOperator.RULE_ID),
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
            ablation
        );
    }
}
