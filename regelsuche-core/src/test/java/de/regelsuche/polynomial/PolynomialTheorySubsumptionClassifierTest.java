package de.regelsuche.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.transform.PolynomialStructureSynthesisOperator;
import org.junit.jupiter.api.Test;

class PolynomialTheorySubsumptionClassifierTest {
    private final PolynomialTheorySubsumptionClassifier classifier =
        new PolynomialTheorySubsumptionClassifier();

    @Test
    void classifiesTheMinedSophieGermainFormulaAsTheoryGenerated() {
        PolynomialTheorySubsumptionClassifier.Result result = classifier.classify(
            "A^4 + 4*B^4",
            "(A^2 + 2*A*B + 2*B^2) * (A^2 - 2*A*B + 2*B^2)");

        assertTrue(result.subsumed(), result.toString());
        assertEquals(
            PolynomialTheorySubsumptionClassifier.Status.SUBSUMED,
            result.status());
        assertEquals(
            PolynomialStructureSynthesisOperator.RULE_ID,
            result.theoryRuleId());
        assertTrue(result.sourceSemanticHash().matches("sha256:[0-9a-f]{64}"));
        assertTrue(result.applicationKey().contains("|certificate=sha256:"));
        assertFalse(result.matchingCandidate().isBlank());
    }

    @Test
    void doesNotPretendEveryEquivalentRewriteIsATheoryGeneratedFactorization() {
        PolynomialTheorySubsumptionClassifier.Result neutral = classifier.classify(
            "(A + 0) * 1",
            "A");
        PolynomialTheorySubsumptionClassifier.Result identity = classifier.classify(
            "A^4 + 4*B^4",
            "A^4 + 4*B^4");

        assertEquals(
            PolynomialTheorySubsumptionClassifier.Status.NOT_SUBSUMED,
            neutral.status());
        assertEquals(
            PolynomialTheorySubsumptionClassifier.Status.NOT_SUBSUMED,
            identity.status());
    }

    @Test
    void separatesFalseAndUnsupportedCandidates() {
        PolynomialTheorySubsumptionClassifier.Result falseRule = classifier.classify(
            "A^4 + 4*B^4",
            "(A^2 + B^2)^2");
        PolynomialTheorySubsumptionClassifier.Result division = classifier.classify(
            "A / B",
            "A * B");

        assertEquals(
            PolynomialTheorySubsumptionClassifier.Status.NOT_EQUIVALENT,
            falseRule.status());
        assertEquals(
            PolynomialTheorySubsumptionClassifier.Status.UNSUPPORTED,
            division.status());
    }
}
