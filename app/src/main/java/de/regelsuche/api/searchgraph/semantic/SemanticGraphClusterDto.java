package de.regelsuche.api.searchgraph.semantic;

import java.util.List;

public record SemanticGraphClusterDto(
    String id,
    String label,
    SemanticClusterKind kind,
    List<String> nodeIds,
    int hiddenNodeCount,
    double cohesion
) {
    public SemanticGraphClusterDto {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        label = label == null ? "" : label;
        kind = kind == null ? SemanticClusterKind.CANONICAL_EQUIVALENCE : kind;
        nodeIds = nodeIds == null ? List.of() : List.copyOf(nodeIds);
    }
}
