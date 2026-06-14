package de.regelsuche.moves.search;

import de.regelsuche.moves.apply.LocalRewriteApplier;
import de.regelsuche.moves.apply.LocalRewriteApplier.LocalRewriteResult;
import de.regelsuche.moves.enumerate.Depth1MoveEnumerator.CandidateMove;
import de.regelsuche.moves.enumerate.TreeLocalMoveEnumerator;
import de.regelsuche.moves.enumerate.TreeLocalMoveEnumerator.LocalCandidateMove;
import de.regelsuche.moves.enumerate.TreePosition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates direct successor states from tree-local candidate moves.
 *
 * <p>Determinism: candidates are consumed in {@link TreeLocalMoveEnumerator}
 * canonical order, grouped by position and enumerator id with stable insertion
 * order. Deduplication keeps the first generated successor per resulting
 * expression and discards later duplicates.</p>
 */
public final class SearchSuccessorGenerator {

    private final TreeLocalMoveEnumerator enumerator;
    private final LocalRewriteApplier applier;

    public SearchSuccessorGenerator() {
        this(new TreeLocalMoveEnumerator(), new LocalRewriteApplier());
    }

    public SearchSuccessorGenerator(TreeLocalMoveEnumerator enumerator, LocalRewriteApplier applier) {
        this.enumerator = enumerator == null ? new TreeLocalMoveEnumerator() : enumerator;
        this.applier = applier == null ? new LocalRewriteApplier() : applier;
    }

    /** Returns unique direct successors reachable in one local rewrite step. */
    public List<SearchSuccessorState> generate(String expression) {
        if (expression == null || expression.isBlank()) {
            return List.of();
        }
        List<LocalCandidateMove> localCandidates = enumerator.enumerate(expression);
        Map<String, SearchSuccessorState> uniqueByExpression = new LinkedHashMap<>();
        for (PositionMatch match : logicalMatches(localCandidates)) {
            LocalRewriteResult rewrite = applier.apply(expression, match.position(), match.candidates());
            if (!rewrite.success() || rewrite.expressionAfter() == null || rewrite.expressionAfter().isBlank()) {
                continue;
            }
            CandidateMove first = match.candidates().getFirst();
            uniqueByExpression.putIfAbsent(
                    rewrite.expressionAfter(),
                    new SearchSuccessorState(
                            rewrite.originalExpression(),
                            rewrite.expressionAfter(),
                            match.position(),
                            first.enumeratorId(),
                            first.kind().name(),
                            match.candidates(),
                            rewrite));
        }
        return List.copyOf(uniqueByExpression.values());
    }

    private static List<PositionMatch> logicalMatches(List<LocalCandidateMove> candidates) {
        Map<TreePosition, List<LocalCandidateMove>> byPosition = new LinkedHashMap<>();
        for (LocalCandidateMove candidate : candidates) {
            byPosition.computeIfAbsent(candidate.position(), unused -> new ArrayList<>()).add(candidate);
        }

        List<PositionMatch> matches = new ArrayList<>();
        for (Map.Entry<TreePosition, List<LocalCandidateMove>> positionEntry : byPosition.entrySet()) {
            TreePosition position = positionEntry.getKey();
            Map<String, List<LocalCandidateMove>> byEnumerator = new LinkedHashMap<>();
            for (LocalCandidateMove candidate : positionEntry.getValue()) {
                byEnumerator.computeIfAbsent(candidate.move().enumeratorId(), unused -> new ArrayList<>())
                        .add(candidate);
            }
            for (Map.Entry<String, List<LocalCandidateMove>> enumeratorEntry : byEnumerator.entrySet()) {
                String enumeratorId = enumeratorEntry.getKey();
                List<LocalCandidateMove> localMoves = enumeratorEntry.getValue();
                if ("complete-square".equals(enumeratorId)) {
                    matches.add(new PositionMatch(
                            position,
                            localMoves.stream().map(LocalCandidateMove::move).toList()));
                } else {
                    for (LocalCandidateMove move : localMoves) {
                        matches.add(new PositionMatch(position, List.of(move.move())));
                    }
                }
            }
        }
        return List.copyOf(matches);
    }

    private record PositionMatch(TreePosition position, List<CandidateMove> candidates) {
    }

    public record SearchSuccessorState(
            String sourceExpression,
            String successorExpression,
            TreePosition position,
            String enumeratorId,
            String moveKind,
            List<CandidateMove> candidateMoves,
            LocalRewriteResult rewrite) {

        public SearchSuccessorState {
            sourceExpression = sourceExpression == null ? "" : sourceExpression;
            successorExpression = successorExpression == null ? "" : successorExpression;
            enumeratorId = enumeratorId == null ? "" : enumeratorId;
            moveKind = moveKind == null ? "" : moveKind;
            candidateMoves = candidateMoves == null ? List.of() : List.copyOf(candidateMoves);
        }
    }
}
