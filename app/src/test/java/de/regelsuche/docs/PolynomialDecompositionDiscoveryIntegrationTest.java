package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.transform.PolynomialDecompositionSynthesisOperator;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolynomialDecompositionDiscoveryIntegrationTest {
    @Test
    void discoveryPipelineFactorsAnUnrelatedQuarticThroughGeneralSynthesis() {
        DiscoveryBenchmarkScenario scenario = scenario();
        DiscoveryOperatorRegistry registry = new DiscoveryOperatorRegistry()
            .register(new DefaultDiscoveryOperatorProvider());

        DiscoveryBenchmarkEvidence evidence =
            new DiscoveryBenchmarkExecutor(
                new DiscoveryBenchmarkScenarioLoader(),
                registry)
                .execute(scenario);

        assertTrue(evidence.success(), evidence.failureReason());
        assertTrue(evidence.withoutMacroRun().appliedRuleIds().contains(
            PolynomialDecompositionSynthesisOperator.RULE_ID));
        assertTrue(evidence.withoutMacroRun().path().stream().anyMatch(value ->
            value.contains("x ^ 2 + y ^ 2")
                && value.contains("x ^ 2 + 4 * y ^ 2")));
    }

    @Test
    void disablingGeneralSynthesisRemovesTheGeneratedFactorization() {
        DiscoveryBenchmarkScenario scenario = scenario();
        DiscoveryOperatorRegistry registry = new DiscoveryOperatorRegistry()
            .register(new DefaultDiscoveryOperatorProvider());
        registry.disable("polynomial_decomposition_synthesis");

        DiscoveryBenchmarkEvidence evidence =
            new DiscoveryBenchmarkExecutor(
                new DiscoveryBenchmarkScenarioLoader(),
                registry)
                .execute(scenario);

        assertFalse(evidence.success(), evidence.failureReason());
        assertFalse(evidence.withoutMacroRun().appliedRuleIds().contains(
            PolynomialDecompositionSynthesisOperator.RULE_ID));
    }

    private static DiscoveryBenchmarkScenario scenario() {
        return new DiscoveryBenchmarkScenario(
            "general-polynomial-decomposition",
            "General polynomial decomposition synthesis",
            "x^4 + 5*x^2*y^2 + 4*y^4",
            "(x^2 + y^2) * (x^2 + 4*y^2)",
            List.of(),
            List.of("polynomial_decomposition_synthesis"),
            List.of("sympy-polynomial-basic", "core"),
            List.of(),
            List.of(),
            List.of(),
            new DiscoveryBenchmarkScenario.MacroLearning(false, null, null),
            new DiscoveryBenchmarkScenario.Budgets(2, 80, 5_000),
            new DiscoveryBenchmarkScenario.Gallery(false, 1, 1));
    }
}
