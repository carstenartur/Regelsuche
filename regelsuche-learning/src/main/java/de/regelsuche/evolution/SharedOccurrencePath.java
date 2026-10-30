package de.regelsuche.evolution;

import de.regelsuche.retention.RetainedGraph;
import java.util.AbstractList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/** Immutable binary occurrence path: one new node per edge, one copy per used path. */
final class SharedOccurrencePath extends AbstractList<Integer> implements RetainedGraph.View {
    private static final SharedOccurrencePath ROOT = new SharedOccurrencePath(null, -1, 0);
    private final SharedOccurrencePath parent;
    private final int edge;
    private final int depth;

    private SharedOccurrencePath(SharedOccurrencePath parent, int edge, int depth) {
        this.parent = parent;
        this.edge = edge;
        this.depth = depth;
    }

    static SharedOccurrencePath root() { return ROOT; }

    SharedOccurrencePath child(int index) {
        if (index != 0 && index != 1) throw new IllegalArgumentException("nonbinary occurrence edge");
        return new SharedOccurrencePath(this, index, Math.addExact(depth, 1));
    }

    @Override public int size() { return depth; }

    @Override public Integer get(int index) {
        Objects.checkIndex(index, depth);
        var node = this;
        for (int remaining = depth - index - 1; remaining > 0; remaining--) node = node.parent;
        return node.edge;
    }

    /** Linear root-to-leaf projection; callers account for allocation and materialization. */
    List<Integer> materialize() {
        if (depth == 0) return List.of();
        var edges = new Integer[depth];
        copyInto(edges);
        return List.of(edges);
    }

    void copyInto(Integer[] edges) {
        Objects.requireNonNull(edges, "edges");
        if (edges.length != depth) throw new IllegalArgumentException("path buffer length mismatch");
        var node = this;
        for (int offset = depth - 1; offset >= 0; offset--) {
            edges[offset] = node.edge;
            node = node.parent;
        }
    }

    // AbstractList's default iterator repeatedly calls get(), which would be quadratic.
    @Override public Iterator<Integer> iterator() { return materialize().iterator(); }

    @Override public void retainedReferences(RetainedGraph.Visitor visitor) {
        visitor.reference(parent);
    }
}
