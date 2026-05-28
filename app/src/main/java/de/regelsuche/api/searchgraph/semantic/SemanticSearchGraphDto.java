package de.regelsuche.api.searchgraph.semantic;

import java.util.List;

public record SemanticSearchGraphDto(
    List<SemanticGraphNodeDto> nodes,
    List<SemanticGraphEdgeDto> edges,
    List<SemanticGraphClusterDto> clusters,
    SemanticGraphStatsDto stats,
    SemanticGraphViewConfigDto view
) {
    public SemanticSearchGraphDto {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        clusters = clusters == null ? List.of() : List.copyOf(clusters);
        stats = stats == null ? new SemanticGraphStatsDto(0, 0, 0, 0, 0, 0, 0, 0) : stats;
        view = view == null ? new SemanticGraphViewConfigDto(
            SemanticGraphViewMode.SEMANTIC,
            SemanticMacroStepDisplay.COMPACT,
            false,
            true,
            false,
            12,
            8,
            new SemanticLayoutDto(java.util.Map.of(), java.util.List.of(), SemanticLayoutKind.MAIN_PATH_LAYERED)
        ) : view;
    }
}
