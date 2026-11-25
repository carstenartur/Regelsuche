package de.regelsuche.sdk.optimization.algebra;

import de.regelsuche.math.algorithms.algebra.OccupiedWorkspace;
import de.regelsuche.math.algorithms.linalg.ExactMatrixAlgebra;
import de.regelsuche.math.algorithms.linalg.ExactMatrixExpression;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OperatorPlanSdkTest {
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
