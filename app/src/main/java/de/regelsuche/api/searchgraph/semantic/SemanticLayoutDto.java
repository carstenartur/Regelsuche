package de.regelsuche.api.searchgraph.semantic;

import java.util.List;
import java.util.Map;

public record SemanticLayoutDto(
    Map<String, SemanticPositionDto> positions,
    List<SemanticLayerDto> layers,
    SemanticLayoutKind kind
) {
    public SemanticLayoutDto {
        positions = positions == null ? Map.of() : Map.copyOf(positions);
        layers = layers == null ? List.of() : List.copyOf(layers);
        kind = kind == null ? SemanticLayoutKind.MAIN_PATH_LAYERED : kind;
    }
}
