package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.transform.CompleteSquareBridgeOperator;
import de.regelsuche.transform.RationalNormalizationHypothesisOperator;
import de.regelsuche.transform.RepeatedSubexpressionFactorizationHypothesisOperator;
import org.junit.jupiter.api.Test;

class OperatorDisablementTest {
    @Test
    void disablingRequiredOperatorMakesScenarioFail() {
        DiscoveryBenchmarkScenario scenario = new DiscoveryBenchmarkScenarioLoader()
                .load("discovery-scenarios/complete-square.yaml");
        DiscoveryOperatorRegistry registry = new DiscoveryOperatorRegistry()
                .register(new DefaultDiscoveryOperatorProvider());
        registry.disable("complete_square_bridge");

        DiscoveryBenchmarkEvidence evidence =
                new DiscoveryBenchmarkExecutor(new DiscoveryBenchmarkScenarioLoader(), registry).execute(scenario);

        assertFalse(evidence.success(), evidence.failureReason());
        assertFalse(evidence.withoutMacroRun().appliedRuleIds().contains(CompleteSquareBridgeOperator.RULE_ID));
    }

    @Test
    void enablingRequiredOperatorMakesScenarioSucceed() {
        DiscoveryBenchmarkScenario scenario = new DiscoveryBenchmarkScenarioLoader()
                .load("discovery-scenarios/complete-square.yaml");
        DiscoveryOperatorRegistry registry = new DiscoveryOperatorRegistry()
                .register(new DefaultDiscoveryOperatorProvider());
        registry.disable("complete_square_bridge");
        registry.enable("complete_square_bridge");

        DiscoveryBenchmarkEvidence evidence =
                new DiscoveryBenchmarkExecutor(new DiscoveryBenchmarkScenarioLoader(), registry).execute(scenario);

        assertTrue(evidence.success(), evidence.failureReason());
        assertTrue(evidence.withoutMacroRun().appliedRuleIds().contains(CompleteSquareBridgeOperator.RULE_ID));
    }

    @Test
    void disablingRepeatedSubexpressionFactorizationMakesScenarioFail() {
        DiscoveryBenchmarkScenario scenario = new DiscoveryBenchmarkScenarioLoader()
                .load("discovery-scenarios/repeated-subexpression-factorization.yaml");
        DiscoveryOperatorRegistry registry = new DiscoveryOperatorRegistry()
                .register(new DefaultDiscoveryOperatorProvider());
        registry.disable("repeated_subexpression_factorization");

        DiscoveryBenchmarkEvidence evidence =
                new DiscoveryBenchmarkExecutor(new DiscoveryBenchmarkScenarioLoader(), registry).execute(scenario);

        assertFalse(evidence.success(), evidence.failureReason());
        assertFalse(evidence.withoutMacroRun().appliedRuleIds()
                .contains(RepeatedSubexpressionFactorizationHypothesisOperator.RULE_ID));
    }

    @Test
    void enablingRepeatedSubexpressionFactorizationMakesScenarioSucceed() {
        DiscoveryBenchmarkScenario scenario = new DiscoveryBenchmarkScenarioLoader()
                .load("discovery-scenarios/repeated-subexpression-factorization.yaml");
        DiscoveryOperatorRegistry registry = new DiscoveryOperatorRegistry()
                .register(new DefaultDiscoveryOperatorProvider());

        DiscoveryBenchmarkEvidence evidence =
                new DiscoveryBenchmarkExecutor(new DiscoveryBenchmarkScenarioLoader(), registry).execute(scenario);

        assertTrue(evidence.success(), evidence.failureReason());
        assertTrue(evidence.withoutMacroRun().appliedRuleIds()
                .contains(RepeatedSubexpressionFactorizationHypothesisOperator.RULE_ID));
    }

    @Test
    void disablingRationalNormalizationMakesScenarioFail() {
        DiscoveryBenchmarkScenario scenario = new DiscoveryBenchmarkScenarioLoader()
                .load("discovery-scenarios/rational-normalization.yaml");
        DiscoveryOperatorRegistry registry = new DiscoveryOperatorRegistry()
                .register(new DefaultDiscoveryOperatorProvider());
        registry.disable("rational_normalization");

        DiscoveryBenchmarkEvidence evidence =
                new DiscoveryBenchmarkExecutor(new DiscoveryBenchmarkScenarioLoader(), registry).execute(scenario);

        assertFalse(evidence.success(), evidence.failureReason());
        assertFalse(evidence.withoutMacroRun().appliedRuleIds()
                .contains(RationalNormalizationHypothesisOperator.RULE_ID));
    }

    @Test
    void enablingRationalNormalizationMakesScenarioSucceed() {
        DiscoveryBenchmarkScenario scenario = new DiscoveryBenchmarkScenarioLoader()
                .load("discovery-scenarios/rational-normalization.yaml");
        DiscoveryOperatorRegistry registry = new DiscoveryOperatorRegistry()
                .register(new DefaultDiscoveryOperatorProvider());

        DiscoveryBenchmarkEvidence evidence =
                new DiscoveryBenchmarkExecutor(new DiscoveryBenchmarkScenarioLoader(), registry).execute(scenario);

        assertTrue(evidence.success(), evidence.failureReason());
        assertTrue(evidence.withoutMacroRun().appliedRuleIds()
                .contains(RationalNormalizationHypothesisOperator.RULE_ID));
    }
}
