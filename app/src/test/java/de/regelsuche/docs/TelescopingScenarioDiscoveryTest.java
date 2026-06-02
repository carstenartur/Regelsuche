package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.DiscoveryExpectation;
import de.regelsuche.transform.TelescopingFractionHypothesisOperator;
import java.util.List;
import org.junit.jupiter.api.Test;

class TelescopingScenarioDiscoveryTest {
    private final DiscoveryBenchmarkExecutor executor = new DiscoveryBenchmarkExecutor();

    @Test
    void telescopingScenarioIsDiscoveredWithoutExactScenarioRules() {
        DiscoveryBenchmarkScenario scenario = new DiscoveryBenchmarkScenarioLoader()
            .load("discovery-scenarios/telescoping.yaml");

        DiscoveryBenchmarkEvidence evidence = executor.execute(scenario);

        assertTrue(evidence.success(), evidence.failureReason());
        assertTrue(evidence.withoutMacroRun().appliedRuleIds().contains(TelescopingFractionHypothesisOperator.RULE_ID));
        assertFalse(evidence.edges().stream().anyMatch(edge -> edge.source().contains("scenario-exact-path")));
    }

    @Test
    void telescopingFamilyVariantsSucceed() {
        List<Case> positives = List.of(
                new Case("1 / ((n+1)*(n+2))", "1 / (n + 1) - 1 / (n + 2)"),
                new Case("1 / (k*(k+1))", "1 / k - 1 / (k + 1)"),
                new Case("2 / (n*(n+1))", "2 / n - 2 / (n + 1)"));
        for (Case c : positives) {
            DiscoveryBenchmarkEvidence evidence = executor.execute(scenario("telescoping-variant-" + c.input.hashCode(), c.input, c.target));
            assertTrue(evidence.success(), c.input + " -> " + evidence.failureReason());
            assertTrue(evidence.withoutMacroRun().appliedRuleIds().contains(TelescopingFractionHypothesisOperator.RULE_ID), c.input);
        }
    }

    @Test
    void telescopingNearMissesFail() {
        List<Case> nearMisses = List.of(
                new Case("1 / (n*(n+2))", "1 / n - 1 / (n + 2)"),
                new Case("1 / (n^2 + 1)", "1 / n - 1 / (n + 1)"),
                new Case("1 / ((n+1)*(n+3))", "1 / (n + 1) - 1 / (n + 3)"));
        for (Case c : nearMisses) {
            DiscoveryBenchmarkEvidence evidence = executor.execute(scenario("telescoping-nearmiss-" + c.input.hashCode(), c.input, c.target));
            assertFalse(evidence.success(), c.input);
        }
    }

    private DiscoveryBenchmarkScenario scenario(String id, String input, String target) {
        return new DiscoveryBenchmarkScenario(
                id,
                "Telescoping family",
                input,
                target,
                List.of(DiscoveryExpectation.BRIDGE_REQUIRED),
                List.of("telescoping_fraction"),
                List.of("sympy-rational-basic"),
                List.of(),
                List.of(),
                List.of(TelescopingFractionHypothesisOperator.RULE_ID),
                new DiscoveryBenchmarkScenario.MacroLearning(false, null, null),
                new DiscoveryBenchmarkScenario.Budgets(4, 80, 5000),
                new DiscoveryBenchmarkScenario.Gallery(false, 1, 1));
    }

    private record Case(String input, String target) {
    }
}
