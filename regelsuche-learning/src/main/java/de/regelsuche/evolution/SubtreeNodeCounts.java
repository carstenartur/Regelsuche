package de.regelsuche.evolution;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.search.moves.TypedMoveSearch;
import de.regelsuche.search.moves.TypedSourceOnlySearch;
import de.regelsuche.search.program.AstExpressionValidation;
import de.regelsuche.transform.AstRewriteTransport;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;

/** Query-local occurrence counts. A rewritten root often keeps an entire child by identity. */
final class SubtreeNodeCounts implements TypedSourceOnlySearch.Objective {
    private static final int CAPACITY = 128;
    private final IdentityHashMap<Expr, Integer> sizes = new IdentityHashMap<>();
    private final ArrayDeque<Expr> pending = new ArrayDeque<>();
    private long work = 3; // Create the counter, its identity table and traversal stack.

    @Override public TypedSourceOnlySearch.Score evaluate(TypedMoveSearch.State state) {
        var root = state.expression();
        work++;
        var count = sizes.get(root);
        if (count == null) {
            work++; // Inspect the root.
            int size = 1;
            if (root instanceof BinaryExpr binary) {
                size += childSize(binary.left()) + childSize(binary.right());
            } else if (root instanceof FunctionExpr function) {
                for (var argument : function.arguments()) {
                    size += childSize(argument);
                    validate(size);
                }
            }
            validate(size);
            remember(root, size);
            count = size;
        }
        long price = work;
        work = 0;
        return new TypedSourceOnlySearch.Score(count, price);
    }

    private int childSize(Expr expression) {
        work++; // Leaves have a known size after inspecting their kind.
        if (!(expression instanceof BinaryExpr) && !(expression instanceof FunctionExpr)) return 1;
        work++;
        var known = sizes.get(expression);
        if (known != null) return known;

        int count = 1;
        pushChildren(expression, count);
        while (!pending.isEmpty()) {
            var next = pending.removeLast();
            work++;
            pushChildren(next, ++count);
        }
        remember(expression, count);
        return count;
    }

    private void pushChildren(Expr expression, int visited) {
        if (expression instanceof BinaryExpr binary) {
            validate((long) visited + pending.size() + 2);
            pending.add(binary.left());
            pending.add(binary.right());
        } else if (expression instanceof FunctionExpr function) {
            validate((long) visited + pending.size() + function.arguments().size());
            pending.addAll(function.arguments());
        }
    }

    private void remember(Expr expression, int count) {
        if (sizes.size() == CAPACITY) {
            work += 1L + sizes.size();
            sizes.clear();
        }
        sizes.put(expression, count);
        work++;
    }

    private void validate(long size) {
        if (size > AstRewriteTransport.MAXIMUM_NODES) {
            work += pending.size();
            pending.clear();
            throw new AstExpressionValidation.InvalidExpression("objective occurrence limit exceeded");
        }
    }
}
