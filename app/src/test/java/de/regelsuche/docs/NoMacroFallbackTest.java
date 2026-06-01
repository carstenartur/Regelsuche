package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.DiscoveryExpectation;
import org.junit.jupiter.api.Test;

class NoMacroFallbackTest {
    @Test
    void enabledMacroLearningWithoutLearnedMacroDoesNotReuseWithoutMacroRun() {
        DiscoveryBenchmarkScenario scenario = new DiscoveryBenchmarkScenario(
                "constant-folding-no-macro",
                "Constant folding without learnable macro",
                "1 + 1",
                "2",
                java.util.List.of(DiscoveryExpectation.CONVERGENCE_REQUIRED),
                java.util.List.of(),
                java.util.List.of("complete-square"),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                new DiscoveryBenchmarkScenario.MacroLearning(true, null, null),
                new DiscoveryBenchmarkScenario.Budgets(2, 20, 5000),
                new DiscoveryBenchmarkScenario.Gallery(true, 2, 1));

        DiscoveryBenchmarkEvidence evidence = new DiscoveryBenchmarkExecutor().execute(scenario);

        assertTrue(evidence.withoutMacroRun().success(), evidence.withoutMacroRun().failureReason());
        assertFalse(evidence.withMacroRun().success());
        assertTrue(evidence.withMacroRun().failureReason().contains("no macro was learned"));
        assertFalse(evidence.success());
        assertTrue(evidence.learnedMacros().isEmpty());
        assertNotEquals(evidence.withoutMacroRun().path(), evidence.withMacroRun().path());
    }
}
