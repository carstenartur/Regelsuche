package de.regelsuche.moves.hypothesis;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** Stable identity of one occurrence inside one owned syntax root. */
public record OccurrenceId(String root, List<Integer> path) implements Comparable<OccurrenceId> {
    public static final String EXPRESSION_ROOT = "expression";
    public static final String EQUATION_LEFT_ROOT = "equation:L";
    public static final String EQUATION_RIGHT_ROOT = "equation:R";

    public OccurrenceId {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(path, "path");
        root = root.trim();
        if (root.isEmpty()) {
            throw new IllegalArgumentException("occurrence root must not be blank");
        }
        path = List.copyOf(path);
        if (path.stream().anyMatch(index -> index == null || index < 0)) {
            throw new IllegalArgumentException("occurrence path indices must be non-negative");
        }
    }

    public static OccurrenceId expression(List<Integer> path) {
        return new OccurrenceId(EXPRESSION_ROOT, path);
    }

    public static OccurrenceId equationSide(String side, List<Integer> path) {
        return switch (Objects.requireNonNull(side, "side")) {
            case "L" -> new OccurrenceId(EQUATION_LEFT_ROOT, path);
            case "R" -> new OccurrenceId(EQUATION_RIGHT_ROOT, path);
            default -> throw new IllegalArgumentException("equation side must be L or R");
        };
    }

    public String externalForm() {
        if (path.isEmpty()) {
            return root + ":root";
        }
        return root + ":" + path.stream()
                .map(index -> String.format("%03d", index))
                .collect(Collectors.joining("."));
    }

    @Override
    public int compareTo(OccurrenceId other) {
        int rootComparison = root.compareTo(other.root);
        if (rootComparison != 0) {
            return rootComparison;
        }
        int common = Math.min(path.size(), other.path.size());
        for (int i = 0; i < common; i++) {
            int comparison = Integer.compare(path.get(i), other.path.get(i));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(path.size(), other.path.size());
    }

    @Override
    public String toString() {
        return externalForm();
    }
}
