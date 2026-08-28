package de.regelsuche.moves.enumerate;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Identifies one AST occurrence by a stable child-index path and a formatted
 * subtree snapshot. The text is a display/staleness guard, never mathematical
 * authority.
 *
 * <p>The position also owns the shared iterative selection and replacement
 * semantics used by local rules and verifier-authorized nested transformations.
 * An empty path denotes the root, binary children use {@code 0}/{@code 1}, and
 * function children use their argument index.</p>
 */
public record TreePosition(List<Integer> path, String text)
        implements Comparable<TreePosition> {

    public static final Comparator<TreePosition> CANONICAL_ORDER =
            Comparator.<TreePosition, List<Integer>>comparing(
                    TreePosition::path,
                    TreePosition::comparePaths)
                    .thenComparing(TreePosition::text);

    public TreePosition {
        path = List.copyOf(path);
    }

    public boolean isRoot() {
        return path.isEmpty();
    }

    public String pathKey() {
        if (path.isEmpty()) {
            return "root";
        }
        return path.stream()
                .map(index -> String.format("%03d", index))
                .collect(Collectors.joining("."));
    }

    /** Returns the concrete subtree at this path, or empty for an invalid path. */
    public Optional<Expr> subtreeAt(Expr root) {
        Navigation navigation = navigate(root);
        return navigation.status() == Status.SELECTED
                ? Optional.of(navigation.selected())
                : Optional.empty();
    }

    /**
     * Replaces this occurrence and rebuilds only its ancestor chain. Untouched
     * siblings retain their original object identity.
     */
    public ReplacementResult replaceAt(Expr root, Expr replacement) {
        Objects.requireNonNull(replacement, "replacement");
        Navigation navigation = navigate(root);
        if (navigation.status() != Status.SELECTED) {
            return ReplacementResult.failure(navigation.status(), path);
        }

        Expr rewritten = replacement;
        List<ParentFrame> parents = navigation.parents();
        for (int index = parents.size() - 1; index >= 0; index--) {
            ParentFrame frame = parents.get(index);
            rewritten = rebuildParent(
                    frame.parent(),
                    frame.childIndex(),
                    rewritten);
        }
        return ReplacementResult.replaced(
                path,
                navigation.selected(),
                rewritten,
                parents.size());
    }

    @Override
    public int compareTo(TreePosition other) {
        return CANONICAL_ORDER.compare(this, other);
    }

    private Navigation navigate(Expr root) {
        if (root == null || path.stream().anyMatch(index -> index < 0)) {
            return Navigation.failure(Status.INVALID_PATH, path);
        }

        Expr current = root;
        List<ParentFrame> parents = new ArrayList<>(path.size());
        for (int childIndex : path) {
            Expr child = childAt(current, childIndex);
            if (child == null) {
                return Navigation.failure(Status.POSITION_NOT_PRESENT, path);
            }
            parents.add(new ParentFrame(current, childIndex));
            current = child;
        }
        return Navigation.selected(path, current, parents);
    }

    private static Expr childAt(Expr expression, int childIndex) {
        if (expression instanceof BinaryExpr binary) {
            return switch (childIndex) {
                case 0 -> binary.left();
                case 1 -> binary.right();
                default -> null;
            };
        }
        if (expression instanceof FunctionExpr function
                && childIndex < function.arguments().size()) {
            return function.arguments().get(childIndex);
        }
        return null;
    }

    private static Expr rebuildParent(
            Expr parent,
            int childIndex,
            Expr rewrittenChild) {
        if (parent instanceof BinaryExpr binary) {
            return switch (childIndex) {
                case 0 -> new BinaryExpr(
                        rewrittenChild,
                        binary.operator(),
                        binary.right());
                case 1 -> new BinaryExpr(
                        binary.left(),
                        binary.operator(),
                        rewrittenChild);
                default -> throw new IllegalStateException(
                        "validated binary path became invalid");
            };
        }
        if (parent instanceof FunctionExpr function) {
            List<Expr> arguments = new ArrayList<>(function.arguments());
            arguments.set(childIndex, rewrittenChild);
            return new FunctionExpr(function.name(), arguments);
        }
        throw new IllegalStateException(
                "validated path parent is not traversable");
    }

    private static int comparePaths(List<Integer> first, List<Integer> second) {
        int minimum = Math.min(first.size(), second.size());
        for (int index = 0; index < minimum; index++) {
            int comparison = Integer.compare(
                    first.get(index),
                    second.get(index));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(first.size(), second.size());
    }

    public enum Status {
        SELECTED,
        REPLACED,
        INVALID_PATH,
        POSITION_NOT_PRESENT
    }

    public record ReplacementResult(
            Status status,
            List<Integer> path,
            Optional<Expr> selectedSubtree,
            Optional<Expr> rewrittenRoot,
            int copiedAncestors) {
        public ReplacementResult {
            status = Objects.requireNonNull(status, "status");
            path = List.copyOf(path);
            selectedSubtree = Objects.requireNonNull(
                    selectedSubtree,
                    "selectedSubtree");
            rewrittenRoot = Objects.requireNonNull(
                    rewrittenRoot,
                    "rewrittenRoot");
            if (copiedAncestors < 0) {
                throw new IllegalArgumentException(
                        "copied ancestor count must not be negative");
            }
            boolean replaced = status == Status.REPLACED;
            if (replaced != selectedSubtree.isPresent()
                    || replaced != rewrittenRoot.isPresent()
                    || !replaced && copiedAncestors != 0) {
                throw new IllegalArgumentException(
                        "tree replacement status/payload mismatch");
            }
        }

        private static ReplacementResult replaced(
                List<Integer> path,
                Expr selectedSubtree,
                Expr rewrittenRoot,
                int copiedAncestors) {
            return new ReplacementResult(
                    Status.REPLACED,
                    path,
                    Optional.of(selectedSubtree),
                    Optional.of(rewrittenRoot),
                    copiedAncestors);
        }

        private static ReplacementResult failure(
                Status status,
                List<Integer> path) {
            if (status == Status.SELECTED || status == Status.REPLACED) {
                throw new IllegalArgumentException(
                        "successful status cannot describe replacement failure");
            }
            return new ReplacementResult(
                    status,
                    path,
                    Optional.empty(),
                    Optional.empty(),
                    0);
        }

        public boolean success() {
            return status == Status.REPLACED;
        }
    }

    private record ParentFrame(Expr parent, int childIndex) {
        private ParentFrame {
            Objects.requireNonNull(parent, "parent");
            if (childIndex < 0) {
                throw new IllegalArgumentException(
                        "child index must not be negative");
            }
        }
    }

    private record Navigation(
            Status status,
            List<Integer> path,
            Expr selected,
            List<ParentFrame> parents) {
        private Navigation {
            status = Objects.requireNonNull(status, "status");
            path = List.copyOf(path);
            parents = List.copyOf(parents);
            if ((status == Status.SELECTED) != (selected != null)
                    || status == Status.SELECTED
                            && parents.size() != path.size()
                    || status != Status.SELECTED && !parents.isEmpty()) {
                throw new IllegalArgumentException(
                        "tree navigation status/payload mismatch");
            }
        }

        private static Navigation selected(
                List<Integer> path,
                Expr selected,
                List<ParentFrame> parents) {
            return new Navigation(
                    Status.SELECTED,
                    path,
                    Objects.requireNonNull(selected, "selected"),
                    parents);
        }

        private static Navigation failure(
                Status status,
                List<Integer> path) {
            if (status == Status.SELECTED || status == Status.REPLACED) {
                throw new IllegalArgumentException(
                        "successful status cannot describe navigation failure");
            }
            return new Navigation(status, path, null, List.of());
        }
    }
}
