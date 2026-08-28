package de.regelsuche.moves.apply;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Selects and replaces AST occurrences by the stable child-index path used by
 * {@code TreePosition}.
 *
 * <p>The implementation is iterative. It therefore supports deep paths without
 * consuming the Java call stack and preserves every untouched child reference.
 * Strings are deliberately absent from this layer: display text and staleness
 * checks remain responsibilities of the caller.</p>
 */
public final class TreeExpressionEditor {
    private TreeExpressionEditor() {
    }

    /** Returns the concrete subtree at {@code path}, or empty for an invalid or missing path. */
    public static Optional<Expr> subtreeAt(Expr root, List<Integer> path) {
        Navigation navigation = navigate(root, path);
        return navigation.status() == Status.SELECTED
            ? Optional.of(navigation.selected())
            : Optional.empty();
    }

    /**
     * Replaces exactly one selected occurrence and rebuilds only its ancestor
     * chain. No parser or formatter round-trip is performed.
     */
    public static ReplacementResult replaceAt(
        Expr root,
        List<Integer> path,
        Expr replacement
    ) {
        Objects.requireNonNull(replacement, "replacement");
        Navigation navigation = navigate(root, path);
        if (navigation.status() != Status.SELECTED) {
            return ReplacementResult.failure(
                navigation.status(),
                navigation.path());
        }

        Expr rewritten = replacement;
        List<ParentFrame> parents = navigation.parents();
        for (int frameIndex = parents.size() - 1;
                frameIndex >= 0;
                frameIndex--) {
            ParentFrame frame = parents.get(frameIndex);
            rewritten = rebuildParent(
                frame.parent(),
                frame.childIndex(),
                rewritten);
        }
        return ReplacementResult.replaced(
            navigation.path(),
            navigation.selected(),
            rewritten,
            parents.size());
    }

    private static Navigation navigate(Expr root, List<Integer> path) {
        List<Integer> stablePath = validatePath(path);
        if (root == null || stablePath == null) {
            return Navigation.failure(Status.INVALID_PATH, List.of());
        }

        Expr current = root;
        List<ParentFrame> parents = new ArrayList<>(stablePath.size());
        for (int childIndex : stablePath) {
            Expr child = childAt(current, childIndex);
            if (child == null) {
                return Navigation.failure(
                    Status.POSITION_NOT_PRESENT,
                    stablePath);
            }
            parents.add(new ParentFrame(current, childIndex));
            current = child;
        }
        return Navigation.selected(stablePath, current, parents);
    }

    private static List<Integer> validatePath(List<Integer> path) {
        if (path == null) {
            return null;
        }
        List<Integer> stable = new ArrayList<>(path.size());
        for (Integer index : path) {
            if (index == null || index < 0) {
                return null;
            }
            stable.add(index);
        }
        return List.copyOf(stable);
    }

    private static Expr childAt(Expr expression, int childIndex) {
        if (expression instanceof BinaryExpr binary) {
            return switch (childIndex) {
                case 0 -> binary.left();
                case 1 -> binary.right();
                default -> null;
            };
        }
        if (expression instanceof FunctionExpr function) {
            return childIndex < function.arguments().size()
                ? function.arguments().get(childIndex)
                : null;
        }
        return null;
    }

    private static Expr rebuildParent(
        Expr parent,
        int childIndex,
        Expr rewrittenChild
    ) {
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

    public enum Status {
        SELECTED,
        REPLACED,
        INVALID_PATH,
        POSITION_NOT_PRESENT
    }

    /** Result of one structural replacement attempt. */
    public record ReplacementResult(
        Status status,
        List<Integer> path,
        Optional<Expr> selectedSubtree,
        Optional<Expr> rewrittenRoot,
        int copiedAncestors
    ) {
        public ReplacementResult {
            status = Objects.requireNonNull(status, "status");
            path = List.copyOf(Objects.requireNonNull(path, "path"));
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
            int copiedAncestors
        ) {
            return new ReplacementResult(
                Status.REPLACED,
                path,
                Optional.of(selectedSubtree),
                Optional.of(rewrittenRoot),
                copiedAncestors);
        }

        private static ReplacementResult failure(
            Status status,
            List<Integer> path
        ) {
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
        List<ParentFrame> parents
    ) {
        private Navigation {
            status = Objects.requireNonNull(status, "status");
            path = List.copyOf(Objects.requireNonNull(path, "path"));
            parents = List.copyOf(Objects.requireNonNull(parents, "parents"));
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
            List<ParentFrame> parents
        ) {
            return new Navigation(
                Status.SELECTED,
                path,
                Objects.requireNonNull(selected, "selected"),
                parents);
        }

        private static Navigation failure(
            Status status,
            List<Integer> path
        ) {
            if (status == Status.SELECTED || status == Status.REPLACED) {
                throw new IllegalArgumentException(
                    "successful status cannot describe navigation failure");
            }
            return new Navigation(status, path, null, List.of());
        }
    }
}
