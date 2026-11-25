package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.math.algorithms.algebra.BilinearIntegerPortfolio;
import de.regelsuche.math.algorithms.algebra.BilinearIntegerSchedule.Profile;
import de.regelsuche.math.algorithms.algebra.BilinearProgram.Tensor;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintCounter.Factor;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintCounter.Problem;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintPlanLearning.Example;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.sdk.optimization.algebra.BilinearProgramSdk;
import de.regelsuche.sdk.optimization.combinatorics.FiniteConstraintSdk;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BoundedPlanningSdkTest {
    @Test void oneCandidateSlotReservesTheFixedControlAndReturnsAVerifiableCount() {
        var factor = new Factor(List.of(0, 1), List.of(BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO));
        var problem = new Problem(2, List.of(factor), Map.of());
        var budget = new OptimizationBudget(1_000_000, 2000, 1, 5_000);
        var model = FiniteConstraintSdk.train(List.of(new Example("previous", problem)), budget, 10_000,
            CancellationToken.NONE).model();
        var decision = FiniteConstraintSdk.countWithPolicy(model, problem, 10_000, 10_000, budget, CancellationToken.NONE);
        assertEquals("MIN_DEGREE", decision.selectedProvider().orElseThrow());
        var verified = decision.value().orElseThrow();
        assertEquals(BigInteger.valueOf(3), verified.count().orElseThrow());
        assertTrue(FiniteConstraintSdk.verify(problem, verified.certificate().orElseThrow(), budget, CancellationToken.NONE).valid());
    }

    @Test void sdkReturnsAnExecutableFixedProgramAndForwardsCancellation() {
        var tensor = new Tensor(1, 1, List.of(List.of(ExactRational.integer(-2))));
        var profile = new Profile(List.of(9), List.of(9));
        var budget = new OptimizationBudget(100_000, 2000, 2, 5_000);
        var plan = BilinearProgramSdk.selectIntegerWithReuse(tensor, profile, BilinearIntegerPortfolio.Objective.LIMB_WORK,
            Optional.empty(), 50_000, budget, CancellationToken.NONE);
        assertEquals("DIRECT_COORDINATES", plan.selectedProvider().orElseThrow());
        assertEquals(List.of(BigInteger.valueOf(-12)), BilinearProgramSdk.evaluateInteger(plan.value().orElseThrow().schedule(),
            List.of(BigInteger.TWO), List.of(BigInteger.valueOf(3)), budget, CancellationToken.NONE).value().orElseThrow());
        var cancelled = BilinearProgramSdk.selectIntegerWithReuse(tensor, profile, BilinearIntegerPortfolio.Objective.LIMB_WORK,
            Optional.empty(), 50_000, budget, () -> true);
        assertEquals("CANCELLED", cancelled.reason());
    }
}
