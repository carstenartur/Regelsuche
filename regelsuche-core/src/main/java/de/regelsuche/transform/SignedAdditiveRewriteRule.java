package de.regelsuche.transform;

import de.regelsuche.assumption.ExpressionDefinedness;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;

/**
 * Ordinary core rule for signed-term cancellation and reusable differences.
 * The same registered rule is lifted by representation adapters; no consumer
 * formula or Java numeric type participates in this implementation.
 *
 * <p>This conservative rule requires a bounded, total real-domain expression.
 * In particular it must not turn {@code (a + 1/x) - 1/x} into {@code a} and
 * silently lose the original domain. Typed consumers still independently
 * verify their own overflow, narrowing, and exception semantics.</p>
 */
public final class SignedAdditiveRewriteRule implements RewriteRule, RetainedGraph.View {
    public static final String ID = "ast_signed_additive_normalize";
    private static final int MAX_INSPECTED_NODES = 512;

    @Override public String id() { return ID; }
    @Override public RewriteKind kind() { return RewriteKind.NORMALIZE; }
    @Override public boolean mayIncreaseComplexity() { return true; }
    @Override public int estimatedCostDelta() { return 0; }
    @Override public boolean isEquivalencePreservingByConstruction() { return true; }
    @Override public void retainedReferences(RetainedGraph.Visitor visitor) { }

    @Override public boolean matches(Expr subtree) {
        return normalized(subtree) != null;
    }

    @Override public Expr apply(Expr subtree) {
        Expr result = normalized(subtree);
        if (result == null) throw new IllegalArgumentException("Rule does not match subtree");
        return result;
    }

    private static Expr normalized(Expr subtree) {
        RetainedOperation.work(1);
        if (!(subtree instanceof BinaryExpr binary)
                || binary.operator() != BinaryOperator.ADD && binary.operator() != BinaryOperator.SUB)
            return null;
        var shape = new BoundedShape();
        RetainedOperation.work(1);
        if (!shape.inspect(subtree, 0, SignedAdditiveForm.MAX_DEPTH)) return null;
        // Definedness is shared with other simplifiers, not reimplemented here.
        RetainedOperation.work(shape.nodes);
        if (!ExpressionDefinedness.isTotal(subtree)) return null;
        var result = SignedAdditiveForm.normalize(subtree, units -> {
            RetainedOperation.work(units);
            RetainedOperation.checkpoint();
        });
        if (!result.complete()) return null;
        // The rule has no enclosing-path budget. Never increase local depth:
        // this also preserves every enclosing transport's existing depth bound.
        // Inspect opaque terms too, before structural equality or admission.
        int sourceDepth = shape.maximumDepth;
        shape.nodes = 0;
        shape.maximumDepth = 0;
        RetainedOperation.work(1);
        if (!shape.inspect(result.expression(), 0, sourceDepth)) return null;
        return !result.expression().equals(subtree) ? result.expression() : null;
    }

    /** Count expanded visits before any recursive domain inspection or term hashing. */
    private static final class BoundedShape {
        private int nodes;
        private int maximumDepth;

        private boolean inspect(Expr expression, int depth, int depthLimit) {
            RetainedOperation.work(1);
            if (++nodes > MAX_INSPECTED_NODES || depth > depthLimit) return false;
            maximumDepth = Math.max(maximumDepth, depth);
            if (expression instanceof BinaryExpr binary) {
                return inspect(binary.left(), depth + 1, depthLimit)
                        && inspect(binary.right(), depth + 1, depthLimit);
            } else if (expression instanceof FunctionExpr function) {
                for (Expr argument : function.arguments()) {
                    if (!inspect(argument, depth + 1, depthLimit)) return false;
                }
            }
            return true;
        }
    }
}
