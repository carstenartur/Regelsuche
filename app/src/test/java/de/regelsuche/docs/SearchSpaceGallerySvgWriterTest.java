package de.regelsuche.docs;

import de.regelsuche.search.SearchSpaceAnalytics;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void visibleNodeSelectionStaysWithinConfiguredMaximum() {
        List<DiscoveryBenchmarkEvidence.EvidenceNode> nodes = new ArrayList<>();
        List<DiscoveryBenchmarkEvidence.EvidenceEdge> edges = new ArrayList<>();
        for (int index = 0; index < 35; index++) {
            String id = "n" + index;
            String kind = index == 0 ? "input" : index == 1 ? "target" : "state";
            nodes.add(new DiscoveryBenchmarkEvidence.EvidenceNode(
                    id,
                    "Node " + index,
                    kind,
                    index,
                    List.of("selected-path")));
            if (index > 0) {
                edges.add(new DiscoveryBenchmarkEvidence.EvidenceEdge(
                        "n" + (index - 1),
                        id,
                        "rule_" + index,
                        "rule",
                        "core",
                        "core",
                        List.of()));
            }
        }
        DiscoveryBenchmarkEvidence evidence = new DiscoveryBenchmarkEvidence(
                "synthetic-scenario",
                "a",
                "b",
                true,
                "",
                new DiscoveryBenchmarkEvidence.SearchRunEvidence(true, "", List.of("a", "b"), List.of("rule_1"),
                        new SearchSpaceAnalytics(35, 34, 0, 0, 0.0d)),
                new DiscoveryBenchmarkEvidence.SearchRunEvidence(false, "", List.of(), List.of(),
                        new SearchSpaceAnalytics(0, 0, 0, 0, 0.0d)),
                List.of(List.of("a", "b")),
                List.of(),
                List.of("synthetic"),
                List.of(),
                List.of(),
                List.of(),
                new SearchSpaceAnalytics(35, 34, 0, 0, 0.0d),
                "PASS",
                nodes,
                edges,
                "");

        String svg = new SearchSpaceGallerySvgWriter().write(evidence, "synthetic-evidence.json");

        assertTrue(svg.contains("Visible: 30/35 nodes"), svg);
        assertTrue(svg.contains("Node 29"), svg);
        assertFalse(svg.contains("Node 30"), svg);
        assertFalse(svg.contains("Node 34"), svg);
    }

    @Test
    void svgOutputIsStableWhenEvidenceEdgesAreReordered() {
        DiscoveryBenchmarkEvidence evidenceForward = evidenceWithReorderedBridgeEdges(false);
        DiscoveryBenchmarkEvidence evidenceReverse = evidenceWithReorderedBridgeEdges(true);

        String forwardSvg = new SearchSpaceGallerySvgWriter().write(evidenceForward, "synthetic-evidence.json");
        String reverseSvg = new SearchSpaceGallerySvgWriter().write(evidenceReverse, "synthetic-evidence.json");

        assertTrue(forwardSvg.contains("Visible: 30/31 nodes"), forwardSvg);
        assertTrue(forwardSvg.contains("Node 30"), forwardSvg);
        assertEquals(forwardSvg, reverseSvg);
    }

    private DiscoveryBenchmarkEvidence evidenceWithReorderedBridgeEdges(boolean reverseBridgeOrder) {
        List<DiscoveryBenchmarkEvidence.EvidenceNode> nodes = new ArrayList<>();
        List<DiscoveryBenchmarkEvidence.EvidenceEdge> edges = new ArrayList<>();
        for (int index = 0; index < 31; index++) {
            String id = "n" + index;
            String kind = index == 0 ? "input" : index == 1 ? "target" : "state";
            nodes.add(new DiscoveryBenchmarkEvidence.EvidenceNode(id, "Node " + index, kind, 0, List.of()));
            if (index > 0) {
                edges.add(new DiscoveryBenchmarkEvidence.EvidenceEdge(
                    "n" + (index - 1),
                    id,
                    "rule_" + index,
                    "rule",
                    "core",
                    "core",
                    List.of()
                ));
            }
        }

        List<DiscoveryBenchmarkEvidence.EvidenceEdge> bridgeEdges = List.of(
            new DiscoveryBenchmarkEvidence.EvidenceEdge("n0", "n25", "bridge_0", "bridge", "operator", "core", List.of()),
            new DiscoveryBenchmarkEvidence.EvidenceEdge("n1", "n26", "bridge_1", "bridge", "operator", "core", List.of()),
            new DiscoveryBenchmarkEvidence.EvidenceEdge("n2", "n27", "bridge_2", "bridge", "operator", "core", List.of()),
            new DiscoveryBenchmarkEvidence.EvidenceEdge("n3", "n28", "bridge_3", "bridge", "operator", "core", List.of()),
            new DiscoveryBenchmarkEvidence.EvidenceEdge("n4", "n29", "bridge_4", "bridge", "operator", "core", List.of()),
            new DiscoveryBenchmarkEvidence.EvidenceEdge("n5", "n30", "bridge_5", "bridge", "operator", "core", List.of())
        );
        if (reverseBridgeOrder) {
            for (int index = bridgeEdges.size() - 1; index >= 0; index--) {
                edges.add(bridgeEdges.get(index));
            }
        } else {
            edges.addAll(bridgeEdges);
        }

        return new DiscoveryBenchmarkEvidence(
            "synthetic-scenario",
            "a",
            "b",
            true,
            "",
            new DiscoveryBenchmarkEvidence.SearchRunEvidence(true, "", List.of("a", "b"), List.of("rule_1"),
                new SearchSpaceAnalytics(31, edges.size(), 0, 0, 0.0d)),
            new DiscoveryBenchmarkEvidence.SearchRunEvidence(false, "", List.of(), List.of(),
                new SearchSpaceAnalytics(0, 0, 0, 0, 0.0d)),
            List.of(List.of("a", "b")),
            List.of(),
            List.of("synthetic"),
            List.of(),
            List.of(),
            List.of(),
            new SearchSpaceAnalytics(31, edges.size(), 0, 0, 0.0d),
            "PASS",
            nodes,
            edges,
            ""
        );
    }
}
