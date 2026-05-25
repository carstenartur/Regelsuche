package de.regelsuche.provenance;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ProvenanceGraph(
    List<ProvenanceNode> nodes,
    List<ProvenanceEdge> edges
) {
    public ProvenanceGraph {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }

    public Map<String, ProvenanceNode> nodeIndex() {
        Map<String, ProvenanceNode> byId = new LinkedHashMap<>();
        for (ProvenanceNode node : nodes) {
            byId.put(node.id(), node);
        }
        return byId;
    }
}
