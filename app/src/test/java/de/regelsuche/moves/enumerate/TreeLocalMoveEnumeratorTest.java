package de.regelsuche.moves.enumerate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.moves.RewriteMoveKind;
import de.regelsuche.moves.enumerate.TreeLocalMoveEnumerator.LocalCandidateMove;
import java.util.List;
import org.junit.jupiter.api.Test;

class TreeLocalMoveEnumeratorTest {

    private final TreeLocalMoveEnumerator enumerator = new TreeLocalMoveEnumerator();

    @Test
    void enumeratesCandidatesAtTheRootPosition() {
        List<LocalCandidateMove> candidates = enumerator.enumerate("x^2 + 6*x + 5");
        assertTrue(
                candidates.stream()
                        .anyMatch(c -> c.position().isRoot()
                                && c.move().kind() == RewriteMoveKind.COMPLETE_SQUARE),
                candidates.toString());
    }

    @Test
    void tagsCandidatesWithTheSubtreePositionTheyApplyTo() {
        // The completing-the-square shape appears inside the function argument, so
        // its candidate must be reported at a non-root position.
        List<LocalCandidateMove> candidates = enumerator.enumerate("sin(x^2 + 6*x + 5)");
        List<LocalCandidateMove> completeSquare = candidates.stream()
                .filter(c -> c.move().kind() == RewriteMoveKind.COMPLETE_SQUARE)
                .toList();
        assertFalse(completeSquare.isEmpty(), candidates.toString());
        assertTrue(
                completeSquare.stream().anyMatch(c -> !c.position().isRoot()),
                completeSquare.toString());
    }

    @Test
    void isDeterministicAndCanonicallyOrdered() {
        List<LocalCandidateMove> first = enumerator.enumerate("x*(y + 1) + z*(y + 1)");
        List<LocalCandidateMove> second = enumerator.enumerate("x*(y + 1) + z*(y + 1)");
        assertEquals(first, second);

        List<LocalCandidateMove> sorted = first.stream()
                .sorted(LocalCandidateMove.CANONICAL_ORDER)
                .toList();
        assertEquals(sorted, first);
    }

    @Test
    void returnsEmptyForUnparseableInput() {
        assertTrue(enumerator.enumerate("(((").isEmpty());
    }

    @Test
    void treePositionPathKeyIsRootForWholeExpression() {
        TreePosition root = new TreePosition(List.of(), "x + 1");
        assertTrue(root.isRoot());
        assertEquals("root", root.pathKey());

        TreePosition leftChild = new TreePosition(List.of(0), "x");
        assertFalse(leftChild.isRoot());
        assertEquals("000", leftChild.pathKey());
    }
}
