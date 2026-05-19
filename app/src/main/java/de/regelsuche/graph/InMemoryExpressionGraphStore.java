package de.regelsuche.graph;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryExpressionGraphStore implements ExpressionGraphStore {
    private final Set<String> nodes = ConcurrentHashMap.newKeySet();
    private final List<GraphEdge> edges = java.util.Collections.synchronizedList(new ArrayList<>());

    @Override
    public void saveNode(String expression, int complexity) {
        nodes.add(expression);
    }

    @Override
    public void saveEdge(GraphEdge edge) {
        edges.add(edge);
    }

    @Override
    public GraphSnapshot snapshot() {
        List<GraphEdge> edgeCopy;
        synchronized (edges) {
            edgeCopy = new ArrayList<>(edges);
        }
        return new GraphSnapshot(new ArrayList<>(nodes), edgeCopy);
    }
}
