package de.regelsuche.sdk.optimization.linalg;

import static de.regelsuche.math.algorithms.linalg.LinearProgramVerifier.Status.UNAVAILABLE;
import static de.regelsuche.math.algorithms.linalg.LinearProgramVerifier.Status.VERIFIED;
import static de.regelsuche.scalar.ExactRational.NEGATIVE_ONE;
import static de.regelsuche.scalar.ExactRational.ONE;
import static de.regelsuche.scalar.ExactRational.ZERO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.math.algorithms.linalg.LinearProgramCertificates.Infeasible;
import de.regelsuche.math.algorithms.linalg.LinearProgramCertificates.Problem;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import java.util.List;
import org.junit.jupiter.api.Test;

class LinearProgramSdkTest {
    @Test
    void verifiesAndReducesCertificatesUsingSdkLimitsAndCancellation() {
        var problem = new Problem(List.of(List.of(ONE), List.of(NEGATIVE_ONE), List.of(NEGATIVE_ONE)),
            List.of(ZERO, NEGATIVE_ONE, NEGATIVE_ONE), List.of(ZERO));
        var input = new Infeasible(problem, List.of(ONE.add(ONE), ONE, ONE));
        var budget = OptimizationBudget.DEFAULT;

        assertEquals(VERIFIED, LinearProgramSdk.verify(problem, input, budget, CancellationToken.NONE).status());
        var result = LinearProgramSdk.sparsify(problem, input, budget, CancellationToken.NONE);
        assertEquals(VERIFIED, result.status());
        var sparse = result.certificate().orElseThrow();
        assertEquals(2, sparse.y().stream().filter(value -> !value.isZero()).count());
        assertEquals(VERIFIED, LinearProgramSdk.verify(problem, sparse, budget, CancellationToken.NONE).status());
        assertEquals(UNAVAILABLE, LinearProgramSdk.verify(problem, input,
            new OptimizationBudget(1, 128, 1, 5_000), CancellationToken.NONE).status());
        assertEquals(UNAVAILABLE, LinearProgramSdk.verify(problem, input,
            new OptimizationBudget(1_000, 1, 1, 5_000), CancellationToken.NONE).status());

        var cancelled = LinearProgramSdk.sparsify(problem, input, budget, () -> true);
        assertEquals(UNAVAILABLE, cancelled.status());
        assertTrue(cancelled.certificate().isEmpty());
    }
}
