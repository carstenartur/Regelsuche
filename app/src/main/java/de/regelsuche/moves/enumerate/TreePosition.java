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

    /** Lexicographic by path, then by subtree text, for deterministic ordering. */
    public static final Comparator<TreePosition> CANONICAL_ORDER =
            Comparator.comparing(TreePosition::pathKey).thenComparing(TreePosition::text);

    public TreePosition {
        path = List.copyOf(path);
    }

    /** {@code true} when this position denotes the whole expression. */
    public boolean isRoot() {
        return path.isEmpty();
    }

    /**
     * A stable, sortable rendering of the path, e.g. {@code "root"} for the root
     * or {@code "0.1"} for {@code root.left.right}. Each index is zero-padded so
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
}
