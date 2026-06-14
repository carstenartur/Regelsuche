package de.regelsuche.moves.enumerate;

import de.regelsuche.ast.Expr;
import de.regelsuche.moves.MoveOrdinal;
import de.regelsuche.moves.MoveParameter;
import de.regelsuche.moves.RewriteMoveKind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Aggregates the finite parameter enumerators into a single, deterministic list
 * of depth-1 candidate moves for a given expression.
 *
 * <p>This drives the "first depth-1 candidate moves" report section: it does not
 * yet steer the search, but every candidate is reportable and reproducible.</p>
 */
public final class Depth1MoveEnumerator {

    private final List<ParameterEnumerator> enumerators;

    public Depth1MoveEnumerator() {
        this(List.of(
                new SubtermParameterEnumerator(),
                new RepeatedSubexpressionEnumerator(),
                new SmallConstantEnumerator(),
                new CancellationCandidateEnumerator(),
                new CompleteSquareParameterEnumerator()));
    }

    public Depth1MoveEnumerator(List<ParameterEnumerator> enumerators) {
        this.enumerators = List.copyOf(enumerators);
    }

    /**
     * @param expression the current expression
     * @return a deterministically ordered list of candidate moves
     */
    public List<CandidateMove> enumerate(String expression) {
        List<CandidateMove> candidates = new ArrayList<>();
        for (ParameterEnumerator enumerator : enumerators) {
            RewriteMoveKind kind = kindFor(enumerator.id());
            int occurrence = 0;
            for (MoveParameter parameter : enumerator.enumerate(expression)) {
                candidates.add(new CandidateMove(
                        enumerator.id(),
                        kind,
                        parameter,
                        MoveOrdinal.of(kind, occurrence, List.of(parameter))));
                occurrence++;
            }
        }
        candidates.sort(CandidateMove.CANONICAL_ORDER);
        return List.copyOf(candidates);
    }

    /**
     * Enumerates candidate moves directly from an already-parsed {@link Expr} node,
     * avoiding a re-parse of each subtree's text form.
     *
     * @param root the already-parsed expression node
     * @return a deterministically ordered list of candidate moves
     */
    public List<CandidateMove> enumerate(Expr root) {
        List<CandidateMove> candidates = new ArrayList<>();
        for (ParameterEnumerator enumerator : enumerators) {
            RewriteMoveKind kind = kindFor(enumerator.id());
            int occurrence = 0;
            for (MoveParameter parameter : enumerator.enumerate(root)) {
                candidates.add(new CandidateMove(
                        enumerator.id(),
                        kind,
                        parameter,
                        MoveOrdinal.of(kind, occurrence, List.of(parameter))));
                occurrence++;
            }
        }
        candidates.sort(CandidateMove.CANONICAL_ORDER);
        return List.copyOf(candidates);
    }

    private RewriteMoveKind kindFor(String enumeratorId) {
        return switch (enumeratorId) {
            case "complete-square" -> RewriteMoveKind.COMPLETE_SQUARE;
            case "cancellation-candidate" -> RewriteMoveKind.ADD_SAME_TERM_BOTH_SIDES;
            case "repeated-subexpression" -> RewriteMoveKind.COMMON_SUBEXPRESSION;
            default -> RewriteMoveKind.UNKNOWN;
        };
    }

    /**
     * A single depth-1 candidate move: the enumerator that produced it, the
     * suggested move kind, the candidate parameter and its reproducible ordinal.
     */
    public record CandidateMove(
            String enumeratorId,
            RewriteMoveKind kind,
            MoveParameter parameter,
            MoveOrdinal ordinal) {

        static final Comparator<CandidateMove> CANONICAL_ORDER =
                Comparator.comparing(CandidateMove::enumeratorId)
                        .thenComparing(CandidateMove::ordinal, MoveOrdinal.CANONICAL_ORDER)
                        .thenComparing(candidate -> candidate.parameter().canonicalValue());
    }
}
