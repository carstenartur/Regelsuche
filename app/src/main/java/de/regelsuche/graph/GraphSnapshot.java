package de.regelsuche.graph;

import java.util.List;

public record GraphSnapshot(List<String> nodes, List<GraphEdge> edges) {
    public GraphSnapshot {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }
}
