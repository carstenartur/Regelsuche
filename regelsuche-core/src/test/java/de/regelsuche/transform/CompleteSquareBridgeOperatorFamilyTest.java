package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Family-level tests for {@link CompleteSquareBridgeOperator} covering positive, variant, near-miss, and negative members. */
class CompleteSquareBridgeOperatorFamilyTest {
    private final CompleteSquareBridgeOperator operator = new CompleteSquareBridgeOperator();

    @Test
    void positiveFamily_evenLinearCoefficient() {
        assertGenerates("x^2 + 6*x + 5", "(x + 3) ^ 2 - 4");
        assertGenerates("x^2 + 10*x + 21", "(x + 5) ^ 2 - 4");
        assertGenerates("x^2 - 4*x + 3", "(x - 2) ^ 2 - 1");
        assertGenerates("x^2 + 2*x + 1", "(x + 1) ^ 2");
        assertGenerates("x^2 + 6*x + 9", "(x + 3) ^ 2");
        assertGenerates("y^2 + 8*y + 7", "(y + 4) ^ 2 - 9");
    }

    @Test
    void positiveFamily_oddLinearCoefficient() {
        assertGenerates("x^2 + 5*x + 6", "(x + 2.5) ^ 2 - 0.25");
        assertGenerates("x^2 + 3*x + 2", "(x + 1.5) ^ 2 - 0.25");
        assertGenerates("x^2 + 7*x + 12", "(x + 3.5) ^ 2 - 0.25");
    }

    @Test
    void positiveFamily_oddLinearCoefficient_squareDifferenceBridge() {
        assertGenerates("x^2 + 5*x + 6", "(x + 2.5) ^ 2 - 0.5 ^ 2");
        assertGenerates("x^2 + 7*x + 6", "(x + 3.5) ^ 2 - 2.5 ^ 2");
    }

    @Test
    void positiveFamily_squareDifferenceBridge() {
        assertGenerates("x^2 + 6*x + 5", "(x + 3) ^ 2 - 2 ^ 2");
    }

    @Test
    void variantFamily_compoundBase() {
        assertGenerates("(x + 1)^2 + 6*(x + 1) + 5", "(x + 1 + 3) ^ 2 - 4");
    }

    @Test
    void variantFamily_termOrderAndMultiplicationForms() {
        assertGenerates("5 + 6*x + x^2", "(x + 3) ^ 2 - 4");
        assertGenerates("x*x + 6*x + 5", "(x + 3) ^ 2 - 4");
    }

    @Test
    void variantFamily_differentVariable() {
        assertGenerates("y^2 + 4*y + 3", "(y + 2) ^ 2 - 1");
    }

    @Test
    void variantFamily_negativeLinearCoefficient() {
        assertGenerates("x^2 - 6*x + 5", "(x - 3) ^ 2 - 4");
    }

    @Test
    void nearMissFamily_oddCoefficientAccepted() {
        assertHasCandidate("x^2 + 5*x + 6");
        assertHasCandidate("x^2 + 3*x + 2");
    }

    @Test
    void nearMissFamily_nonIntegerRemainder_noSquareRootBridge() {
        // x^2 + 5*x + 5: offset=2.5, remainder=5-6.25=-1.25, perfectSquareRoot(1.25)=null
        // should produce the completed square but not the square-difference bridge
        List<String> candidates = operator.generateCandidates("x^2 + 5*x + 5").stream()
            .map(Transformation::transformedExpression)
            .toList();
        assertFalse(candidates.isEmpty(), "should produce at least the completed square");
        assertTrue(candidates.stream().anyMatch(c -> c.contains("2.5")), "should produce half-integer offset candidate");
        // A square-difference bridge would contain two "^ 2" occurrences (A^2 - B^2 form)
        assertFalse(candidates.stream().anyMatch(c -> c.split("\\^ 2", -1).length - 1 >= 2),
            "should not produce spurious square-difference for non-rational bridge");
    }

    @Test
    void negativeFamily_nonUnitLeadingCoefficient() {
        assertNoneGenerated("2*x^2 + 6*x + 5");
    }

    @Test
    void negativeFamily_crossTerms() {
        assertNoneGenerated("x^2 + 6*x*y + 5");
    }

    @Test
    void negativeFamily_wrongDegree() {
        assertNoneGenerated("x^3 + 6*x + 5");
    }

    private void assertGenerates(String input, String expected) {
        List<String> candidates = operator.generateCandidates(input).stream()
            .map(Transformation::transformedExpression)
            .toList();
        assertTrue(candidates.contains(expected),
            "Expected '" + expected + "' in " + candidates + " for input '" + input + "'");
    }

    private void assertHasCandidate(String input) {
        assertFalse(operator.generateCandidates(input).isEmpty(),
            "Expected at least one candidate for '" + input + "'");
    }

    private void assertNoneGenerated(String input) {
        assertTrue(operator.generateCandidates(input).stream()
            .noneMatch(c -> c.rule().equals(CompleteSquareBridgeOperator.RULE_ID)), input);
    }
}
