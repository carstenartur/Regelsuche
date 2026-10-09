package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.JointComputationPlan;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Public Java-consumer policy; no source example, class or operation family is a task selector. */
class RuntimeOptimizationPolicyTest {
    private static final Expr X=new VariableExpr("x"),Y=new VariableExpr("y"),Z=new VariableExpr("z");
    @Test void unusedInputsDoNotTurnConstantFoldingIntoRuntimeOptimization() {
        var request=request(List.of(product(literal(5),literal(7))));
        assertInstanceOf(OptimizationResult.NoImprovement.class,runtime(request));
    }
    @Test void aConstantSubexpressionInsideVariableCodeIsNotAnOptimization() {
        var request=request(List.of(product(X,product(literal(5),literal(7)))));
        assertInstanceOf(OptimizationResult.NoImprovement.class,runtime(request));
    }
    @Test void aStaticCompanionOutputIsKeptWhileRuntimeWorkIsReduced() {
        Expr constants=product(literal(5),literal(7));
        var request=request(List.of(constants,op(NumericOperation.SUBTRACT,X,X)));
        var result=runtime(request);
        var candidate=assertInstanceOf(OptimizationResult.Candidate.class,result,result.toString());
        assertEquals(constants,candidate.plan().outputExpressions().getFirst());
        assertEquals(literal(0),candidate.plan().outputExpressions().get(1));
        assertInstanceOf(VerificationResult.Verified.class,new ComputationOptimizer().reverify(request,candidate,CancellationToken.NONE));
    }
    @Test void aConstantResultOfVariableAlgebraIsStillUseful() {
        var request=request(List.of(op(NumericOperation.SUBTRACT,op(NumericOperation.ADD,X,Y),op(NumericOperation.ADD,Y,X))));
        var result=runtime(request);
        var candidate=assertInstanceOf(OptimizationResult.Candidate.class,result,result.toString());
        assertEquals(List.of(literal(0)),candidate.plan().outputExpressions());
    }
    @Test void nontrivialRuntimeAlgebraUsesTheSameSearch() {
        var source=op(NumericOperation.SUBTRACT,product(X,op(NumericOperation.ADD,Y,Z)),product(X,Y));
        var request=request(List.of(source));
        var result=runtime(request);
        var candidate=assertInstanceOf(OptimizationResult.Candidate.class,result,result.toString());
        assertInstanceOf(VerificationResult.Verified.class,new ComputationOptimizer().reverify(request,candidate,CancellationToken.NONE));
        assertTrue(candidate.cost().estimatedRuntimeImprovement());
        assertEquals(Map.of("out0",51),candidate.prepared().execute(Map.of("x",3,"y",11,"z",17)));
    }
    @Test void mathematicalConsumersCanStillAskForConstantEvaluation() {
        var result=new ComputationOptimizer().optimize(request(List.of(product(literal(5),literal(7)))),CancellationToken.NONE);
        var candidate=assertInstanceOf(OptimizationResult.Candidate.class,result,result.toString());
        assertEquals(List.of(literal(35)),candidate.plan().outputExpressions());
    }
    private static OptimizationResult runtime(OptimizationRequest request) {
        try {
            // Also pins the public binary signature used by external Java adapters.
            return (OptimizationResult) ComputationOptimizer.class.getMethod("optimizeRuntime",OptimizationRequest.class,CancellationToken.class)
                    .invoke(new ComputationOptimizer(),request,CancellationToken.NONE);
        } catch (NoSuchMethodException absent) {
            return fail("Missing public runtime-only optimization policy; Java consumers must not reimplement mathematics",absent);
        } catch (InvocationTargetException failure) {
            return fail("Runtime-only optimizer failed",failure.getCause());
        } catch (ReflectiveOperationException failure) {
            return fail(failure);
        }
    }
    private static Expr literal(int value){return JavaExpressions.literal(value);}
    private static Expr product(Expr a,Expr b){return op(NumericOperation.MULTIPLY,a,b);}
    private static Expr op(NumericOperation operation,Expr... operands){return JavaExpressions.operation(NumericKind.INT,operation,operands);}
    private static OptimizationRequest request(List<Expr> expressions){
        var outputs=new java.util.ArrayList<JointComputationPlan.Output>();
        for(int i=0;i<expressions.size();i++)outputs.add(new JointComputationPlan.Output("out"+i,NumericKind.INT.type(),expressions.get(i)));
        var plan=new JointComputationPlan(Map.of("x",NumericKind.INT.type(),"y",NumericKind.INT.type(),"z",NumericKind.INT.type()),Map.of(),outputs);
        return new OptimizationRequest(plan,SourceEvaluationTrace.fromPlan(plan),Set.of(NumericKind.INT),ComputationOptimizer.SEMANTICS_REVISION,Set.of(),
                SafetyProfile.PRESERVE_JAVA,OptimizationGoal.LOWER_ESTIMATED_RUNTIME,new OptimizationBudget(2_000_000,20_000,64,5000),CheckedPolicy.NONE);
    }
}
