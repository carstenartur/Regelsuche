package de.regelsuche.egraph;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple union-find / disjoint-set forest over {@link EClassId}s with
 * path compression and union-by-rank.
 *
 * <p>The {@link EGraph} uses this structure to track which e-classes have
 * been merged. {@link #find} returns the canonical representative; {@link
 * #union} returns the surviving root after merging two classes.</p>
 */
public final class UnionFind {

    private final List<Integer> parent = new ArrayList<>();
    private final List<Integer> rank = new ArrayList<>();

    /**
     * Add a fresh singleton set and return its (initial) representative.
     */
    public EClassId makeSet() {
        int id = parent.size();
        parent.add(id);
        rank.add(0);
        return new EClassId(id);
    }

    /** Number of underlying singletons ever created. */
    public int size() {
        return parent.size();
    }

    /** Canonical representative of {@code id}'s set. */
    public EClassId find(EClassId id) {
        int x = id.value();
        if (x >= parent.size()) {
            throw new IllegalArgumentException("Unknown EClassId: " + id);
        }
        // iterative path compression — avoid recursion for deep chains.
        int root = x;
        while (parent.get(root) != root) {
            root = parent.get(root);
        }
        int cursor = x;
        while (parent.get(cursor) != root) {
            int next = parent.get(cursor);
            parent.set(cursor, root);
            cursor = next;
        }
        return new EClassId(root);
    }

    /**
     * Merge the sets containing {@code a} and {@code b}. Returns the root
     * that survived the merge; the other root is now an alias for it.
     */
    public EClassId union(EClassId a, EClassId b) {
        int rootA = find(a).value();
        int rootB = find(b).value();
        if (rootA == rootB) {
            return new EClassId(rootA);
        }
        int rA = rank.get(rootA);
        int rB = rank.get(rootB);
        int survivor;
        int merged;
        if (rA < rB) {
            survivor = rootB;
            merged = rootA;
        } else if (rA > rB) {
            survivor = rootA;
            merged = rootB;
        } else {
            // Prefer the smaller id for determinism in tests.
            survivor = Math.min(rootA, rootB);
            merged = Math.max(rootA, rootB);
            rank.set(survivor, rA + 1);
        }
        parent.set(merged, survivor);
        return new EClassId(survivor);
    }

    /** {@code true} iff {@code a} and {@code b} are in the same set. */
    public boolean inSameSet(EClassId a, EClassId b) {
        return find(a).equals(find(b));
    }
}
