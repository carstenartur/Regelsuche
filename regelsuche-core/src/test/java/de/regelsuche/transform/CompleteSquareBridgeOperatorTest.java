package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class CompleteSquareBridgeOperatorTest {
    private final CompleteSquareBridgeOperator operator = new CompleteSquareBridgeOperator();

    @Test
    void generatesParametricCompleteSquareCandidates() {
        assertGenerated("x^2 + 6*x + 5", "(x + 3) ^ 2 - 4");
        assertGenerated("5 + 6*x + x^2", "(x + 3) ^ 2 - 4");
        assertGenerated("x*x + 6*x + 5", "(x + 3) ^ 2 - 4");
        assertGenerated("x^2 + 10*x + 21", "(x + 5) ^ 2 - 4");
        assertGenerated("x^2 - 4*x + 3", "(x - 2) ^ 2 - 1");
        assertGenerated("x^2 + 2*x + 1", "(x + 1) ^ 2");
        assertGenerated("x^2 + 6*x + 9", "(x + 3) ^ 2");
    }

    @Test
    void alsoGeneratesSquareDifferenceBridgeWhenRemainderIsNegativeSquare() {
        assertGenerated("x^2 + 6*x + 5", "(x + 3) ^ 2 - 2 ^ 2");
    }

    @Test
    void rejectsNearMisses() {
        for (String expression : List.of("x^2 + 6*x*y + 5", "2*x^2 + 6*x + 5", "x^3 + 6*x + 5")) {
            assertFalse(operator.generateCandidates(expression).stream()
                .anyMatch(candidate -> candidate.rule().equals(CompleteSquareBridgeOperator.RULE_ID)), expression);
        }
    }

    private void assertGenerated(String input, String expected) {
        List<String> candidates = operator.generateCandidates(input).stream()
            .map(Transformation::transformedExpression)
            .toList();
        assertTrue(candidates.contains(expected), candidates.toString());
    }
}
