package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NoMacroFallbackTest {
    @Test
    void enabledMacroLearningWithoutLearnedMacroDoesNotReuseWithoutMacroRun() {
        DiscoveryBenchmarkScenario original = new DiscoveryBenchmarkScenarioLoader()
                .load("discovery-scenarios/complete-square.yaml");
        DiscoveryBenchmarkScenario scenario = new DiscoveryBenchmarkScenario(
                original.id(),
                original.displayName(),
                original.inputExpression(),
                original.targetExpression(),
                original.expectations(),
                original.enabledOperators(),
                original.enabledRulePacks(),
                original.requiredBridgeEffects(),
                original.requiredRuleFamilies(),
                original.requiredBridgeRules(),
                new DiscoveryBenchmarkScenario.MacroLearning(true, null, null),
                original.budgets(),
                original.gallery());

        DiscoveryBenchmarkEvidence evidence = new DiscoveryBenchmarkExecutor().execute(scenario);

        assertTrue(evidence.withoutMacroRun().success(), evidence.withoutMacroRun().failureReason());
        assertFalse(evidence.withMacroRun().success());
        assertTrue(evidence.withMacroRun().failureReason().contains("no macro was learned"));
        assertFalse(evidence.success());
        assertTrue(evidence.learnedMacros().isEmpty());
        assertNotEquals(evidence.withoutMacroRun().path(), evidence.withMacroRun().path());
    }
}
