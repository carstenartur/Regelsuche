package de.regelsuche.math.algorithms.combinatorics;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintCounter.Factor;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintCounter.Problem;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintPlanLearning.Example;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintPlanner.Strategy;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FiniteConstraintFixedPolicyTest {
    @Test
    void theCallerCanKeepItsStrongFixedControlWithAndWithoutALearnedModel() {
        var problem = new Problem(3, List.of(
            new Factor(List.of(0, 1), List.of(BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)),
            new Factor(List.of(1, 2), List.of(BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO))), Map.of());
        var limits = new IterationLimits(2_000_000, 20_000, 10_000);
        var model = FiniteConstraintPlanLearning.train(List.of(new Example("training-source", problem)),
            limits, 100_000, () -> false).model().orElseThrow();
        for (var hint : List.of(Optional.<FiniteConstraintPlanLearning.Model>empty(), Optional.of(model))) {
            var result = FiniteConstraintPlanLearning.countWithPolicy(hint, problem, Strategy.MIN_FILL,
                1, 100_000, limits, () -> false);
            assertEquals("MIN_FILL", result.selectedProvider().orElseThrow());
            assertEquals(BigInteger.valueOf(5), result.value().orElseThrow().count().orElseThrow());
            assertTrue(result.work() <= limits.maximumWork());
        }
    }
}
