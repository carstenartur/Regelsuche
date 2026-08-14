package de.regelsuche.discovery.representation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Domain-neutral child-index path from an expression root.
 *
 * <p>For binary nodes, child {@code 0} is the left operand and child {@code 1}
 * is the right operand. Function arguments use their zero-based argument index.</p>
 */
public record ExpressionOccurrencePath(List<Integer> childIndexes)
        implements Comparable<ExpressionOccurrencePath> {
    public ExpressionOccurrencePath {
        Objects.requireNonNull(childIndexes, "childIndexes");
        if (childIndexes.stream().anyMatch(index -> index == null || index < 0)) {
            throw new IllegalArgumentException("child indexes must be non-negative");
        }
        childIndexes = List.copyOf(childIndexes);
    }

    public static ExpressionOccurrencePath root() {
        return new ExpressionOccurrencePath(List.of());
    }

    public boolean isRoot() {
        return childIndexes.isEmpty();
    }

    public ExpressionOccurrencePath append(int childIndex) {
        if (childIndex < 0) {
            throw new IllegalArgumentException("child index must be non-negative");
        }
        List<Integer> next = new ArrayList<>(childIndexes);
        next.add(childIndex);
        return new ExpressionOccurrencePath(next);
    }

    public String canonical() {
        if (isRoot()) {
            return "/";
        }
        StringBuilder value = new StringBuilder();
        for (Integer childIndex : childIndexes) {
            value.append('/').append(childIndex);
        }
        return value.toString();
    }

    @Override
    public int compareTo(ExpressionOccurrencePath other) {
        Objects.requireNonNull(other, "other");
        int shared = Math.min(childIndexes.size(), other.childIndexes.size());
        for (int i = 0; i < shared; i++) {
            int compared = Integer.compare(childIndexes.get(i), other.childIndexes.get(i));
            if (compared != 0) {
                return compared;
            }
        }
        return Integer.compare(childIndexes.size(), other.childIndexes.size());
    }

    @Override
    public String toString() {
        return canonical();
    }
}
