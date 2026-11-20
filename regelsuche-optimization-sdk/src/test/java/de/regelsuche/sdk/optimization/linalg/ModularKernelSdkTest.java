package de.regelsuche.sdk.optimization.linalg;

import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactMatrix;
import de.regelsuche.math.algorithms.linalg.ModularKernel;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import java.util.List;
import org.junit.jupiter.api.Test;
import static de.regelsuche.math.algorithms.linalg.ModularKernel.Strength.COMPLETE_KERNEL;
import static org.junit.jupiter.api.Assertions.*;

class ModularKernelSdkTest {
    @Test void forwardsSourceClaimBudgetAndCancellation() {
        var a = new ExactMatrix(List.of(List.of(Rational.ONE, Rational.ONE)));
        var budget = new OptimizationBudget(1000, 100, 1, 10000);
        var cert = ModularKernelSdk.search(a, COMPLETE_KERNEL, budget, CancellationToken.NONE).certificate().orElseThrow();
        assertTrue(ModularKernelSdk.verify(a, COMPLETE_KERNEL, cert, budget, CancellationToken.NONE));
        assertFalse(ModularKernelSdk.verify(a, COMPLETE_KERNEL, cert, budget, () -> true));
        assertEquals(ModularKernel.Status.LIMIT, ModularKernelSdk.search(a, COMPLETE_KERNEL,
            new OptimizationBudget(1, 100, 1, 10000), CancellationToken.NONE).status());
    }
}
