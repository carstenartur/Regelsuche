package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.JointComputationPlan;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;

class BoundedIncumbentTest {
    @Test void independentVerificationHasAReservedBudgetAfterBroadSearch() {
        Expr a=new VariableExpr("a"), e=new VariableExpr("e"), m=new VariableExpr("m");
        Expr left=op(NumericOperation.MOD_POW,a,
                op(NumericOperation.ADD,op(NumericOperation.MULTIPLY,e,number(3)),number(2)),m);
        Expr right=op(NumericOperation.MOD_POW,a,
                op(NumericOperation.ADD,op(NumericOperation.MULTIPLY,e,number(2)),number(1)),m);
        var plan=new JointComputationPlan(Map.of("a",NumericKind.BIG_INTEGER.type(),"e",NumericKind.BIG_INTEGER.type(),"m",NumericKind.BIG_INTEGER.type()),Map.of(),
                List.of(new JointComputationPlan.Output("left",NumericKind.BIG_INTEGER.type(),left),
                        new JointComputationPlan.Output("right",NumericKind.BIG_INTEGER.type(),right)));
        var assumptions=new HashSet<SemanticAssumption>();
        for(String name:List.of("a","e","m")) {
            assumptions.add(new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_VALUE_SEMANTICS,name,"","test's exact BigInteger inputs"));
            assumptions.add(new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_BIT_LENGTH_BOUND,name,"64","test range"));
        }
        assumptions.add(new SemanticAssumption(SemanticAssumption.Kind.NON_NEGATIVE,"e","","test range"));
        assumptions.add(new SemanticAssumption(SemanticAssumption.Kind.POSITIVE,"m","","test range"));
        var request=new OptimizationRequest(plan,SourceEvaluationTrace.fromPlan(plan),Set.of(NumericKind.BIG_INTEGER),
                ComputationOptimizer.SEMANTICS_REVISION,assumptions,SafetyProfile.PRESERVE_JAVA,OptimizationGoal.LOWER_ESTIMATED_RUNTIME,
                new OptimizationBudget(1_000_000,20_000,64,10_000),CheckedPolicy.NONE);
        var optimizer=new ComputationOptimizer();
        var result=optimizer.optimize(request,CancellationToken.NONE);
        var candidate=assertInstanceOf(OptimizationResult.Candidate.class,result,result.toString());
        assertTrue(candidate.work()<=request.budget().maximumWork(),candidate.toString());
        assertInstanceOf(VerificationResult.Verified.class,optimizer.reverify(request,candidate,CancellationToken.NONE));
        assertEquals(OptimizationResult.SearchCompletion.IMPROVEMENT_FOUND,candidate.searchCompletion());
        var original=ComputationOptimizer.prepare(plan);
        for(int base:new int[]{-10,0,1,17,65538})for(int exponent:new int[]{0,1,7,31}) {
            var input=Map.<String,Object>of("a",BigInteger.valueOf(base),"e",BigInteger.valueOf(exponent),"m",BigInteger.valueOf(65537));
            assertEquals(original.execute(input),candidate.prepared().execute(input));
        }
    }
    private static Expr op(NumericOperation op,Expr... operands){return JavaExpressions.operation(NumericKind.BIG_INTEGER,op,operands);}
    private static Expr number(int n){return JavaExpressions.literal(BigInteger.valueOf(n));}
}
