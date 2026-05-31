package de.regelsuche.search.estimate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SearchSpaceEstimatorTest {

    private final SearchSpaceEstimator estimator = new SearchSpaceEstimator();

    @Test
    void stableFrontierReportsLowRiskAndNoWarning() {
        SearchSpaceEstimate estimate = estimator.estimate(List.of(1L, 1L, 1L, 1L), 6, 1_000);

        assertEquals(4, estimate.knownStateCount());
        assertEquals(1.0, estimate.estimatedBranchingFactor(), 1e-9);
        assertEquals(SearchSpaceRisk.LOW, estimate.risk());
        assertNull(estimate.warning());
        assertFalse(estimate.hasWarning());
    }

    @Test
    void doublingFrontierIsClassifiedAsExplosive() {
        SearchSpaceEstimate estimate = estimator.estimate(List.of(1L, 2L, 4L, 8L), 10, 1_000);

        assertEquals(15, estimate.knownStateCount());
        assertEquals(2.0, estimate.estimatedBranchingFactor(), 1e-9);
        assertEquals(SearchSpaceRisk.EXPLOSIVE, estimate.risk());
        assertTrue(estimate.hasWarning());
        // Projection continues doubling from the deepest frontier (8) up to depth 10.
        assertTrue(estimate.projectedStateCount() > estimate.knownStateCount());
    }

    @Test
    void slowGrowthIsModerateWithinBudget() {
        SearchSpaceEstimate estimate = estimator.estimate(List.of(10L, 11L, 12L), 4, 100_000);

        assertEquals(SearchSpaceRisk.MODERATE, estimate.risk());
        assertNull(estimate.warning());
    }

    @Test
    void shrinkingFrontierStaysLowRisk() {
        SearchSpaceEstimate estimate = estimator.estimate(List.of(100L, 50L, 25L, 12L), 8, 1_000);

        assertTrue(estimate.estimatedBranchingFactor() < 1.0);
        assertEquals(SearchSpaceRisk.LOW, estimate.risk());
        // A shrinking frontier still discovers a small decaying tail of states,
        // so the projection is only slightly above the known count.
        assertTrue(estimate.projectedStateCount() >= estimate.knownStateCount());
        assertTrue(estimate.projectedStateCount() < estimate.knownStateCount() * 2);
    }

    @Test
    void projectionUsesBudgetToRaiseRiskEvenWithModerateGrowth() {
        // Branching factor ~1.1 (MODERATE band) but projected past the budget,
        // yet below 10x the budget -> HIGH rather than EXPLOSIVE.
        SearchSpaceEstimate estimate = estimator.estimate(List.of(100L, 110L, 121L), 12, 1_000);

        assertEquals(1.1, estimate.estimatedBranchingFactor(), 1e-6);
        assertTrue(estimate.projectedStateCount() >= 1_000);
        assertTrue(estimate.projectedStateCount() < 10_000);
        assertEquals(SearchSpaceRisk.HIGH, estimate.risk());
        assertTrue(estimate.hasWarning());
    }

    @Test
    void projectionSaturatesInsteadOfOverflowing() {
        SearchSpaceEstimate estimate = estimator.estimate(List.of(1L, 10L, 100L, 1_000L), 60, 1_000);

        assertEquals(Long.MAX_VALUE, estimate.projectedStateCount());
        assertEquals(SearchSpaceRisk.EXPLOSIVE, estimate.risk());
        assertTrue(estimate.warning().contains("very large"));
    }

    @Test
    void noObservedStatesYieldsStableEstimate() {
        SearchSpaceEstimate estimate = estimator.estimate(List.of(), 5, 1_000);

        assertEquals(0, estimate.knownStateCount());
        assertEquals(1.0, estimate.estimatedBranchingFactor(), 1e-9);
        assertEquals(0, estimate.projectedStateCount());
        assertEquals(SearchSpaceRisk.LOW, estimate.risk());
    }

    @Test
    void ignoresEmptyDepthsWhenEstimatingGrowth() {
        // A depth with zero states (e.g. fully pruned) should not break the ratio.
        SearchSpaceEstimate estimate = estimator.estimate(List.of(1L, 0L, 2L, 4L), 8, 1_000);

        assertEquals(7, estimate.knownStateCount());
        assertEquals(2.0, estimate.estimatedBranchingFactor(), 1e-9);
    }

    @Test
    void rejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> estimator.estimate(null, 5, 1_000));
        assertThrows(IllegalArgumentException.class, () -> estimator.estimate(List.of(1L), -1, 1_000));
        assertThrows(IllegalArgumentException.class, () -> estimator.estimate(List.of(1L), 5, 0));
        assertThrows(IllegalArgumentException.class, () -> estimator.estimate(List.of(-1L), 5, 1_000));
    }
}
