package de.regelsuche.api.searchgraph.semantic;

public record SemanticGraphStatsDto(
    int rawNodeCount,
    int rawEdgeCount,
    int visibleNodeCount,
    int visibleEdgeCount,
    int collapsedVariantCount,
    int lowSignalEdgeCount,
    int macroMoveEdgeCount,
    int mainPathLength
) {
}
