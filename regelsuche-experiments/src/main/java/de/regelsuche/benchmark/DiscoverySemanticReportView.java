package de.regelsuche.benchmark;

import java.util.List;

/** Semantic graph projection used by discovery report artifacts. */
public record DiscoverySemanticReportView(
    String renderer,
    int rawNodeCount,
    int rawEdgeCount,
    int semanticNodeCount,
    int semanticEdgeCount,
    int collapsedVariantCount,
    int collapsedLowSignalCount,
    List<SemanticPath> paths
) {
    public DiscoverySemanticReportView {
        renderer = renderer == null || renderer.isBlank() ? "semantic-main-path" : renderer;
        paths = paths == null ? List.of() : List.copyOf(paths);
    }

    public static DiscoverySemanticReportView fromReplayPaths(
        DeterministicDiscoveryExperimentRunner.DiscoveryReport report
    ) {
        List<SemanticPath> paths = (report == null ? List.<DeterministicDiscoveryExperimentRunner.SeedRunReport>of() : report.rows())
            .stream()
            .map(row -> {
                List<SemanticNode> nodes = row.replayPath().stream()
                    .map(step -> new SemanticNode("replay:" + row.seed().id() + ":" + Integer.toHexString(step.hashCode()), step))
                    .toList();
                return new SemanticPath(row.seed().id(), nodes, chainEdges(nodes));
            })
            .toList();
        int nodes = paths.stream().mapToInt(path -> path.nodes().size()).sum();
        int edges = paths.stream().mapToInt(path -> path.edges().size()).sum();
        return new DiscoverySemanticReportView("replay-main-path", nodes, edges, nodes, edges, 0, 0, paths);
    }

    private static List<SemanticEdge> chainEdges(List<SemanticNode> nodes) {
        java.util.ArrayList<SemanticEdge> edges = new java.util.ArrayList<>();
        for (int i = 1; i < nodes.size(); i++) {
            edges.add(new SemanticEdge(nodes.get(i - 1).id(), nodes.get(i).id(), "replay step", "MAIN_STEP", 0));
        }
        return edges;
    }

    public record SemanticPath(String seedId, List<SemanticNode> nodes, List<SemanticEdge> edges) {
        public SemanticPath {
            seedId = seedId == null ? "" : seedId;
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            edges = edges == null ? List.of() : List.copyOf(edges);
        }
    }

    public record SemanticNode(String id, String label) {
        public SemanticNode {
            id = id == null ? "" : id;
            label = label == null ? "" : label;
        }
    }

    public record SemanticEdge(String from, String to, String label, String kind, int collapsedCount) {
        public SemanticEdge {
            from = from == null ? "" : from;
            to = to == null ? "" : to;
            label = label == null ? "" : label;
            kind = kind == null ? "" : kind;
        }
    }
}
