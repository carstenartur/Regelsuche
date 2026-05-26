package de.regelsuche.api.searchgraph.semantic;

public record SemanticPositionDto(
    double x,
    double y,
    int layer,
    double complexity,
    int depth
) {
}
