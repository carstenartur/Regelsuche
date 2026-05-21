package de.regelsuche.egraph;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * One e-class inside an {@link EGraph}: the set of {@link ENode}s that
 * have been proven equivalent under the current rewrites.
 *
 * <p>Mutation (adding nodes, merging-in another class) is package-private
 * — only {@link EGraph} drives the lifecycle so external callers cannot
 * leave the graph in an inconsistent state.</p>
 */
public final class EClass {

    private final EClassId id;
    private final LinkedHashSet<ENode> nodes = new LinkedHashSet<>();
    /**
     * Parents of this class: e-nodes that mention this class as one of
     * their children. Used by {@link EGraph#rebuild()} to detect new
     * congruences after a {@code union(...)} call.
     */
    private final LinkedHashSet<ENode> parents = new LinkedHashSet<>();

    EClass(EClassId id, ENode initialNode) {
        this.id = id;
        this.nodes.add(initialNode);
    }

    /** Class identifier — stable for the lifetime of this {@link EGraph}. */
    public EClassId id() {
        return id;
    }

    /** Unmodifiable snapshot of the e-nodes that live in this class. */
    public Set<ENode> nodes() {
        return Collections.unmodifiableSet(nodes);
    }

    /** Unmodifiable snapshot of the parent e-nodes pointing at this class. */
    public Set<ENode> parents() {
        return Collections.unmodifiableSet(parents);
    }

    void addNode(ENode node) {
        nodes.add(node);
    }

    void addParent(ENode parent) {
        parents.add(parent);
    }

    void absorb(EClass other) {
        this.nodes.addAll(other.nodes);
        this.parents.addAll(other.parents);
    }

    void clearParents() {
        parents.clear();
    }

    void replaceNodes(Set<ENode> replacement) {
        nodes.clear();
        nodes.addAll(replacement);
    }

    @Override
    public String toString() {
        return "EClass(" + id + ", nodes=" + nodes + ")";
    }
}
