package de.regelsuche.benchmark;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscoveryExpectationTest {
    @Test
    void bridgeRequiredFailsWithoutBridgeRuleEvenWhenTargetIsReached() {
        DiscoveryBenchmarkCase benchmark = new DiscoveryBenchmarkCase(
                "sophie-germain",
                "a^4+4b^4",
                "target",
                List.of(List.of("a^4+4b^4", "target")),
                Set.of(DiscoveryExpectation.BRIDGE_REQUIRED),
                List.of("sophie_germain_bridge"),
                Set.of(),
                Set.of());

        DiscoveryBenchmarkResult result = new DiscoveryBenchmarkRunner().run(benchmark);

        assertFalse(result.success());
        assertEquals(0, result.bridgeCount());
    }

    @Test
    void sophieGermainCanRequireBridgeLearningAndReuse() {
        DiscoveryBenchmarkCase benchmark = new DiscoveryBenchmarkCase(
                "sophie-germain",
                "a^4+4b^4",
                "target",
                List.of(
                        List.of("a^4+4b^4", "sophie_germain_bridge", "difference_of_squares", "target"),
                        List.of("a^4+4b^4", "macro_sophie", "target")),
                Set.of(
                        DiscoveryExpectation.BRIDGE_REQUIRED,
                        DiscoveryExpectation.MACRO_LEARNING_REQUIRED,
                        DiscoveryExpectation.MACRO_REUSE_REQUIRED),
                List.of("sophie_germain_bridge"),
                Set.of("macro_sophie"),
                Set.of("macro_sophie"));

        DiscoveryBenchmarkResult result = new DiscoveryBenchmarkRunner().run(benchmark);

        assertTrue(result.success());
        assertEquals(List.of("sophie_germain_bridge"), result.bridgeRules());
        assertEquals(1, result.macroReuseCount());
    }

    @Test
    void differenceOfSquaresOnlyNeedsDirectRewrite() {
        DiscoveryBenchmarkCase benchmark = new DiscoveryBenchmarkCase(
                "difference-of-squares",
                "a^2-b^2",
                "target",
                List.of(List.of("a^2-b^2", "target")),
                Set.of(DiscoveryExpectation.DIRECT_REWRITE),
                List.of(),
                Set.of(),
                Set.of());

        assertTrue(new DiscoveryBenchmarkRunner().run(benchmark).success());
    }
}
