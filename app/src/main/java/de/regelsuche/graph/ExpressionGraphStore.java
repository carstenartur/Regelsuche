package de.regelsuche.graph;

public interface ExpressionGraphStore extends AutoCloseable {
    void saveNode(String expression, int complexity);

    void saveEdge(GraphEdge edge);

    GraphSnapshot snapshot();

    @Override
    default void close() {
    }
}
