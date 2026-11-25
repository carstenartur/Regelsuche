package de.regelsuche.sdk.optimization.linalg;

import static de.regelsuche.scalar.ExactRational.NEGATIVE_ONE;
import static de.regelsuche.scalar.ExactRational.ONE;
import static de.regelsuche.scalar.ExactRational.ZERO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.math.algorithms.linalg.AffineImplication;
import de.regelsuche.math.algorithms.linalg.LinearProgramCertificates.Optimal;
import de.regelsuche.math.algorithms.linalg.LinearProgramCertificates.Problem;
import de.regelsuche.math.algorithms.linalg.LinearProgramVerifier;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import java.util.List;
import org.junit.jupiter.api.Test;

class LinearProgramSearchSdkTest {
    @Test
    void exposesSearchedCertificatesAndCheckedAffineConclusions() {
        var problem = new Problem(List.of(List.of(ONE), List.of(NEGATIVE_ONE)),
            List.of(ONE, ZERO), List.of(ONE));
        var result = LinearProgramSdk.search(problem, OptimizationBudget.DEFAULT, CancellationToken.NONE);
        assertEquals(LinearProgramVerifier.Status.VERIFIED, result.status());
        assertEquals(List.of(ONE), assertInstanceOf(Optimal.class, result.certificate().orElseThrow()).x());
        var request = new AffineImplication.Request(problem, ONE);
        var implied = LinearProgramSdk.imply(request, OptimizationBudget.DEFAULT, CancellationToken.NONE);
        assertEquals(AffineImplication.Status.PROVED, implied.status());
        assertTrue(LinearProgramSdk.verifyImplication(request, implied.evidence().orElseThrow(),
            OptimizationBudget.DEFAULT, CancellationToken.NONE).valid());
    }

    @Test
    void mapsSdkResourceLimitsWithoutResettingSearchBudgets() {
        var problem = new Problem(List.of(List.of(ONE)), List.of(ONE), List.of(ONE));
        var request = new AffineImplication.Request(problem, ONE);
        var exhausted = LinearProgramSdk.search(problem,
            new OptimizationBudget(1, 128, 1, 5_000), CancellationToken.NONE);
        assertEquals(LinearProgramVerifier.Status.UNAVAILABLE, exhausted.status());
        assertTrue(exhausted.certificate().isEmpty());
        assertEquals("TERM_LIMIT", LinearProgramSdk.imply(request,
            new OptimizationBudget(100_000, 1, 1, 5_000), CancellationToken.NONE).reason());
        var cancelled = LinearProgramSdk.imply(request, OptimizationBudget.DEFAULT, () -> true);
        assertEquals(AffineImplication.Status.UNAVAILABLE, cancelled.status());
        assertEquals("CANCELLED", cancelled.reason());
    }
}
