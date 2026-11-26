package de.regelsuche.sdk.optimization.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import de.regelsuche.math.algorithms.modular.*;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuadraticRootSdkTest {
    @Test void sdkPropagatesCancellationBeforeInterpretingTheSource() {
        var p = BigInteger.valueOf(7);
        var arm = new QuadraticRootProgram.Arm(List.of(), 0, 1, false);
        var source = new QuadraticRootProgram(List.of(), 0, 1, arm, arm);
        var contract = new QuadraticRootContract("unit", p, new PrimeEvidence.SmallPrime(p), source,
                QuadraticRootContract.Observation.EXACT_PAIR);
        var result = QuadraticRootSdk.search(contract, new SharedPowerPlan.Weights(1, 1, 10),
                OptimizationBudget.DEFAULT, () -> true);
        assertEquals("CANCELLED", result.reason());
    }
}
