package de.regelsuche.benchmark.peer;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
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
}
