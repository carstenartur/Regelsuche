package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.transform.TelescopingFractionHypothesisOperator;
import org.junit.jupiter.api.Test;

class TelescopingScenarioDiscoveryTest {
    @Test
    void telescopingScenarioIsDiscoveredWithoutExactScenarioRules() {
        DiscoveryBenchmarkScenario scenario = new DiscoveryBenchmarkScenarioLoader()
            .load("discovery-scenarios/telescoping.yaml");

        DiscoveryBenchmarkEvidence evidence = new DiscoveryBenchmarkExecutor().execute(scenario);

        assertTrue(evidence.success(), evidence.failureReason());
        assertTrue(evidence.withoutMacroRun().appliedRuleIds().contains(TelescopingFractionHypothesisOperator.RULE_ID));
        assertFalse(evidence.edges().stream().anyMatch(edge -> edge.source().contains("scenario-exact-path")));
    }
}
