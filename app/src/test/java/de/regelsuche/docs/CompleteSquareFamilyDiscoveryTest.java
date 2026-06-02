package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.DiscoveryExpectation;
import de.regelsuche.transform.CompleteSquareBridgeOperator;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompleteSquareFamilyDiscoveryTest {
    private final DiscoveryBenchmarkExecutor executor = new DiscoveryBenchmarkExecutor();

    @Test
    void completeSquareFamilyPositiveAndVariantCasesSucceed() {
        List<Case> positives = List.of(
                new Case("x^2 + 6*x + 5", "(x + 1) * (x + 5)"),
                new Case("5 + 6*x + x^2", "(x + 1) * (x + 5)"),
                new Case("x*x + 6*x + 5", "(x + 1) * (x + 5)"),
                new Case("x^2 - 4*x + 3", "(x - 1) * (x - 3)"),
                new Case("x^2 + 10*x + 21", "(x + 3) * (x + 7)"),
                new Case("(x+1)^2 + 6*(x+1) + 5", "(x + 2) * (x + 6)"));

        for (Case c : positives) {
            DiscoveryBenchmarkEvidence evidence = executor.execute(scenario("complete-square-family-" + c.input.hashCode(), c.input, c.target));
            assertTrue(evidence.success(), c.input + " -> " + evidence.failureReason());
            assertTrue(evidence.withoutMacroRun().appliedRuleIds().contains(CompleteSquareBridgeOperator.RULE_ID), c.input);
        }
    }

    @Test
    void completeSquareFamilyNearMissesAndNegativeCasesFail() {
        List<Case> negatives = List.of(
                new Case("x^2 + 6*x*y + 5", "(x + 1) * (x + 5)"),
                new Case("2*x^2 + 6*x + 5", "(x + 1) * (2*x + 5)"),
                new Case("x^3 + 6*x + 5", "x^3 + 6*x + 5"));

        for (Case c : negatives) {
            DiscoveryBenchmarkEvidence evidence = executor.execute(scenario("complete-square-negative-" + c.input.hashCode(), c.input, c.target));
            assertFalse(evidence.success(), c.input);
            assertFalse(evidence.withoutMacroRun().appliedRuleIds().contains(CompleteSquareBridgeOperator.RULE_ID), c.input);
        }
    }

    private DiscoveryBenchmarkScenario scenario(String id, String input, String target) {
        return new DiscoveryBenchmarkScenario(
                id,
                "Complete-square family",
                input,
                target,
                List.of(DiscoveryExpectation.BRIDGE_REQUIRED),
                List.of("complete_square_bridge"),
                List.of("sympy-polynomial-basic"),
                List.of(),
                List.of(),
                List.of(CompleteSquareBridgeOperator.RULE_ID),
                new DiscoveryBenchmarkScenario.MacroLearning(false, null, null),
                new DiscoveryBenchmarkScenario.Budgets(8, 280, 5000),
                new DiscoveryBenchmarkScenario.Gallery(false, 1, 3));
    }

    private record Case(String input, String target) {
    }
}
