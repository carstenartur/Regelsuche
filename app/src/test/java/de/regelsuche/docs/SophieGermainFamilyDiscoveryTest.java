package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.DiscoveryExpectation;
import de.regelsuche.transform.DifferenceOfSquaresPreparationOperator;
import java.util.List;
import org.junit.jupiter.api.Test;

class SophieGermainFamilyDiscoveryTest {
    private final DiscoveryBenchmarkExecutor executor = new DiscoveryBenchmarkExecutor();
    private final DifferenceOfSquaresPreparationOperator operator = new DifferenceOfSquaresPreparationOperator();

    @Test
    void sophieGermainFamilyPositiveAndVariantCasesProduceBridgeCandidates() {
        List<Case> positives = List.of(
                new Case(
                        "x^4 + 4*y^4",
                        "(x^2 - 2*x*y + 2*y^2) * (x^2 + 2*x*y + 2*y^2)"),
                new Case(
                        "a^4 + 4*b^4",
                        "(a^2 - 2*a*b + 2*b^2) * (a^2 + 2*a*b + 2*b^2)"),
                new Case(
                        "(x+1)^4 + 4*y^4",
                        "((x + 1)^2 + 2*y^2 - 2*(x + 1)*y) * ((x + 1)^2 + 2*y^2 + 2*(x + 1)*y)"),
                new Case(
                        "x^4 + 4*(y+1)^4",
                        "(x^2 + 2*(y + 1)^2 - 2*x*(y + 1)) * (x^2 + 2*(y + 1)^2 + 2*x*(y + 1))"));

        for (Case c : positives) {
            assertTrue(operator.generateCandidates(c.input).stream()
                    .anyMatch(candidate -> DifferenceOfSquaresPreparationOperator.RULE_ID.equals(candidate.rule())), c.input);
        }
    }

    @Test
    void sophieGermainFamilyDiscoverySucceedsForCanonicalMembers() {
        List<Case> canonicalDiscoveryMembers = List.of(
                new Case(
                        "x^4 + 4*y^4",
                        "(x^2 - 2*x*y + 2*y^2) * (x^2 + 2*x*y + 2*y^2)"),
                new Case(
                        "a^4 + 4*b^4",
                        "(a^2 - 2*a*b + 2*b^2) * (a^2 + 2*a*b + 2*b^2)"));

        for (Case c : canonicalDiscoveryMembers) {
            DiscoveryBenchmarkEvidence evidence = executor.execute(scenario("sophie-family-" + c.input.hashCode(), c.input, c.target));
            assertTrue(evidence.success(), c.input + " -> " + evidence.failureReason());
            assertTrue(evidence.withoutMacroRun().appliedRuleIds().contains(DifferenceOfSquaresPreparationOperator.RULE_ID), c.input);
            assertTrue(evidence.withoutMacroRun().appliedRuleIds().contains("ast_square_difference_factor"), c.input);
        }
    }

    @Test
    void sophieGermainFamilyNearMissesFail() {
        List<Case> nearMisses = List.of(
                new Case("x^4 + 4*y^3", "(x^2 - 2*x*y + 2*y^2) * (x^2 + 2*x*y + 2*y^2)"),
                new Case("x^4 + 3*y^4", "(x^2 - 2*x*y + 2*y^2) * (x^2 + 2*x*y + 2*y^2)"),
                new Case("x^4 - 4*y^4", "(x^2 - 2*x*y + 2*y^2) * (x^2 + 2*x*y + 2*y^2)"));

        for (Case c : nearMisses) {
            DiscoveryBenchmarkEvidence evidence = executor.execute(scenario("sophie-nearmiss-" + c.input.hashCode(), c.input, c.target));
            assertFalse(evidence.success(), c.input);
        }
    }

    private DiscoveryBenchmarkScenario scenario(String id, String input, String target) {
        return new DiscoveryBenchmarkScenario(
                id,
                "Sophie-Germain family",
                input,
                target,
                List.of(DiscoveryExpectation.BRIDGE_REQUIRED),
                List.of("sophie_germain_bridge"),
                List.of("sympy-polynomial-basic"),
                List.of(),
                List.of(),
                List.of(DifferenceOfSquaresPreparationOperator.RULE_ID, "ast_square_difference_factor"),
                new DiscoveryBenchmarkScenario.MacroLearning(false, null, null),
                new DiscoveryBenchmarkScenario.Budgets(12, 960, 5000),
                new DiscoveryBenchmarkScenario.Gallery(false, 1, 3));
    }

    private record Case(String input, String target) {
    }
}
