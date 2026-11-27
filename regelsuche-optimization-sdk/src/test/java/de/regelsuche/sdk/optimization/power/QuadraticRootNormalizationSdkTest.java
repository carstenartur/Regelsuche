package de.regelsuche.sdk.optimization.power;

import static de.regelsuche.math.algorithms.modular.QuadraticRootProgram.Step.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.modular.*;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuadraticRootNormalizationSdkTest {
    @Test void facadeReplaysARealCertificateWithTheSameWorkAndCancellationAsCore() {
        var p = BigInteger.valueOf(7);
        var original = new QuadraticRootProgram(List.of(constant(BigInteger.valueOf(4)), add(0, 2), multiply(4, 3),
                power(5, BigInteger.TWO), square(6)), 7, 5,
                new QuadraticRootProgram.Arm(List.of(multiply(1, 3), inverse(6), multiply(8, 9)), 6, 10, false),
                new QuadraticRootProgram.Arm(List.of(subtract(0, 2), multiply(8, 3), power(9, BigInteger.TWO),
                        multiply(1, 3), inverse(10), multiply(11, 12)), 10, 13, false));
        var c = new QuadraticRootContract("fixture", p, new PrimeEvidence.SmallPrime(p), original,
                QuadraticRootContract.Observation.UP_TO_JOINT_SIGN);
        var budget = new OptimizationBudget(2_000_000, 8192, 64, 30_000);
        var root = QuadraticRootSdk.search(c, new SharedPowerPlan.Weights(1, 1, 1000), budget, () -> false).certificate().orElseThrow();
        var normalizer = new QuadraticRootNormalization.Contract("observer", p, QuadraticRootNormalization.ParitySelector.imaginaryFirst());
        var cert = new QuadraticRootNormalization.Certificate(root, normalizer);
        var limits = new IterationLimits(budget.maximumWork(), budget.maximumStates(), budget.timeoutMillis());
        var replay = QuadraticRootNormalization.verifyNormalized(c, normalizer, cert, limits, () -> false);
        assertTrue(replay.valid(), replay.reason());
        assertEquals(replay, QuadraticRootSdk.verifyNormalized(c, normalizer, cert, budget, () -> false));
        assertEquals("CANCELLED", QuadraticRootSdk.verifyNormalized(c, normalizer, cert, budget, () -> true).reason());
        var shortBudget = new OptimizationBudget(replay.work() - 1, budget.maximumStates(), 1, budget.timeoutMillis());
        assertEquals("WORK_LIMIT", QuadraticRootSdk.verifyNormalized(c, normalizer, cert, shortBudget, () -> false).reason());
    }
}
