package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RationalNormalizationHypothesisOperatorAssumptionTest {

    private final RationalNormalizationHypothesisOperator operator =
        new RationalNormalizationHypothesisOperator();

    @Test
    void combiningEqualDenominatorsRetainsTheirNonZeroCondition() {
        Transformation candidate = candidate(
            "x / y + z / y",
            "(x + z) / y");

        assertEquals(List.of("y != 0"), candidate.assumptions());
    }

    @Test
    void cancellationRetainsCancelledFactorAndRemainingDenominator() {
        Transformation candidate = candidate(
            "(x * z) / (y * z)",
            "x / y");

        assertEquals(List.of("y != 0", "z != 0"), candidate.assumptions());
    }

    @Test
    void cancellationAgainstTheWholeDenominatorRetainsTheCancelledFactor() {
        Transformation candidate = candidate(
            "(2 * x) / x",
            "2 / 1");

        assertEquals(List.of("x != 0"), candidate.assumptions());
    }

    @Test
    void explicitZeroDenominatorsDoNotProduceCandidates() {
        assertTrue(operator.generateCandidates("x / 0 + z / 0").isEmpty());
        assertTrue(operator.generateCandidates("(x * z) / (0 * z)").isEmpty());
    }

    private Transformation candidate(String input, String expectedOutput) {
        return operator.generateCandidates(input).stream()
            .filter(item -> expectedOutput.equals(item.transformedExpression()))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "missing candidate " + expectedOutput + " for " + input));
    }
}
