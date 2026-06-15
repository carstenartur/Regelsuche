package de.regelsuche.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.moves.MoveOrdinal;
import de.regelsuche.moves.MoveParameter;
import de.regelsuche.moves.MoveParameterKind;
import de.regelsuche.moves.RewriteMoveKind;
import de.regelsuche.moves.enumerate.Depth1MoveEnumerator.CandidateMove;
import de.regelsuche.moves.enumerate.TreeLocalMoveEnumerator.LocalCandidateMove;
import de.regelsuche.moves.enumerate.TreePosition;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class DefaultLogicalMoveGrouperTest {

    private final DefaultLogicalMoveGrouper grouper = new DefaultLogicalMoveGrouper();

    @Test
    void defaultGroupingKeepsSingleCandidateAsSingleLogicalMatch() {
        TreePosition position = new TreePosition(List.of(), "x");
        MoveParameter factor = new MoveParameter("factor", MoveParameterKind.EXPRESSION, "x", "test");
        LocalCandidateMove candidate = candidate(position, "factor-candidate", RewriteMoveKind.FACTOR, factor, 0);

        List<LogicalMoveMatch> grouped = grouper.group(position, List.of(candidate));

        assertEquals(1, grouped.size());
        LogicalMoveMatch match = grouped.getFirst();
        assertFalse(match.composite());
        assertEquals(1, match.candidates().size());
        assertEquals("factor-candidate", match.enumeratorId());
        assertEquals("FACTOR", match.kind());
        assertEquals(List.of("factor"), match.bindings().stream().map(MoveParameter::name).toList());
    }

    @Test
    void completeSquareCandidatesAreGroupedIntoOneCompositeLogicalMatch() {
        TreePosition position = new TreePosition(List.of(), "x ^ 2 + 6 * x + 5");
        MoveParameter shift = new MoveParameter("shift", MoveParameterKind.EXPRESSION, "3", "test");
        MoveParameter residue = new MoveParameter("residue", MoveParameterKind.EXPRESSION, "-4", "test");
        LocalCandidateMove shiftCandidate = candidate(position, "complete-square", RewriteMoveKind.COMPLETE_SQUARE, shift, 0);
        LocalCandidateMove residueCandidate = candidate(position, "complete-square", RewriteMoveKind.COMPLETE_SQUARE, residue, 1);

        List<LogicalMoveMatch> grouped = grouper.group(position, List.of(shiftCandidate, residueCandidate));

        assertEquals(1, grouped.size());
        LogicalMoveMatch match = grouped.getFirst();
        assertTrue(match.composite());
        assertEquals(2, match.candidates().size());
        Set<String> bindingNames = match.bindings().stream().map(MoveParameter::name).collect(Collectors.toSet());
        assertEquals(Set.of("shift", "residue"), bindingNames);
    }

    private static LocalCandidateMove candidate(
            TreePosition position,
            String enumeratorId,
            RewriteMoveKind kind,
            MoveParameter parameter,
            int occurrence) {
        CandidateMove move = new CandidateMove(
                enumeratorId,
                kind,
                parameter,
                MoveOrdinal.of(kind, occurrence, parameter == null ? List.of() : List.of(parameter)));
        return new LocalCandidateMove(position, move);
    }
}
