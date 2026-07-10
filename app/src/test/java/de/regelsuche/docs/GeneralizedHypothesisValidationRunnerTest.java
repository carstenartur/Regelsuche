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
            supportRecord("support-factor-2", "2 * a + 2 * b", "2 * (a + b)"),
            supportRecord("support-factor-3", "3 * x + 3 * y", "3 * (x + y)")
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
        assertTrue(validated.generatorCoverage().generatedPositiveCount() >= 100,
            "expected at least 100 positive holdouts, got " + validated.generatorCoverage().generatedPositiveCount());
        assertTrue(validated.generatorCoverage().generatedNegativeCount() >= 100,
            "expected at least 100 negative holdouts, got " + validated.generatorCoverage().generatedNegativeCount());
        assertFalse(validated.generatorCoverage().byStructureClass().isEmpty(),
            "expected generator coverage to include structure-class counts");
        assertTrue(validated.negativeHoldoutResults().stream().allMatch(GeneralizedHypothesisValidationRunner.NegativeHoldoutResult::blocked),
            "negative holdouts must block dynamic operator firings");
        assertTrue(validated.generatorCoverage().filteredLeakageCount() == 0,
            "this fixture should not need leakage filtering");
        assertTrue(validated.ablationEvidence().hasStructuredMetrics(), "holdout validation must carry structured ablation metrics");
        assertTrue(validationReport.generatorCoverage().generatedPositiveCount() >= 100);
        assertTrue(validationReport.generatorCoverage().generatedNegativeCount() >= 100);
        assertFalse(validationReport.generatorCoverage().byStructureClass().isEmpty());
        assertTrue(Files.exists(tempDir.resolve("validated-hypotheses.json")));
        assertTrue(Files.exists(tempDir.resolve("validated-hypotheses.md")));
        assertTrue(Files.readString(tempDir.resolve("validated-hypotheses.md")).contains("structure coverage:"),
            "markdown report should surface structure coverage");
    }

    @Test
    void alphaEquivalentSupportExamplesAreFilteredOutOfGeneratedHoldouts(@TempDir Path tempDir) throws Exception {
        List<PromotionRecord> support = List.of(
            supportRecord("support-offset-1", "(u + 1) * a + (u + 1) * d", "(u + 1) * (a + d)"),
            supportRecord("support-offset-2", "(v + 2) * b + (v + 2) * e", "(v + 2) * (b + e)")
        );
        DiscoveryCandidateStore.CandidateStoreReport storeReport = new DiscoveryCandidateStore().build(support);
        PatternHypothesisMiner.PatternHypothesisReport patternReport = new PatternHypothesisMiner().mine(storeReport);

        GeneralizedHypothesisValidationRunner.ValidationReport validationReport =
            new GeneralizedHypothesisValidationRunner().write(tempDir, patternReport);

        GeneralizedHypothesisValidationRunner.ValidatedHypothesis validated = validationReport.validatedHypotheses().stream()
            .filter(hypothesis -> RepeatedSubexpressionFactorizationHypothesisOperator.RULE_ID.equals(hypothesis.operatorId()))
            .findFirst()
            .orElseThrow();
        assertTrue(validated.generatorCoverage().filteredLeakageCount() > 0,
            "expected alpha-equivalent support examples to be removed from the generated holdout set");
        assertTrue(validated.generatorCoverage().generatedPositiveCount() >= 100);
        assertTrue(validated.generatorCoverage().generatedNegativeCount() >= 100);
    }

    @Test
    void canonicalNegativeTargetComparisonTreatsEquivalentFormattingAsRewrite() throws Exception {
        GeneralizedHypothesisValidationRunner runner = new GeneralizedHypothesisValidationRunner();
        var comparison = GeneralizedHypothesisValidationRunner.class
            .getDeclaredMethod("comparableExpressionKey", String.class);
        comparison.setAccessible(true);

        assertTrue(comparison.invoke(runner, "a * (b + c)").equals(comparison.invoke(runner, "(a) * ((b + c))")),
            "canonical comparison should treat equivalent formatting and parentheses as the same target");
        assertFalse(comparison.invoke(runner, "a * (b + c)").equals(comparison.invoke(runner, "a * (b - c)")),
            "distinct targets must still compare differently");
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
