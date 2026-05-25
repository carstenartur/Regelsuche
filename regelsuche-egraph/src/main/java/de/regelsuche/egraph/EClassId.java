package de.regelsuche.egraph;

/**
 * Identifier of an {@link EClass} inside an {@link EGraph}.
 *
 * <p>IDs are stable for the lifetime of the e-graph instance: once issued
 * by {@link EGraph#add}, the integer never changes, even when the class
 * later merges with another class. After such a merge, the "current"
 * (canonical) ID of an old reference can be obtained via {@link
 * EGraph#find(EClassId)}. This is the same invariant as the
 * <a href="https://egraphs-good.github.io/">egg</a> library.</p>
 */
public record EClassId(int value) implements Comparable<EClassId> {

    public EClassId {
        if (value < 0) {
            throw new IllegalArgumentException("EClassId must be non-negative");
        }
    }

    @Override
    public int compareTo(EClassId other) {
        return Integer.compare(this.value, other.value);
    }

    @Override
    public String toString() {
        return "#" + value;
    }
}
