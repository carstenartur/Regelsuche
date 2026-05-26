package de.regelsuche.api.searchgraph.semantic;

import java.util.List;

public record SemanticLayerDto(
    int index,
    String label,
    List<String> nodeIds
) {
    public SemanticLayerDto {
        label = label == null ? "" : label;
        nodeIds = nodeIds == null ? List.of() : List.copyOf(nodeIds);
    }
}
