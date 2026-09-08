package de.regelsuche.api.searchgraph.semantic;

import java.util.List;
import java.util.Map;
import de.regelsuche.transform.RecordedExecution;

public record SemanticSearchGraphDto(
    List<SemanticGraphNodeDto> nodes,
    List<SemanticGraphEdgeDto> edges,
    List<SemanticGraphClusterDto> clusters,
    SemanticGraphStatsDto stats,
    SemanticGraphViewConfigDto view,
    Map<String, RecordedExecution> executionObservations
) {
    public SemanticSearchGraphDto {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        executionObservations = executionObservations == null ? Map.of() : Map.copyOf(executionObservations);
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

    public SemanticSearchGraphDto(List<SemanticGraphNodeDto> nodes, List<SemanticGraphEdgeDto> edges,
            List<SemanticGraphClusterDto> clusters, SemanticGraphStatsDto stats, SemanticGraphViewConfigDto view) {
        this(nodes, edges, clusters, stats, view, Map.of());
    }
}
