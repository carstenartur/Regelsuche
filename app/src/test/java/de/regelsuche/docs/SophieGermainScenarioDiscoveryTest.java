package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.transform.DifferenceOfSquaresPreparationOperator;
import org.junit.jupiter.api.Test;

class SophieGermainScenarioDiscoveryTest {
    @Test
    void sophieGermainScenarioLearnsAndReusesMacroFromDiscoveredPath() {
        DiscoveryBenchmarkScenario scenario = new DiscoveryBenchmarkScenarioLoader()
            .load("discovery-scenarios/sophie-germain.yaml");

        DiscoveryBenchmarkEvidence evidence = new DiscoveryBenchmarkExecutor().execute(scenario);

        assertTrue(evidence.success(), evidence.failureReason());
        assertTrue(evidence.withoutMacroRun().appliedRuleIds().contains(DifferenceOfSquaresPreparationOperator.RULE_ID));
        assertTrue(evidence.withoutMacroRun().appliedRuleIds().contains("ast_square_difference_factor"));
        assertFalse(evidence.learnedMacros().isEmpty());
        assertFalse(evidence.reusedMacros().isEmpty());
    }
}
