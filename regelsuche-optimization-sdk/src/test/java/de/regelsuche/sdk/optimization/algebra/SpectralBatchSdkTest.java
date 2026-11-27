package de.regelsuche.sdk.optimization.algebra;

import de.regelsuche.math.algorithms.algebra.SpectralBatchRepair;
import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactMatrixAlgebra;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import de.regelsuche.scalar.ExactRational;
import java.util.List;
import org.junit.jupiter.api.Test;

import static de.regelsuche.math.algorithms.algebra.OperatorResidualPlans.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class SpectralBatchSdkTest {
    @Test
    void discoversAndExecutesSourceOnlyInvolutionUnderSdkLimits() {
        var algebra = new ExactMatrixAlgebra(new ExactMatrixAlgebra.Work(10_000));
        var source = algebra.matrix(2, 2, (i, j) -> Polynomial.constant(Rational.of(i.equals(j) ? 0 : 1)));
        var budget = new OptimizationBudget(10_000_000, 500_000, 5, 10_000);
        var found = SpectralBatchSdk.discover(source, 2, SpectralBatchRepair.Policy.defaults(), budget,
                CancellationToken.NONE);
        assertEquals(VERIFIED, found.status(), found.detail());
        var prepared = found.requireValue().operator();
        var input = List.of(List.of(ExactRational.integer(2), ExactRational.integer(3)),
                List.of(ExactRational.integer(-1), ExactRational.integer(4)));
        assertEquals(List.of(List.of(ExactRational.integer(3), ExactRational.integer(2)),
                List.of(ExactRational.integer(4), ExactRational.integer(-1))),
                SpectralBatchSdk.apply(prepared, input, budget, CancellationToken.NONE).requireValue());
        assertEquals(VERIFIED, SpectralBatchSdk.verify(prepared.problem(), prepared.certificate(), budget,
                CancellationToken.NONE).status());
        assertEquals(STOPPED, SpectralBatchSdk.discover(source, 2, SpectralBatchRepair.Policy.defaults(),
                new OptimizationBudget(10_000_000, 500_000, 1, 10_000), CancellationToken.NONE).status());
        assertEquals(STOPPED, SpectralBatchSdk.apply(prepared, input, budget, () -> true).status());
    }
}
