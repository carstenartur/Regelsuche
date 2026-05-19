package de.regelsuche.graph;

public record GraphEdge(String fromExpression, String toExpression, String transformationRule, int depth, int improvement) {
}
