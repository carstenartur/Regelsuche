package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.transform.PolynomialStructureSynthesisOperator;
import org.junit.jupiter.api.Test;

class SophieGermainScenarioDiscoveryTest {
    @Test
    void sophieGermainScenarioLearnsAndReusesMacroFromGenericTheorySynthesis() {
        DiscoveryBenchmarkScenario scenario = new DiscoveryBenchmarkScenarioLoader()
            .load("discovery-scenarios/sophie-germain.yaml");

        DiscoveryBenchmarkEvidence evidence = new DiscoveryBenchmarkExecutor().execute(scenario);

        assertTrue(evidence.success(), evidence.failureReason());
        assertTrue(evidence.withoutMacroRun().appliedRuleIds().contains(
            PolynomialStructureSynthesisOperator.RULE_ID));
        assertFalse(evidence.withoutMacroRun().appliedRuleIds().contains(
            "hypothesis_difference_of_squares_preparation"));
        assertFalse(evidence.learnedMacros().isEmpty());
        assertFalse(evidence.reusedMacros().isEmpty());
    }
}
