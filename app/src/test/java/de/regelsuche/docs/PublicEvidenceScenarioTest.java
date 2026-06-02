package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PublicEvidenceScenarioTest {
    private static final List<String> PUBLIC_SCENARIOS = List.of(
            "discovery-scenarios/complete-square.yaml",
            "discovery-scenarios/sophie-germain.yaml");

    @Test
    void publicDiscoveryScenariosProduceSuccessfulEvidence() {
        DiscoveryBenchmarkScenarioLoader loader = new DiscoveryBenchmarkScenarioLoader();
        DiscoveryBenchmarkExecutor executor = new DiscoveryBenchmarkExecutor();

        for (String resource : PUBLIC_SCENARIOS) {
            DiscoveryBenchmarkScenario scenario = loader.load(resource);
            DiscoveryBenchmarkEvidence evidence = executor.execute(scenario);

            assertTrue(evidence.success(), scenario.id() + ": " + evidence.failureReason());
            assertTrue(evidence.failureReason().isBlank(), scenario.id());
            assertTrue(evidence.nodeCount() > 0, scenario.id());
            assertTrue(evidence.edgeCount() > 0, scenario.id());
            assertTrue(evidence.nodeCount() > 8, scenario.id() + ": public evidence graph should be >8 nodes");
            assertFalse(evidence.bridgeRulesUsed().isEmpty(), scenario.id());
            if (scenario.macroLearning().enabled()) {
                assertFalse(evidence.learnedMacros().isEmpty(), scenario.id());
                assertFalse(evidence.reusedMacros().isEmpty(), scenario.id());
            }
            for (DiscoveryBenchmarkEvidence.EvidenceEdge edge : evidence.edges()) {
                assertFalse("scenario-exact-path".equals(edge.source()), scenario.id());
                assertHasText(edge.ruleId(), scenario.id() + " edge.ruleId");
                assertHasText(edge.source(), scenario.id() + " edge.source");
                assertHasText(edge.packId(), scenario.id() + " edge.packId");
                assertNotNull(edge.searchEffect(), scenario.id() + " edge.searchEffect");
                assertFalse(edge.searchEffect().isEmpty(), scenario.id() + " edge.searchEffect");
            }
        }
    }

    private static void assertHasText(String value, String message) {
        assertTrue(value != null && !value.isBlank(), message);
    }
}
