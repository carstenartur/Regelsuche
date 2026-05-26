package de.regelsuche.provenance;

import java.util.List;
import java.util.Optional;

public interface ProvenanceRepository {
    void save(String runId, ProvenanceGraph graph);

    Optional<ProvenanceGraph> findByRunId(String runId);

    default List<ProvenanceNode> adjacent(String runId, String nodeId, ProvenanceEdgeType edgeType) {
        return findByRunId(runId)
            .map(graph -> {
                var nodes = graph.nodeIndex();
                return graph.edges().stream()
                    .filter(edge -> edge.type() == edgeType)
                    .filter(edge -> edge.fromId().equals(nodeId))
                    .map(edge -> nodes.get(edge.toId()))
                    .filter(java.util.Objects::nonNull)
                    .toList();
            })
            .orElse(List.of());
    }
}
