package de.regelsuche.sdk.optimization.power;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.modular.SharedPowerSearch;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

import static de.regelsuche.math.algorithms.modular.SharedPowerPlan.*;
import static org.junit.jupiter.api.Assertions.*;

class SharedPowerSdkTest {
    @Test void delegatesSearchProofAndExecutionToTheCoreWithIdenticalWork() {
        var request = new Request(BigInteger.valueOf(1018), List.of(
                new Output(BigInteger.valueOf(255), BigInteger.TWO),
                new Output(BigInteger.valueOf(-255), BigInteger.TWO)));
        var budget = new OptimizationBudget(2_000_000, 20_000, 32, 20_000);
        var weights = new Weights(1, 1, 8);
        var sdk = SharedPowerSdk.search(request, weights, 8, budget, () -> false);
        var core = SharedPowerSearch.search(request, weights, 8,
                new IterationLimits(budget.maximumWork(), budget.maximumStates(), budget.timeoutMillis()), () -> false);
        assertEquals(core, sdk);
        var certificate = sdk.certificate().orElseThrow();
        assertTrue(SharedPowerSdk.verify(request, certificate, weights, budget, () -> false).valid());
        var result = SharedPowerSdk.evaluate(request, certificate, BigInteger.valueOf(1019),
                BigInteger.TWO, budget, () -> false);
        assertEquals("VERIFIED", result.reason());
        assertEquals(2, result.values().orElseThrow().size());
        assertEquals("CANCELLED", SharedPowerSdk.search(request, weights, 8, budget, () -> true).reason());
        var singleCandidate = SharedPowerSdk.search(request, weights, 8,
                new OptimizationBudget(2_000_000, 20_000, 1, 20_000), () -> false);
        assertTrue(singleCandidate.certificate().isPresent());
        assertTrue(singleCandidate.work() < sdk.work());
    }
}
