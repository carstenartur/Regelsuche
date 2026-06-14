package de.regelsuche.moves.enumerate;

import de.regelsuche.ast.Expr;
import de.regelsuche.moves.MoveParameter;
import java.util.List;

/**
 * A finite, deterministic enumerator of candidate {@link MoveParameter}s for a
 * given expression. Implementations must never produce infinite parameter
 * spaces and must return results in a stable, reproducible order.
 */
public interface ParameterEnumerator {

    /** @return a short, stable id identifying this enumerator. */
    String id();

    /**
     * Enumerates the candidate parameters for {@code expression}.
     *
     * @param expression the current expression
     * @return a deterministically ordered, finite list of candidate parameters
     */
    List<MoveParameter> enumerate(String expression);

    /**
     * Enumerates candidate parameters directly from an already-parsed
     * {@link Expr} node, avoiding a round-trip through the expression formatter
     * and parser. The default implementation formats the node back to text and
     * delegates to {@link #enumerate(String)}.
     *
     * @param expr the already-parsed expression node
     * @return a deterministically ordered, finite list of candidate parameters
     */
    default List<MoveParameter> enumerate(Expr expr) {
        return enumerate(MoveExpressions.format(expr));
    }
}
