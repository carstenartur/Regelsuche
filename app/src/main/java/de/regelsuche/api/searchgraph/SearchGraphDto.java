package de.regelsuche.api.searchgraph;

import java.util.List;
import java.util.Map;

/**
 * Top-level DTO returned by {@code GET /api/search-graph} (to be wired up in Step 2).
 *
 * <p>See {@code docs/visual-search-graph.md}.
 */
public record SearchGraphDto(
    List<SearchGraphNodeDto> nodes,
    List<SearchGraphEdgeDto> edges,
    List<SearchGraphClusterDto> clusters,
    SearchGraphStatsDto stats
) {
    public SearchGraphDto {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        clusters = clusters == null ? List.of() : List.copyOf(clusters);
        if (stats == null) {
            stats = new SearchGraphStatsDto(
                nodes.size(), edges.size(), 0, 0, 0.0, 0,
                Map.of(), List.of(), 0, 0);
        }
    }
}
