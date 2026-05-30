package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import org.junit.jupiter.api.Test;

class QuadraticFactorizationHypothesisOperatorTest {
    private final QuadraticFactorizationHypothesisOperator operator = new QuadraticFactorizationHypothesisOperator();

    @Test
    void supportsQuadraticsWithSubtractionTerms() {
        List<Transformation> candidates = operator.generateCandidates("x^2 - 6*x + 5");

        assertFalse(candidates.isEmpty());
    }
}
