package de.regelsuche.moves.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.moves.search.BoundedSearchExplorer.ExplorationResult;
import org.junit.jupiter.api.Test;

class BoundedSearchExplorerTest {

    private final BoundedSearchExplorer explorer = new BoundedSearchExplorer();

    // ---------------------------------------------------------------------------
    // Integration tests against real successor generator
    // ---------------------------------------------------------------------------

    @Test
    void exploresPolynomialWithinDepthTwoBounds() {
        ExplorationResult result = explorer.explore("x^2 + 6*x + 5", 2, 100);

        assertTrue(result.exploredStates() >= 1, "at least root explored");
        assertTrue(result.uniqueStates() >= 2, "root + at least one successor");
        assertEquals(
                result.exploredStates(),
                result.uniqueStates() + result.duplicateStates(),
                "explored = unique + duplicate");
        assertTrue(result.maxBranchingFactor() >= 2, "at least 2 direct successors");
        assertTrue(result.averageBranchingFactor() >= 0.0);
        assertFalse(result.growthPerDepth().isEmpty());
    }

    @Test
    void exploresNestedSinExpression() {
        ExplorationResult result = explorer.explore("sin(x^2 + 6*x + 5)", 2, 100);

        assertTrue(result.exploredStates() >= 1);
        assertTrue(result.uniqueStates() >= 2);
        assertEquals(
                result.exploredStates(),
                result.uniqueStates() + result.duplicateStates());
        assertTrue(result.maxBranchingFactor() >= 2,
                "sin wrapping does not suppress successor generation");
        assertFalse(result.growthPerDepth().isEmpty());
    }

    // ---------------------------------------------------------------------------
    // Structural invariant tests
    // ---------------------------------------------------------------------------

    @Test
    void rootAlwaysCountedAsUniqueAtDepthZero() {
        ExplorationResult result = explorer.explore("x^2 + 6*x + 5", 0, 10);

        assertEquals(1, result.exploredStates());
        assertEquals(1, result.uniqueStates());
        assertEquals(0, result.duplicateStates());
        assertEquals(1, result.growthPerDepth().get(0));
    }

    @Test
    void respectsMaxStatesLimit() {
        ExplorationResult result = explorer.explore("x^2 + 6*x + 5", 4, 3);

        assertTrue(result.exploredStates() <= 3);
    }

    @Test
    void growthAtDepthZeroIsAlwaysOne() {
        ExplorationResult result = explorer.explore("x^2 + 6*x + 5", 2, 100);

        assertEquals(1, result.growthPerDepth().get(0),
                "root counts as 1 unique state at depth 0");
    }

    @Test
    void growthAtDepthOneIncludesDirectSuccessors() {
        ExplorationResult result = explorer.explore("x^2 + 6*x + 5", 2, 100);

        assertTrue(result.growthPerDepth().getOrDefault(1, 0) >= 2,
                "at least 2 direct successors (complete-square and factor)");
    }

    @Test
    void exploredEqualsUniquePlusDuplicates() {
        ExplorationResult result = explorer.explore("x^2 + 6*x + 5", 3, 200);

        assertEquals(result.exploredStates(), result.uniqueStates() + result.duplicateStates());
    }

    @Test
    void averageBranchingFactorIsAtMostMaxBranchingFactor() {
        ExplorationResult result = explorer.explore("x^2 + 6*x + 5", 2, 100);

        assertTrue(result.averageBranchingFactor() >= 0.0);
        assertTrue(result.averageBranchingFactor() <= result.maxBranchingFactor());
    }

    @Test
    void growthPerDepthIsImmutable() {
        ExplorationResult result = explorer.explore("x^2 + 6*x + 5", 1, 50);

        assertNotNull(result.growthPerDepth());
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> result.growthPerDepth().put(99, 1));
    }

    // ---------------------------------------------------------------------------
    // Edge-case / guard tests
    // ---------------------------------------------------------------------------

    @Test
    void returnsEmptyResultForBlankExpression() {
        ExplorationResult result = explorer.explore("   ", 4, 100);

        assertEquals(0, result.exploredStates());
        assertEquals(0, result.uniqueStates());
        assertEquals(0, result.duplicateStates());
        assertTrue(result.growthPerDepth().isEmpty());
    }

    @Test
    void returnsEmptyResultForNullExpression() {
        ExplorationResult result = explorer.explore(null, 4, 100);

        assertEquals(0, result.exploredStates());
        assertTrue(result.growthPerDepth().isEmpty());
    }

    @Test
    void maxStatesClampedToOneEnforcesMinimalExploration() {
        ExplorationResult result = explorer.explore("x^2 + 6*x + 5", 4, 0);

        // maxStates is clamped to 1 internally, so exactly 1 state explored
        assertEquals(1, result.exploredStates());
        assertEquals(1, result.uniqueStates());
    }
}
