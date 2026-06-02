package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DifferenceOfSquaresPreparationOperatorFamilyTest {
    private final DifferenceOfSquaresPreparationOperator operator = new DifferenceOfSquaresPreparationOperator();

    @Test
    void generatesParametricSophieGermainPreparationForFamilyInputs() {
        assertHasBridge("x^4 + 4*y^4");
        assertHasBridge("a^4 + 4*b^4");
        assertHasBridge("(x+1)^4 + 4*y^4");
        assertHasBridge("x^4 + 4*(y+1)^4");
    }

    @Test
    void rejectsSophieGermainNearMisses() {
        for (String expression : List.of("x^4 + 4*y^3", "x^4 + 3*y^4", "x^4 - 4*y^4")) {
            assertFalse(operator.generateCandidates(expression).stream()
                    .anyMatch(candidate -> candidate.rule().equals(DifferenceOfSquaresPreparationOperator.RULE_ID)), expression);
        }
    }

    private void assertHasBridge(String input) {
        assertTrue(operator.generateCandidates(input).stream()
                .anyMatch(candidate -> candidate.rule().equals(DifferenceOfSquaresPreparationOperator.RULE_ID)), input);
    }
}
