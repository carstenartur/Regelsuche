package de.regelsuche.benchmark.peer;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintPlanLearning;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import org.junit.jupiter.api.Test;

class ConstraintLifecycleComparisonTest {
    @Test
    void independentGraphOracleMatchesClosedFormsAndRetainsIsolatedVertices() {
        assertEquals(BigInteger.valueOf(13), ConstraintLifecycleComparison.enumerate(ConstraintLifecycleComparison.graph("complete", 12, 0)));
        assertEquals(BigInteger.valueOf(17), ConstraintLifecycleComparison.enumerate(ConstraintLifecycleComparison.graph("star", 5, 0)));
        assertEquals(BigInteger.valueOf(11), ConstraintLifecycleComparison.enumerate(ConstraintLifecycleComparison.graph("complete_bipartite", 2, 3)));
        assertEquals(BigInteger.valueOf(64), ConstraintLifecycleComparison.enumerate(ConstraintLifecycleComparison.graph("disconnected_triangles", 3, 0)));
        assertEquals(BigInteger.TWO, ConstraintLifecycleComparison.enumerate(ConstraintLifecycleComparison.graph("path", 1, 0)));
    }

    @Test
    void generationBoundsKeepIndependentEnumerationFinite() {
        assertThrows(IllegalArgumentException.class, () -> ConstraintLifecycleComparison.graph("complete", 100, 0));
        assertThrows(IllegalArgumentException.class, () -> ConstraintLifecycleComparison.graph("unregistered", 4, 0));
    }

    @Test
    void frozenFingerprintBindsTheActualModelAndItsAcquisition() throws Exception {
        var limits = new IterationLimits(100_000, 20_000, 20_000);
        var first = FiniteConstraintPlanLearning.train(List.of(new FiniteConstraintPlanLearning.Example(
            "first", ConstraintLifecycleComparison.graph("path", 3, 0).problem())), limits, 10_000, () -> false);
        var second = FiniteConstraintPlanLearning.train(List.of(new FiniteConstraintPlanLearning.Example(
            "second", ConstraintLifecycleComparison.graph("complete", 4, 0).problem())), limits, 10_000, () -> false);
        String before = ConstraintLifecycleComparison.modelFingerprint(first.model());
        assertNotEquals(before, ConstraintLifecycleComparison.modelFingerprint(second.model()));
        assertNotEquals(before, ConstraintLifecycleComparison.modelFingerprint(Optional.empty()));
        FiniteConstraintPlanLearning.count(first.model().orElseThrow(),
            ConstraintLifecycleComparison.graph("star", 5, 0).problem(), limits, () -> false);
        assertEquals(before, ConstraintLifecycleComparison.modelFingerprint(first.model()));
    }
}
