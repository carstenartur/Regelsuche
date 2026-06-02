package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Family-level tests for {@link DifferenceOfSquaresPreparationOperator} covering Sophie-Germain / hidden square-difference families. */
class HiddenSquareDifferenceFamilyTest {
    private final DifferenceOfSquaresPreparationOperator operator = new DifferenceOfSquaresPreparationOperator();

    @Test
    void positiveFamily_basicSophieGermain() {
        assertHasSquareDifferenceBridge("x^4 + 4");
        assertHasSquareDifferenceBridge("y^4 + 4");
        assertHasSquareDifferenceBridge("x^4 + 4*y^4");
    }

    @Test
    void positiveFamily_numericConstant() {
        assertHasSquareDifferenceBridge("x^4 + 64");
    }

    @Test
    void variantFamily_scaledLeadingTerm() {
        assertHasSquareDifferenceBridge("16*x^4 + 4*y^4");
    }

    @Test
    void variantFamily_compoundBase() {
        assertHasSquareDifferenceBridge("(x + 1)^4 + 4");
        assertHasSquareDifferenceBridge("(2*x)^4 + 4");
    }

    @Test
    void variantFamily_higherPowers() {
        assertHasSquareDifferenceBridge("a^8 + 4*b^8");
    }

    @Test
    void variantFamily_rationalConstant() {
        assertHasSquareDifferenceBridge("x^4 + 0.25");
    }

    @Test
    void variantFamily_specificBridgeForm_symbolicFourthPowers() {
        List<String> candidates = operator.generateCandidates("x^4 + 4*y^4").stream()
            .map(Transformation::transformedExpression)
            .toList();
        assertTrue(candidates.stream().anyMatch(c -> c.equals("(x ^ 2 + 2 * y ^ 2) ^ 2 - (2 * x * y) ^ 2")),
            "Sophie-Germain bridge for x^4 + 4*y^4: " + candidates);
    }

    @Test
    void nearMissFamily_nonSquareConstant() {
        assertNoCandidates("x^4 + 5");
        assertNoCandidates("x^4 + 2");
        assertNoCandidates("x^4 + 3");
    }

    @Test
    void nearMissFamily_nonSquareTerm() {
        assertNoCandidates("x^4 + y");
    }

    @Test
    void negativeFamily_threeOrMoreTerms() {
        assertNoCandidates("x^4 + 4 + y^2");
        assertNoCandidates("x^4 + 4 + y");
    }

    @Test
    void negativeFamily_noSymmetricBridge() {
        assertNoCandidates("x^4 + 5*y^4");
    }

    private void assertHasSquareDifferenceBridge(String expression) {
        List<Transformation> candidates = operator.generateCandidates(expression);
        assertFalse(candidates.isEmpty(), "Expected at least one candidate for '" + expression + "'");
        assertTrue(candidates.stream().anyMatch(c ->
            SquareDifferenceAstPredicate.containsSquareDifference(c.transformedExpression())),
            "Expected a square-difference bridge candidate for '" + expression + "', got: " +
                candidates.stream().map(Transformation::transformedExpression).toList());
    }

    private void assertNoCandidates(String expression) {
        List<Transformation> candidates = operator.generateCandidates(expression);
        assertTrue(candidates.isEmpty(),
            "Expected no candidates for '" + expression + "', got: " +
                candidates.stream().map(Transformation::transformedExpression).toList());
    }
}
