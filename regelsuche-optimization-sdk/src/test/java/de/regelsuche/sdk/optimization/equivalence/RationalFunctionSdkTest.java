package de.regelsuche.sdk.optimization.equivalence;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.math.algorithms.equivalence.RationalFunctionPlanner;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import java.util.List;
import org.junit.jupiter.api.Test;

class RationalFunctionSdkTest {
    @Test
    void aConsumerCanSimplifyVerifyAndCancelWithItsOwnBudget() {
        var budget = new OptimizationBudget(2_000_000, 20_000, 20, 10_000);
        var result = RationalFunctionSdk.simplify("(x^2-1)/(x-1)", List.of("x != 1"), budget, () -> false);
        assertEquals(RationalFunctionPlanner.Status.VERIFIED, result.status());
        assertTrue(RationalFunctionSdk.verify(result.source(), result.certificate().orElseThrow(), budget,
            () -> false).valid());
        assertEquals("CANCELLED", RationalFunctionSdk.simplify("x/x", List.of("x != 0"), budget,
            () -> true).reason());
    }
}
