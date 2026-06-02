package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.transform.RationalizationHypothesisOperator;
import de.regelsuche.transform.TelescopingFractionHypothesisOperator;
import java.util.List;
import org.junit.jupiter.api.Test;

class NoFalsePositiveDiscoveryTest {
    @Test
    void newOperatorsRejectNearMissesWithoutValidatedDiscoveries() {
        TelescopingFractionHypothesisOperator telescoping = new TelescopingFractionHypothesisOperator();
        RationalizationHypothesisOperator rationalization = new RationalizationHypothesisOperator();

        for (String expression : List.of("1 / (n + n + 1)", "1 / (n * (m + 1))", "1 / (n^2 + 1)")) {
            assertTrue(telescoping.generateCandidates(expression).isEmpty(), expression);
        }
        for (String expression : List.of("1 / (sqrt(x) + sqrt(y))", "1 / (x + 1)", "1 / (sqrt(x) + y)")) {
            assertTrue(rationalization.generateCandidates(expression).isEmpty(), expression);
        }
    }
}
