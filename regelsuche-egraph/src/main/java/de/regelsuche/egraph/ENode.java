package de.regelsuche.egraph;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One node in an {@link EGraph}: a symbol (operator name, function name,
 * variable name, or numeric literal) together with the e-class IDs of its
 * children.
 *
 * <p>An e-node is the analogue of an AST node, but children are referenced
 * via {@link EClassId}s rather than by direct pointer. Two e-nodes are
 * structurally equal when they share the same {@code symbol} and the same
 * sequence of <em>canonical</em> child e-class IDs — which is how
 * {@link EGraph#rebuild()} discovers congruence ("if {@code a≡b} then
 * {@code f(a)≡f(b)}").</p>
 *
 * <p>This record is immutable; the e-graph achieves congruence closure by
 * <em>replacing</em> stale e-nodes via {@link #canonicalize}, not by
 * mutating them in place.</p>
 */
public record ENode(String symbol, List<EClassId> children) {

    public ENode {
        if (symbol == null || symbol.isEmpty()) {
            throw new IllegalArgumentException("symbol must not be empty");
        }
        children = children == null ? List.of() : List.copyOf(children);
    }

    /** Convenience constructor for leaves (variables, numbers). */
    public static ENode leaf(String symbol) {
        return new ENode(symbol, List.of());
    }

    /**
     * Return a copy of this node with every child replaced by its current
     * canonical (find-root) e-class ID under {@code unionFind}.
     */
    public ENode canonicalize(UnionFind unionFind) {
        if (children.isEmpty()) {
            return this;
        }
        EClassId[] canonical = new EClassId[children.size()];
        boolean changed = false;
        for (int i = 0; i < children.size(); i++) {
            EClassId before = children.get(i);
            EClassId after = unionFind.find(before);
            canonical[i] = after;
            if (!Objects.equals(before, after)) {
                changed = true;
            }
        }
        if (!changed) {
            return this;
        }
        return new ENode(symbol, List.of(canonical));
    }

    /** {@code true} if this node has no children. */
    public boolean isLeaf() {
        return children.isEmpty();
    }

    /** Read-only view of the child references. */
    public List<EClassId> childView() {
        return Collections.unmodifiableList(children);
    }
}
