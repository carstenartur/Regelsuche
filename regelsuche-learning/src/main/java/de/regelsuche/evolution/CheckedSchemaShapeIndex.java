package de.regelsuche.evolution;

import de.regelsuche.retention.RetainedGraph;
import java.util.Objects;

/**
 * Conservative root/left/right discrimination for an already ordered schema bucket.
 * Bits retain the original positions; filtering never refills a capped prefix.
 * A set bit is only a possible match, never mathematical or binding evidence.
 */
final class CheckedSchemaShapeIndex implements RetainedGraph.View {
    static final int ANY = -1;
    static final long QUERY_WORK = 18;
    private static final int AXES = 3;
    private final int shapeCount;
    private final int size;
    private final long[] masks;
    private final long compilationWork;

    CheckedSchemaShapeIndex(int shapeCount, int[][] requirements) {
        if (shapeCount < 1 || shapeCount > 256) {
            throw new IllegalArgumentException("unsupported shape alphabet");
        }
        Objects.requireNonNull(requirements, "requirements");
        if (requirements.length > Long.SIZE) {
            throw new IllegalArgumentException("more than 64 indexed schemas");
        }
        this.shapeCount = shapeCount;
        size = requirements.length;
        masks = new long[AXES * (shapeCount + 1)];
        long work = masks.length + 1L;
        for (int row = 0; row < size; row++) {
            int[] requirement = Objects.requireNonNull(requirements[row], "requirement");
            if (requirement.length != AXES) {
                throw new IllegalArgumentException("expected root/left/right shapes");
            }
            for (int axis = 0; axis < AXES; axis++) {
                int shape = requirement[axis];
                if (shape < ANY || shape >= shapeCount) {
                    throw new IllegalArgumentException("shape outside index alphabet");
                }
                int slot = axis * (shapeCount + 1) + (shape == ANY ? shapeCount : shape);
                masks[slot] |= 1L << row;
                work = Math.addExact(work, 4);
            }
            work = Math.addExact(work, 1);
        }
        compilationWork = work;
    }

    long compilationWork() { return compilationWork; }

    long candidates(int root, int left, int right, int prefixLength) {
        if (prefixLength < 0 || prefixLength > size) {
            throw new IllegalArgumentException("prefix outside schema bucket");
        }
        return allowed(0, root) & allowed(1, left) & allowed(2, right) & prefix(prefixLength);
    }

    static long prefix(int size) {
        if (size < 0 || size > Long.SIZE) throw new IllegalArgumentException("invalid mask size");
        return size == Long.SIZE ? -1L : (1L << size) - 1;
    }

    private long allowed(int axis, int shape) {
        if (shape < 0 || shape >= shapeCount) {
            throw new IllegalArgumentException("query shape outside index alphabet");
        }
        int offset = axis * (shapeCount + 1);
        return masks[offset + shape] | masks[offset + shapeCount];
    }

    @Override public void retainedReferences(RetainedGraph.Visitor visitor) {
        visitor.reference(masks);
    }
}
