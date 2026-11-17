package de.regelsuche.sdk.optimization.modular;

import de.regelsuche.math.algorithms.modular.PolynomialCongruence;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

import static java.math.BigInteger.ONE;
import static java.math.BigInteger.ZERO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolynomialCongruenceSdkTest {
    private static final OptimizationBudget BUDGET = new OptimizationBudget(10_000, 64, 1, 10_000);
    private static final PolynomialCongruence.Request REQUEST =
        new PolynomialCongruence.Request(List.of(ONE, BigInteger.valueOf(2)), 12, BigInteger.valueOf(5));

    @Test
    void findsAndVerifiesEveryIndexForAnAffineCongruence() {
        var result = PolynomialCongruenceSdk.solve(REQUEST, BUDGET, CancellationToken.NONE);
        assertEquals(PolynomialCongruence.Status.COMPLETE, result.status());
        var solution = result.solution().orElseThrow();
        assertEquals(List.of(2, 8), solution.residues());
        assertTrue(PolynomialCongruenceSdk.verify(REQUEST, solution, BUDGET, CancellationToken.NONE).valid());
        var changed = new PolynomialCongruence.Request(REQUEST.coefficientsAscending(), 12, ZERO);
        assertFalse(PolynomialCongruenceSdk.verify(changed, solution, BUDGET, CancellationToken.NONE).valid());
    }

    @Test
    void forwardsWorkAndStateBudgetsWithoutReturningPartialRoots() {
        var solution = PolynomialCongruenceSdk.solve(REQUEST, BUDGET, CancellationToken.NONE)
            .solution().orElseThrow();
        for (var budget : List.of(new OptimizationBudget(1, 64, 1, 10_000),
                new OptimizationBudget(10_000, 1, 1, 10_000))) {
            var result = PolynomialCongruenceSdk.solve(REQUEST, budget, CancellationToken.NONE);
            assertEquals(PolynomialCongruence.Status.UNAVAILABLE, result.status());
            assertTrue(result.solution().isEmpty());
            assertFalse(PolynomialCongruenceSdk.verify(REQUEST, solution, budget, CancellationToken.NONE).valid());
        }
    }

    @Test
    void forwardsCancellationToSolveAndVerify() {
        var solution = PolynomialCongruenceSdk.solve(REQUEST, BUDGET, CancellationToken.NONE)
            .solution().orElseThrow();
        var cancelled = PolynomialCongruenceSdk.solve(REQUEST, BUDGET, () -> true);
        assertEquals(PolynomialCongruence.Status.UNAVAILABLE, cancelled.status());
        assertTrue(cancelled.solution().isEmpty());
        assertFalse(PolynomialCongruenceSdk.verify(REQUEST, solution, BUDGET, () -> true).valid());
    }
}
