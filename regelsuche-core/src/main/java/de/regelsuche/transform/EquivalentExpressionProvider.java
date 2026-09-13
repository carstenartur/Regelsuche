package de.regelsuche.transform;

import de.regelsuche.ast.Expr;
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
        return (expression, profile) -> List.of(expression);
    }
}
