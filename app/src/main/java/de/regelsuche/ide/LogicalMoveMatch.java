package de.regelsuche.ide;

import de.regelsuche.moves.MoveParameter;
import de.regelsuche.moves.enumerate.TreeLocalMoveEnumerator.LocalCandidateMove;
import de.regelsuche.moves.enumerate.TreePosition;
import java.util.List;

/** One logical match, potentially composed of multiple local candidate moves. */
public record LogicalMoveMatch(
        TreePosition position,
        String enumeratorId,
        String kind,
        List<LocalCandidateMove> candidates,
        List<MoveParameter> bindings,
        boolean composite) {

    public LogicalMoveMatch {
        enumeratorId = enumeratorId == null ? "" : enumeratorId;
        kind = kind == null ? "" : kind;
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        bindings = bindings == null ? List.of() : List.copyOf(bindings);
    }
}
