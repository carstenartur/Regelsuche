package de.regelsuche.docs;

import de.regelsuche.search.SearchSpaceAnalytics;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchSpaceGallerySvgWriterTest {
    @Test
    void labelsComeFromEvidenceAndSvgContainsProvenance() {
        DiscoveryBenchmarkEvidence evidence = new DiscoveryBenchmarkEvidence(
                "synthetic-scenario",
                "a",
                "b",
                true,
                "",
                new DiscoveryBenchmarkEvidence.SearchRunEvidence(true, "", List.of("a", "b"), List.of("rule_alpha"),
                        new SearchSpaceAnalytics(2, 2, 0, 0, 0.0d)),
                new DiscoveryBenchmarkEvidence.SearchRunEvidence(false, "", List.of(), List.of(),
                        new SearchSpaceAnalytics(0, 0, 0, 0, 0.0d)),
                List.of(List.of("a", "b")),
                List.of("rule_alpha"),
                List.of("synthetic"),
                List.of(),
                List.of(),
                List.of(),
                new SearchSpaceAnalytics(2, 2, 0, 0, 0.0d),
                "PASS",
                List.of(new DiscoveryBenchmarkEvidence.EvidenceNode("a", "Node A", "state"),
                        new DiscoveryBenchmarkEvidence.EvidenceNode("b", "Node B", "target")),
                List.of(new DiscoveryBenchmarkEvidence.EvidenceEdge("a", "b", "rule_alpha", "bridge", "core", "core", List.of())),
                "Search produced only 2 visible states under this budget.");

        String svg = new SearchSpaceGallerySvgWriter().write(evidence, "synthetic-evidence.json");

        assertTrue(svg.contains("data-generated-by=\"SearchSpaceGallerySvgWriter\""));
        assertTrue(svg.contains("data-scenario-id=\"synthetic-scenario\""));
        assertTrue(svg.contains("data-evidence=\"synthetic-evidence.json\""));
        assertTrue(svg.contains("data-node-count=\"2\""));
        assertTrue(svg.contains("data-edge-count=\"1\""));
        assertTrue(svg.contains("Node A"));
        assertTrue(svg.contains("Node B"));
        assertTrue(svg.contains("rule_alpha"));
        assertTrue(svg.contains("Search produced only 2 visible states under this budget."));
        assertFalse(svg.contains("complete square"));
        assertFalse(svg.contains("stale label"));
        assertFalse(svg.contains("rule_beta"));
    }
}
