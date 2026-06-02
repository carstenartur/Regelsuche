package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.transform.CompleteSquareBridgeOperator;
import org.junit.jupiter.api.Test;

class CompleteSquareScenarioDiscoveryTest {
    @Test
    void completeSquareScenarioIsDiscoveredWithoutExactScenarioRules() {
        DiscoveryBenchmarkScenario scenario = new DiscoveryBenchmarkScenarioLoader()
            .load("discovery-scenarios/complete-square.yaml");

        DiscoveryBenchmarkEvidence evidence = new DiscoveryBenchmarkExecutor().execute(scenario);

        assertTrue(evidence.success(), evidence.failureReason());
        assertTrue(evidence.withoutMacroRun().appliedRuleIds().contains(CompleteSquareBridgeOperator.RULE_ID));
        assertTrue(evidence.withoutMacroRun().appliedRuleIds().contains("ast_square_difference_factor"));
        assertTrue(evidence.withoutMacroRun().appliedRuleIds().contains("ast_linear_offset_simplify"));
        assertFalse(evidence.learnedMacros().isEmpty());
        assertFalse(evidence.reusedMacros().isEmpty());
        assertFalse(evidence.edges().stream().anyMatch(edge -> edge.source().contains("scenario-exact-path")));
    }
}
