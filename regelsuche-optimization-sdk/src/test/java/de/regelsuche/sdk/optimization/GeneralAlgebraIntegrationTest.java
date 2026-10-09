package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.JointComputationPlan;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The request is the input graph, never an example ID or a preselected mathematical task. */
class GeneralAlgebraIntegrationTest {
    private static Expr add(NumericKind k, Expr a, Expr b) { return JavaExpressions.operation(k, NumericOperation.ADD,a,b); }
    private static Expr sub(NumericKind k, Expr a, Expr b) { return JavaExpressions.operation(k, NumericOperation.SUBTRACT,a,b); }
    private static Expr mul(NumericKind k, Expr a, Expr b) { return JavaExpressions.operation(k, NumericOperation.MULTIPLY,a,b); }
    private static Expr v(String name) { return new VariableExpr(name); }

    @Test void sharedCoreRulesAreUsedWithoutAJavaSpecificFormulaImplementation() {
        var k=NumericKind.INT;
        var source=mul(k,v("x"),add(k,v("y"),v("z")));
        var request=request(k,source);
        var generation=new JavaCandidateGenerator(request,new VerificationWork(request,CancellationToken.NONE))
                .generate(request.plan(),64);
        assertTrue(generation.proposals().stream().anyMatch(p -> p.rule().contains("ast_distribute_left_add")),
                generation.toString());
        var expanded=add(k,mul(k,v("x"),v("y")),mul(k,v("x"),v("z")));
        assertTrue(generation.proposals().stream().anyMatch(p -> request.plan().withExpression(p.expression())
                .outputExpressions().equals(List.of(expanded))), generation.toString());
    }

    @Test void associatedAndPermutedRuntimeArithmeticIsActuallySearched() {
        for (var kind:List.of(NumericKind.INT,NumericKind.LONG)) {
            Expr x=v("x"),y=v("y"),z=v("z");
            qualifyZero(kind,sub(kind,add(kind,add(kind,x,y),z),add(kind,z,add(kind,y,x))));
            qualifyZero(kind,sub(kind,mul(kind,x,add(kind,y,z)),add(kind,mul(kind,y,x),mul(kind,z,x))));
        }
    }

    @Test void arbitraryInputNamesAndGeneratedPolynomialContextsDoNotSelectTheTask() {
        Random random=new Random(20261009L);
        for (int i=0;i<12;i++) {
            NumericKind kind=i%2==0?NumericKind.INT:NumericKind.LONG;
            Expr x=v("input_"+i), y=v("value_"+(31-i)), z=v("term_"+(i*7));
            Expr c=kind==NumericKind.INT?JavaExpressions.literal(random.nextInt(31)+2)
                    :JavaExpressions.literal((long)random.nextInt(31)+2);
            Expr a=add(kind,mul(kind,x,c),y), b=mul(kind,z,c);
            Expr source=sub(kind,add(kind,a,b),add(kind,b,a));
            var plan=new JointComputationPlan(Map.of(((VariableExpr)x).name(),kind.type(),
                    ((VariableExpr)y).name(),kind.type(),((VariableExpr)z).name(),kind.type()),Map.of(),
                    List.of(new JointComputationPlan.Output("result",kind.type(),source)));
            var request=request(kind,plan);
            var result=new ComputationOptimizer().optimize(request,CancellationToken.NONE);
            var candidate=assertInstanceOf(OptimizationResult.Candidate.class,result,result.toString());
            assertInstanceOf(VerificationResult.Verified.class,new ComputationOptimizer().reverify(request,candidate,CancellationToken.NONE));
            assertEquals(0,candidate.prepared().cost().operationCount(),candidate.plan().toString());
        }
    }

    @Test void divisionAndExactOperationsAreNotReinterpretedAsFieldArithmetic() {
        var k=NumericKind.INT;
        Expr divided=JavaExpressions.operation(k,NumericOperation.DIVIDE,mul(k,v("x"),JavaExpressions.literal(2)),JavaExpressions.literal(2));
        Expr source=sub(k,divided,v("x"));
        var request=request(k,source);
        var zero=request.plan().withOutputs(List.of(JavaExpressions.literal(0)));
        assertFalse(new ComputationOptimizer().verify(request,zero,CancellationToken.NONE) instanceof VerificationResult.Verified);
    }

    @Test void floatingPointAssociationNeverBecomesAnUnconditionalRingIdentity() {
        var k=NumericKind.DOUBLE;
        Expr source=sub(k,add(k,add(k,v("x"),v("y")),v("z")),add(k,v("x"),add(k,v("y"),v("z"))));
        var request=request(k,source);
        assertFalse(new ComputationOptimizer().verify(request,request.plan().withOutputs(List.of(JavaExpressions.literal(0d))),
                CancellationToken.NONE) instanceof VerificationResult.Verified);
    }

    @Test void cancellationStillStopsBeforeAnyRuleWork() {
        var request=request(NumericKind.INT,add(NumericKind.INT,v("x"),v("y")));
        assertInstanceOf(OptimizationResult.Cancelled.class,new ComputationOptimizer().optimize(request,()->true));
    }

    private static void qualifyZero(NumericKind kind,Expr source) {
        var request=request(kind,source);
        var optimizer=new ComputationOptimizer();
        var result=optimizer.optimize(request,CancellationToken.NONE);
        var candidate=assertInstanceOf(OptimizationResult.Candidate.class,result,result.toString());
        assertInstanceOf(VerificationResult.Verified.class,optimizer.reverify(request,candidate,CancellationToken.NONE));
        assertEquals(0,candidate.prepared().cost().operationCount(),candidate.plan().toString());
        long[] values={0,1,-1,Integer.MIN_VALUE,Integer.MAX_VALUE,Long.MIN_VALUE,Long.MAX_VALUE};
        var before=ComputationOptimizer.prepare(request.plan());
        for(long a:values)for(long b:values)for(long c:values) {
            Map<String,Object> input=Map.of("x",number(kind,a),"y",number(kind,b),"z",number(kind,c));
            assertEquals(before.execute(input),candidate.prepared().execute(input),input.toString());
        }
    }
    private static Object number(NumericKind kind,long value) {
        if(kind==NumericKind.INT)return Integer.valueOf((int)value);
        return Long.valueOf(value);
    }
    private static OptimizationRequest request(NumericKind kind,Expr expression) {
        return request(kind,new JointComputationPlan(Map.of("x",kind.type(),"y",kind.type(),"z",kind.type()),Map.of(),
                List.of(new JointComputationPlan.Output("result",kind.type(),expression))));
    }
    private static OptimizationRequest request(NumericKind kind,JointComputationPlan plan) {
        return new OptimizationRequest(plan,SourceEvaluationTrace.fromPlan(plan),Set.of(kind),ComputationOptimizer.SEMANTICS_REVISION,
                Set.of(),SafetyProfile.PRESERVE_JAVA,OptimizationGoal.LOWER_ESTIMATED_RUNTIME,
                new OptimizationBudget(2_000_000L,20_000,64,5000L),CheckedPolicy.NONE);
    }
}
