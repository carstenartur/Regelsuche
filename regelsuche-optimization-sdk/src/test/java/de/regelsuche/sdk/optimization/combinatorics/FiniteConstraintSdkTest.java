package de.regelsuche.sdk.optimization.combinatorics;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintCounter;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FiniteConstraintSdkTest {
    @Test void sdkCountsVerifiesAndPropagatesCancellation() {
        var budget = new OptimizationBudget(100_000, 1_000, 1, 5_000);
        var problem = new FiniteConstraintCounter.Problem(3,
            List.of(new FiniteConstraintCounter.Factor(List.of(0, 1),
                List.of(BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO))), Map.of(2, true));
        var result = FiniteConstraintSdk.count(problem, budget, CancellationToken.NONE);
        assertEquals(BigInteger.valueOf(3), result.count().orElseThrow());
        assertTrue(FiniteConstraintSdk.verify(problem, result.certificate().orElseThrow(), budget, CancellationToken.NONE).valid());
        var cancelled = FiniteConstraintSdk.count(problem, budget, () -> true);
        assertEquals("CANCELLED", cancelled.reason());
        assertTrue(cancelled.count().isEmpty());
    }
}
