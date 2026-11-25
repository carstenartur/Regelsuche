package de.regelsuche.sdk.optimization.combinatorics;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintCnf;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintCounter;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintPlanLearning;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintPlanner.Strategy;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FiniteConstraintSdkTest {
    @Test void countsCnfAndReplaysTheOriginalSource() {
        var budget = new OptimizationBudget(100_000, 1_000, 1, 5_000);
        var source = new FiniteConstraintCnf.Source(4, List.of(List.of(1, 2, 3, 4)), Map.of(0, false));
        var count = FiniteConstraintSdk.countCnf(source, budget, CancellationToken.NONE);
        assertEquals(BigInteger.valueOf(7), count.count().orElseThrow());
        var certificate = count.certificate().orElseThrow();
        assertTrue(FiniteConstraintSdk.verifyCnf(source, certificate, budget, CancellationToken.NONE).valid());
        var indexed = FiniteConstraintSdk.countIndexed(certificate.elimination().source(), Strategy.MIN_DEGREE,
            budget, CancellationToken.NONE);
        assertEquals(count.count(), indexed.count());
    }

    @Test void sdkCountsVerifiesAndPropagatesCancellation() {
        var budget = new OptimizationBudget(100_000, 1_000, 1, 5_000);
        var problem = new FiniteConstraintCounter.Problem(3,
            List.of(new FiniteConstraintCounter.Factor(List.of(0, 1),
                List.of(BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO))), Map.of(2, true));
        var result = FiniteConstraintSdk.count(problem, budget, CancellationToken.NONE);
        assertEquals(BigInteger.valueOf(3), result.count().orElseThrow());
        assertTrue(FiniteConstraintSdk.verify(problem, result.certificate().orElseThrow(), budget, CancellationToken.NONE).valid());
        for (Strategy strategy : Strategy.values()) {
            var planned = FiniteConstraintSdk.count(problem, strategy, budget, CancellationToken.NONE);
            assertEquals(result.count(), planned.count());
            assertTrue(FiniteConstraintSdk.verify(problem, planned.certificate().orElseThrow(), budget,
                CancellationToken.NONE).valid());
        }
        var cancelled = FiniteConstraintSdk.count(problem, Strategy.MIN_FILL, budget, () -> true);
        assertEquals("CANCELLED", cancelled.reason());
        assertTrue(cancelled.count().isEmpty());
    }

    @Test void sdkTrainsAndAppliesAnOrderPolicyWithSourceBoundReplay() {
        var budget = new OptimizationBudget(1_000_000, 1_000, 1, 5_000);
        var factor = new FiniteConstraintCounter.Factor(List.of(0, 1),
            List.of(BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO));
        var source = new FiniteConstraintCounter.Problem(2, List.of(factor), Map.of());
        var examples = List.of(new FiniteConstraintPlanLearning.Example("training", source));
        var model = FiniteConstraintSdk.train(examples, budget, 10_000, CancellationToken.NONE).model().orElseThrow();
        var query = new FiniteConstraintCounter.Problem(2, List.of(factor), Map.of(1, true));
        var evaluation = FiniteConstraintSdk.count(model, query, budget, CancellationToken.NONE);
        assertEquals(BigInteger.ONE, evaluation.result().count().orElseThrow());
        assertEquals("training", evaluation.decision().orElseThrow().trainingSource());
        var certificate = evaluation.result().certificate().orElseThrow();
        assertTrue(FiniteConstraintSdk.verify(query, certificate, budget, CancellationToken.NONE).valid());
        assertFalse(FiniteConstraintSdk.verify(source, certificate, budget, CancellationToken.NONE).valid());
        assertTrue(FiniteConstraintSdk.train(examples, budget, 10_000, () -> true).model().isEmpty());
        assertEquals("CANCELLED", FiniteConstraintSdk.count(model, query, budget, () -> true).result().reason());
    }
}
