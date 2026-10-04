package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.JointComputationPlan;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RecoverySafetyRegressionTest {
    @Test
    void publicCandidateCannotReplaceThePreparedProgramAuthorizedByItsEvidence() {
        var source = new JointComputationPlan(Map.of("x", NumericKind.INT.type()), Map.of(),
            List.of(new JointComputationPlan.Output("out", NumericKind.INT.type(),
                JavaExpressions.operation(NumericKind.INT, NumericOperation.ADD,
                    new VariableExpr("x"), JavaExpressions.literal(0)))));
        var request = new OptimizationRequest(source, SourceEvaluationTrace.fromPlan(source),
            Set.of(NumericKind.INT), ComputationOptimizer.SEMANTICS_REVISION, Set.of(),
            SafetyProfile.PRESERVE_JAVA, OptimizationGoal.LOWER_ESTIMATED_RUNTIME,
            OptimizationBudget.DEFAULT, CheckedPolicy.NONE);
        var optimizer = new ComputationOptimizer();
        var good = assertInstanceOf(OptimizationResult.Candidate.class,
            optimizer.optimize(request, CancellationToken.NONE));
        var wrong = source.withOutputs(List.of(JavaExpressions.literal(999)));
        assertThrows(IllegalArgumentException.class, () -> new OptimizationResult.Candidate(
            good.plan(), ComputationOptimizer.prepare(wrong), good.evidence(), good.obligations(),
            good.cost(), good.searchCompletion(), good.work()));
        assertEquals(1, good.prepared().execute(Map.of("x", 1)).get("out"));
    }
}
