package de.regelsuche.moves.enumerate;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Identifies a single subtree within an expression by its stable child-index
 * {@code path} from the root together with the subtree's infix {@code text}.
 *
 * <p>An empty {@code path} denotes the whole expression (the root). Child paths
 * use {@code 0}/{@code 1} for the left/right operands of a binary node and the
 * argument index for a function node, so the path is reproducible across runs.</p>
 */
public record TreePosition(List<Integer> path, String text) implements Comparable<TreePosition> {

    /**
     * Numeric path order (root/empty first, then element-wise), then by subtree text,
     * for deterministic ordering. Compares path lists directly so that the root
     * (empty path) always precedes non-root positions regardless of their display form.
     */
    public static final Comparator<TreePosition> CANONICAL_ORDER =
            Comparator.<TreePosition, List<Integer>>comparing(TreePosition::path, TreePosition::comparePaths)
                    .thenComparing(TreePosition::text);

    public TreePosition {
        path = List.copyOf(path);
    }

    /** {@code true} when this position denotes the whole expression. */
    public boolean isRoot() {
        return path.isEmpty();
    }

    /**
     * A stable, sortable rendering of the path, e.g. {@code "root"} for the root
     * or {@code "000.001"} for {@code root.left.right}. Each index is zero-padded so
     * that lexicographic comparison matches numeric order for paths up to depth
     * indices below 1000.
     */
    public String pathKey() {
        if (path.isEmpty()) {
            return "root";
        }
        return path.stream()
                .map(index -> String.format("%03d", index))
                .collect(Collectors.joining("."));
    }

    @Override
    public int compareTo(TreePosition other) {
        return CANONICAL_ORDER.compare(this, other);
    }

    private static int comparePaths(List<Integer> a, List<Integer> b) {
        int min = Math.min(a.size(), b.size());
        for (int i = 0; i < min; i++) {
            int cmp = Integer.compare(a.get(i), b.get(i));
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(a.size(), b.size());
    }
}
