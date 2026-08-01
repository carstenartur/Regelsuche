package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.equivalence.AssumptionAwareEquivalenceService;
import de.regelsuche.math.algorithms.equivalence.RationalFunctionNormalFormEquivalencePortAdapter;
import java.util.List;
import org.junit.jupiter.api.Test;

class FlagshipRewriteProgramTrainCorpusTest {
    private final AssumptionAwareEquivalenceService equivalence =
        new RationalFunctionNormalFormEquivalencePortAdapter();

    @Test
    void everyTrainCaseIsExactlyConfirmedUnderItsDeclaredAssumptions() {
        EvolutionRewriteProgramTrainSuite suite =
            FlagshipRewriteProgramTrainCorpus.create();

        assertTrue(suite.toCanonicalJson()
            .contains("\"evaluationSplit\":\"TRAIN\""));
        assertEquals(8, suite.cases().size());
        assertEquals(
            suite.cases().stream()
                .map(EvolutionRewriteProgramTrainSuite.TrainCase::caseId)
                .sorted()
                .toList(),
            suite.cases().stream()
                .map(EvolutionRewriteProgramTrainSuite.TrainCase::caseId)
                .toList());
        assertEquals(
            FlagshipRewriteProgramTrainCorpus.create().contentHash(),
            suite.contentHash());

        for (EvolutionRewriteProgramTrainSuite.TrainCase trainCase
                : suite.cases()) {
            AssumptionAwareEquivalenceService.Evaluation evaluation =
                equivalence.evaluate(
                    trainCase.inputExpression(),
                    trainCase.targetExpression(),
                    trainCase.assumptions());
            assertEquals(
                AssumptionAwareEquivalenceService.Status.CONFIRMED,
                evaluation.status(),
                trainCase.caseId() + ": " + evaluation.detail());
            assertTrue(evaluation.equivalent(), trainCase.caseId());
            assertTrue(
                evaluation.missingAssumptions().isEmpty(),
                trainCase.caseId());
            assertTrue(
                evaluation.unsupportedAssumptions().isEmpty(),
                trainCase.caseId());
        }
    }

    @Test
    void cancellationControlsRequireTheDeclaredNonZeroFactor() {
        var direct = equivalence.evaluate(
            "(x * a) / (x * b)",
            "a / b",
            List.of("b != 0"));
        var affine = equivalence.evaluate(
            "((u + 2) * p) / ((u + 2) * q)",
            "p / q",
            List.of("q != 0"));

        assertEquals(
            AssumptionAwareEquivalenceService.Status.MISSING_ASSUMPTION,
            direct.status());
        assertFalse(direct.equivalent());
        assertTrue(direct.missingAssumptions().stream()
            .anyMatch(value -> value.contains("x")));
        assertEquals(
            AssumptionAwareEquivalenceService.Status.MISSING_ASSUMPTION,
            affine.status());
        assertFalse(affine.equivalent());
        assertFalse(affine.missingAssumptions().isEmpty());
    }
}
