package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class EvidenceProvenanceTest {
    @Test
    void evidenceEdgesDeclareRuleSourceAndSearchEffects() {
        DiscoveryBenchmarkScenario scenario = new DiscoveryBenchmarkScenarioLoader()
            .load("discovery-scenarios/complete-square.yaml");

        DiscoveryBenchmarkEvidence evidence = new DiscoveryBenchmarkExecutor().execute(scenario);

        assertFalse(evidence.edges().isEmpty());
        for (DiscoveryBenchmarkEvidence.EvidenceEdge edge : evidence.edges()) {
            assertFalse(edge.ruleId().isBlank());
            assertFalse(edge.source().isBlank());
            assertFalse(edge.searchEffect().isEmpty(), edge.toString());
            assertFalse(edge.source().equals("hardcoded") || edge.source().equals("scenario"), edge.toString());
        }
    }
}
