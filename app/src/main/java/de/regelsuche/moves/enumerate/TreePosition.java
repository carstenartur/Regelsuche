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
 * Identifies a single subtree within an expression by its stable child-index
 * {@code path} from the root together with the subtree's infix {@code text}.
 *
 * <p>An empty {@code path} denotes the whole expression (the root). Child paths
 * use {@code 0}/{@code 1} for the left/right operands of a binary node and the
 * argument index for a function node, so the path is reproducible across runs.</p>
 *
 * <h3>Why does {@code TreePosition} carry {@code text}?</h3>
 * <p>The {@code text} field serves as a <em>staleness guard</em>: when a
 * {@code TreePosition} is created from an enumeration result and later handed
 * back to {@link de.regelsuche.moves.apply.LocalRewriteApplier}, the applier
 * compares {@code text} against the formatted subtree it finds at {@code path}
 * in the live expression. If they differ (e.g. the expression was edited between
 * enumeration and application), the rewrite is rejected with a {@code "position
 * is stale"} failure rather than silently rewriting the wrong subtree.</p>
 *
 * <p>Carrying text also makes positions self-describing for display in the Rule
 * Authoring IDE and for JSON serialisation: a client receives a
 * {@link de.regelsuche.ide.RuleInspectionDto.PositionResult} and can render the
 * subtree label without an additional tree-walk.</p>
 *
 * <h3>Should {@code text} remain here long-term?</h3>
 * <p>The coupling is intentional and should stay. Alternatives (e.g. a separate
 * snapshot field, or a version counter) are more complex and offer no practical
 * advantage given that the text is cheap to produce at enumeration time and small
 * relative to the position record as a whole. If the formatter ever becomes
 * expensive, caching at the call site is preferable to removing the guard.</p>
 *
 * <h3>Selection and replacement authority</h3>
 * <p>The position owns the shared iterative AST navigation used by ordinary
 * local rewrites and verifier-authorized nested transformations. Both
 * {@link #subtreeAt(Expr)} and {@link #replaceAt(Expr, Expr)} return typed
 * outcomes that distinguish an invalid path from a valid path whose occurrence
 * is absent. They operate on AST objects rather than rendered text. Replacement
 * rebuilds only the selected ancestor chain and preserves every untouched sibling
 * by object identity.</p>
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

    /**
     * Selects the concrete subtree at this path.
     *
     * @param root complete expression tree
     * @return a typed success or failure result; invalid and absent paths remain distinct
     */
    public SelectionResult subtreeAt(Expr root) {
        Navigation navigation = navigate(root);
        return navigation.status() == Status.SELECTED
                ? SelectionResult.selected(path, navigation.selected())
                : SelectionResult.failure(navigation.status(), path);
    }

    /**
     * Replaces this occurrence and rebuilds only its ancestor chain. Untouched
     * siblings retain their original object identity.
     *
     * @param root complete expression tree
     * @param replacement subtree to install at this position
     * @return a typed success or failure result; invalid and absent paths remain distinct
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
            rewritten = rebuildParent(frame.parent(), frame.childIndex(), rewritten);
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
        if (root == null) {
            return Navigation.failure(Status.INVALID_PATH, path);
        }

        Expr current = root;
        List<ParentFrame> parents = new ArrayList<>(path.size());
        for (int childIndex : path) {
            if (childIndex < 0) {
                return Navigation.failure(Status.INVALID_PATH, path);
            }

            Expr child;
            if (current instanceof BinaryExpr binary) {
                child = switch (childIndex) {
                    case 0 -> binary.left();
                    case 1 -> binary.right();
                    default -> null;
                };
                if (child == null) {
                    return Navigation.failure(Status.INVALID_PATH, path);
                }
            } else if (current instanceof FunctionExpr function) {
                if (childIndex >= function.arguments().size()) {
                    return Navigation.failure(Status.INVALID_PATH, path);
                }
                child = function.arguments().get(childIndex);
            } else {
                return Navigation.failure(Status.POSITION_NOT_PRESENT, path);
            }

            parents.add(new ParentFrame(current, childIndex));
            current = child;
        }
        return Navigation.selected(path, current, parents);
    }

    private static Expr rebuildParent(Expr parent, int childIndex, Expr rewrittenChild) {
        if (parent instanceof BinaryExpr binary) {
            return switch (childIndex) {
                case 0 -> new BinaryExpr(rewrittenChild, binary.operator(), binary.right());
                case 1 -> new BinaryExpr(binary.left(), binary.operator(), rewrittenChild);
                default -> throw new IllegalStateException(
                        "validated binary path became invalid");
            };
        }
        if (parent instanceof FunctionExpr function) {
            List<Expr> arguments = new ArrayList<>(function.arguments());
            arguments.set(childIndex, rewrittenChild);
            return new FunctionExpr(function.name(), arguments);
        }
        throw new IllegalStateException("validated path parent is not traversable");
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

    /** Outcome categories shared by iterative selection and replacement. */
    public enum Status {
        SELECTED,
        REPLACED,
        INVALID_PATH,
        POSITION_NOT_PRESENT
    }

    /** Typed selection outcome. A successful result exposes the selected subtree. */
    public record SelectionResult(
            Status status,
            List<Integer> path,
            Optional<Expr> selectedSubtree) {
        public SelectionResult {
            status = Objects.requireNonNull(status, "status");
            path = List.copyOf(path);
            selectedSubtree = Objects.requireNonNull(selectedSubtree, "selectedSubtree");
            boolean selected = status == Status.SELECTED;
            if (status == Status.REPLACED || selected != selectedSubtree.isPresent()) {
                throw new IllegalArgumentException("tree selection status/payload mismatch");
            }
        }

        private static SelectionResult selected(List<Integer> path, Expr selectedSubtree) {
            return new SelectionResult(
                    Status.SELECTED,
                    path,
                    Optional.of(selectedSubtree));
        }

        private static SelectionResult failure(Status status, List<Integer> path) {
            if (status == Status.SELECTED || status == Status.REPLACED) {
                throw new IllegalArgumentException(
                        "successful status cannot describe selection failure");
            }
            return new SelectionResult(status, path, Optional.empty());
        }

        public boolean success() {
            return status == Status.SELECTED;
        }
    }

    /**
     * Typed replacement outcome. A successful result exposes both the selected
     * original subtree and the rewritten root; failures expose neither.
     */
    public record ReplacementResult(
            Status status,
            List<Integer> path,
            Optional<Expr> selectedSubtree,
            Optional<Expr> rewrittenRoot,
            int copiedAncestors) {
        public ReplacementResult {
            status = Objects.requireNonNull(status, "status");
            path = List.copyOf(path);
            selectedSubtree = Objects.requireNonNull(selectedSubtree, "selectedSubtree");
            rewrittenRoot = Objects.requireNonNull(rewrittenRoot, "rewrittenRoot");
            if (copiedAncestors < 0) {
                throw new IllegalArgumentException("copied ancestor count must not be negative");
            }
            boolean replaced = status == Status.REPLACED;
            if (replaced != selectedSubtree.isPresent()
                    || replaced != rewrittenRoot.isPresent()
                    || !replaced && copiedAncestors != 0) {
                throw new IllegalArgumentException("tree replacement status/payload mismatch");
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

        private static ReplacementResult failure(Status status, List<Integer> path) {
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
                throw new IllegalArgumentException("child index must not be negative");
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
                    || status == Status.SELECTED && parents.size() != path.size()
                    || status != Status.SELECTED && !parents.isEmpty()) {
                throw new IllegalArgumentException("tree navigation status/payload mismatch");
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

        private static Navigation failure(Status status, List<Integer> path) {
            if (status == Status.SELECTED || status == Status.REPLACED) {
                throw new IllegalArgumentException(
                        "successful status cannot describe navigation failure");
            }
            return new Navigation(status, path, null, List.of());
        }
    }
}
