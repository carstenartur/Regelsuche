package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.JointComputationPlan;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GuardedBigIntegerCostTest {
    @Test void noPrimitiveGateMustNotChargeASecondOriginalBigIntegerComputation() {
        var kind=NumericKind.BIG_INTEGER;
        var x=new VariableExpr("x");
        var one=JavaExpressions.literal(BigInteger.ONE);
        var sum=JavaExpressions.operation(kind,NumericOperation.ADD,x,one);
        var result=JavaExpressions.operation(kind,NumericOperation.SUBTRACT,sum,one);
        var plan=new JointComputationPlan(Map.of("x",kind.type()),Map.of(),List.of(new JointComputationPlan.Output("out",kind.type(),result)));
        var assumptions=Set.of(new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_VALUE_SEMANTICS,"x","","host receiver contract"),
            new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_BIT_LENGTH_BOUND,"x","64","host magnitude contract"));
        var request=new OptimizationRequest(plan,SourceEvaluationTrace.fromPlan(plan),Set.of(kind),ComputationOptimizer.SEMANTICS_REVISION,assumptions,
            SafetyProfile.GUARDED_FALLBACK,OptimizationGoal.LOWER_ESTIMATED_RUNTIME,OptimizationBudget.DEFAULT,CheckedPolicy.NONE);
        var resultCandidate=new ComputationOptimizer().optimize(request,CancellationToken.NONE);
        var candidate=assertInstanceOf(OptimizationResult.Candidate.class,resultCandidate,resultCandidate.toString());
        assertEquals(RuntimeObligations.GuardKind.NONE,candidate.obligations().guard());
        assertEquals(0,candidate.obligations().estimatedCheckWork());
        assertEquals(0,candidate.obligations().fallbackOperationCount());
        assertEquals(BigInteger.TEN,candidate.prepared().execute(Map.of("x",BigInteger.TEN)).get("out"));
    }
}
