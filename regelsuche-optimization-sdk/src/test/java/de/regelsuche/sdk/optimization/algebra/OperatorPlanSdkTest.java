package de.regelsuche.sdk.optimization.algebra;

import de.regelsuche.math.algorithms.algebra.OccupiedWorkspace;
import de.regelsuche.math.algorithms.algebra.OperatorResidualSearch;
import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactMatrixAlgebra;
import de.regelsuche.math.algorithms.linalg.ExactMatrixExpression;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import de.regelsuche.scalar.ExactRational;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OperatorPlanSdkTest {
    @Test void discoversPreparesAndReplaysAnOperatorWithSdkBudgets() {
        var algebra = new ExactMatrixAlgebra(new ExactMatrixAlgebra.Work(100_000));
        var source = algebra.matrix(4, 4,
            (i,j) -> Polynomial.constant(Rational.of((i.equals(j) ? 1 : 0) + (i + 1L) * (j + 2L))));
        var budget = new OptimizationBudget(1_000_000, 100_000, 4, 5_000);
        var found = OperatorPlanSdk.discover(source, OperatorResidualSearch.Policy.defaults(), budget, CancellationToken.NONE);
        var prepared = found.requireValue().operator();
        assertEquals(OperatorResidualSearch.Predictor.IDENTITY, prepared.predictor());
        assertEquals(List.of(ExactRational.integer(15), ExactRational.integer(29), ExactRational.integer(43), ExactRational.integer(57)),
            OperatorPlanSdk.apply(prepared, List.of(ExactRational.ONE, ExactRational.ONE, ExactRational.ONE, ExactRational.ONE),
                budget, CancellationToken.NONE).requireValue());
        var limited = new OptimizationBudget(1_000_000, 100_000, 1, 5_000);
        assertEquals("CANDIDATE_LIMIT", OperatorPlanSdk.discover(source,
            OperatorResidualSearch.Policy.defaults(), limited, CancellationToken.NONE).detail());
    }

    @Test void usesSharedWorkAndSdkCandidateLimits() {
        var m = new ExactMatrixAlgebra(new ExactMatrixAlgebra.Work(500)).identity(1);
        var budget = new OptimizationBudget(100_000, 10_000, 1, 5_000);
        var token = CancellationToken.NONE;
        var plan = OperatorPlanSdk.spectral(m, new ExactMatrixExpression.Identity(1), m, m, budget, token).requireValue();
        assertEquals(0, OperatorPlanSdk.verify(m, plan, budget, token).requireValue().total());
        assertEquals("CANDIDATE_LIMIT", OperatorPlanSdk.select(m, List.of(plan,plan), budget, token).detail());
        var schedule = OccupiedWorkspace.composition(m,m);
        assertEquals("CANCELLED", OperatorPlanSdk.verifyWorkspace(m,m,schedule,budget,() -> true).detail());
        var state = new OccupiedWorkspace.State(m,m,m);
        var output = OperatorPlanSdk.applyWorkspace(m,m,schedule,state,budget,token).requireValue();
        assertEquals(m, output.z());
    }
}
