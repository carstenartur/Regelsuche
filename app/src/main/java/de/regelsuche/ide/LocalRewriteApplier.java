package de.regelsuche.ide;

import de.regelsuche.moves.MoveRealizer;
import de.regelsuche.moves.apply.LocalRewriteApplier.LocalRewriteResult;
import de.regelsuche.moves.enumerate.Depth1MoveEnumerator.CandidateMove;
import de.regelsuche.moves.enumerate.TreePosition;
import de.regelsuche.parse.ExpressionParser;
import java.util.List;

/**
 * @deprecated Use {@link de.regelsuche.moves.apply.LocalRewriteApplier} instead.
 *             This class is a thin forwarding wrapper kept for binary compatibility
 *             while callers migrate to the new, more central package.
 */
@Deprecated(forRemoval = true)
public final class LocalRewriteApplier {

    private final de.regelsuche.moves.apply.LocalRewriteApplier delegate;

    public LocalRewriteApplier() {
        this.delegate = new de.regelsuche.moves.apply.LocalRewriteApplier();
    }

    public LocalRewriteApplier(MoveRealizer realizer, ExpressionParser parser) {
        this.delegate = new de.regelsuche.moves.apply.LocalRewriteApplier(realizer, parser);
    }

    public LocalRewriteResult apply(
            String rootExpression, TreePosition position, CandidateMove candidate) {
        return delegate.apply(rootExpression, position, candidate);
    }

    public LocalRewriteResult apply(
            String rootExpression, TreePosition position, List<CandidateMove> candidates) {
        return delegate.apply(rootExpression, position, candidates);
    }
}
