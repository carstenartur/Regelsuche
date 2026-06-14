package de.regelsuche.moves.enumerate;

import de.regelsuche.ast.Expr;
import de.regelsuche.moves.enumerate.Depth1MoveEnumerator.CandidateMove;
import de.regelsuche.moves.enumerate.MoveExpressions.PositionedExpr;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Enumerates depth-1 candidate moves <em>tree-locally</em>: instead of treating
 * the expression as a single flat string, it visits every subtree position and
 * runs the finite parameter enumerators on that subtree, tagging each candidate
 * with the {@link TreePosition} it applies to.
 *
 * <p>This makes move enumeration position-aware (e.g. the same {@code complete
 * square} candidate can surface independently inside the left and right operands
 * of an equation) while remaining fully deterministic and reproducible: the
 * result is canonically ordered by position and then by the underlying
 * {@link CandidateMove} ordering.</p>
 */
public final class TreeLocalMoveEnumerator {

    private final Depth1MoveEnumerator delegate;

    public TreeLocalMoveEnumerator() {
        this(new Depth1MoveEnumerator());
    }

    public TreeLocalMoveEnumerator(Depth1MoveEnumerator delegate) {
        this.delegate = delegate;
    }

    /**
     * @param expression the current expression
     * @return a deterministically ordered list of position-tagged candidate moves
     */
    public List<LocalCandidateMove> enumerate(String expression) {
        return MoveExpressions.parse(expression)
                .map(this::enumerateTree)
                .orElseGet(List::of);
    }

    private List<LocalCandidateMove> enumerateTree(Expr root) {
        List<LocalCandidateMove> candidates = new ArrayList<>();
        for (PositionedExpr positioned : MoveExpressions.positionedSubexpressions(root)) {
            String text = MoveExpressions.format(positioned.expr());
            TreePosition position = new TreePosition(positioned.path(), text);
            for (CandidateMove move : delegate.enumerate(positioned.expr())) {
                candidates.add(new LocalCandidateMove(position, move));
            }
        }
        candidates.sort(LocalCandidateMove.CANONICAL_ORDER);
        return List.copyOf(candidates);
    }

    /**
     * A depth-1 candidate move together with the subtree {@link TreePosition} it
     * was enumerated for.
     */
    public record LocalCandidateMove(TreePosition position, CandidateMove move) {

        static final Comparator<LocalCandidateMove> CANONICAL_ORDER =
                Comparator.comparing(LocalCandidateMove::position, TreePosition.CANONICAL_ORDER)
                        .thenComparing(LocalCandidateMove::move, CandidateMove.CANONICAL_ORDER);
    }
}
