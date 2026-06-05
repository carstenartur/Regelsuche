package de.regelsuche.moves.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.moves.RewriteMoveKind;
import org.junit.jupiter.api.Test;

class CountableMoveSearchEngineTest {
    private final CountableMoveSearchEngine engine = new CountableMoveSearchEngine();

    @Test
    void findsCompleteSquarePathWithinBoundedSearch() {
        CountableMoveSearchEngine.CountableMoveSearchResult result =
            engine.search("x^2 + 6*x + 5", "(x + 3)^2 - 4", 4, 120);

        assertTrue(result.success());
        assertTrue(result.pathLength() >= 1);
        assertEquals(result.pathLength(), result.appliedMoves().size());
        assertEquals(result.pathLength(), result.appliedRuleIds().size());
        assertTrue(result.appliedMoves().stream().allMatch(move -> move.kind() != RewriteMoveKind.UNKNOWN));
    }

    @Test
    void returnsDeterministicResultAcrossRuns() {
        CountableMoveSearchEngine.CountableMoveSearchResult first =
            engine.search("x*(y+1)+z*(y+1)", "(y+1)*(x+z)", 4, 120);
        CountableMoveSearchEngine.CountableMoveSearchResult second =
            engine.search("x*(y+1)+z*(y+1)", "(y+1)*(x+z)", 4, 120);

        assertEquals(first.success(), second.success());
        assertEquals(first.pathExpressions(), second.pathExpressions());
        assertEquals(first.appliedRuleIds(), second.appliedRuleIds());
        assertEquals(first.appliedMoves().stream().map(move -> move.moveId()).toList(),
            second.appliedMoves().stream().map(move -> move.moveId()).toList());
        assertEquals(first.exploredStateCount(), second.exploredStateCount());
        assertEquals(first.uniqueCanonicalStateCount(), second.uniqueCanonicalStateCount());
    }

    @Test
    void enforcesStateBudgetWithFailureReason() {
        CountableMoveSearchEngine.CountableMoveSearchResult result =
            engine.search("x - 1 = 0", "x = 1", 4, 1);

        assertFalse(result.success());
        assertTrue(result.failureReason() == CountableMoveSearchEngine.FailureReason.MAX_STATES_REACHED
            || result.failureReason() == CountableMoveSearchEngine.FailureReason.TARGET_NOT_REACHED);
        assertTrue(result.exploredStateCount() <= 1);
    }
}
