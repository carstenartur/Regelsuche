package de.regelsuche.transform;

import de.regelsuche.ast.Expr;
import de.regelsuche.retention.RetainedGraph;
import java.util.List;

/**
 * Supplies a bounded deterministic set of expressions equivalent to an input.
 * Implementations may use an e-class, normalization cache or recognition-safe
 * learned rules, but must not claim completeness. Representatives must be
 * equivalent without additional assumptions: this API has no assumption
 * channel, so guarded rewrites cannot supply unconditional representatives.
 */
@FunctionalInterface
public interface EquivalentExpressionProvider {
    List<Expr> representatives(Expr expression, RecognitionProfile profile);

    static EquivalentExpressionProvider identity() {
        return Identity.INSTANCE;
    }

    enum Identity implements EquivalentExpressionProvider, RetainedGraph.View {
        INSTANCE;
        @Override public List<Expr> representatives(Expr expression, RecognitionProfile profile) {
            return List.of(expression);
        }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { }
    }
}
