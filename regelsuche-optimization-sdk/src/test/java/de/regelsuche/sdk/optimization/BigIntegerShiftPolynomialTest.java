package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;
import static de.regelsuche.sdk.optimization.ValueArithmeticTest.*;
import de.regelsuche.ast.*;
import de.regelsuche.search.program.JointComputationPlan;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;

class BigIntegerShiftPolynomialTest {
    private static final Expr A=new VariableExpr("valueInput0");
    private static final Expr P=JavaExpressions.literal(new BigInteger("4002409555221667393417789825735904156556882819939007885332058136124031650490837864442687629129015664037894272559787"));

    @Test void actualModularSquareLawProvesShiftedProductAgainstSourceMultiplication() {
        Expr a=mod(A,P),b=mod(new VariableExpr("valueInput1"),P);
        Expr real=mod(mul(op(NumericOperation.ADD,a,b),op(NumericOperation.SUBTRACT,a,b)),P);
        Expr imaginary=mod(shift(mul(a,b),1),P);
        var request=squareRequest(List.of(real,imaginary));
        var target=request.plan().withOutputs(List.of(mod(op(NumericOperation.SUBTRACT,mul(a,a),mul(b,b)),P),
                mod(op(NumericOperation.ADD,mul(a,b),mul(b,a)),P)));
        var optimizer=new ComputationOptimizer();
        var result=optimizer.verify(request,target,CancellationToken.NONE);
        var verified=assertInstanceOf(VerificationResult.Verified.class,result,result.toString());
        assertEquals(request.sourceTrace(),verified.obligations().originalTrace());
        assertFalse(optimizer.verify(request,target.withOutputs(List.of(real,mod(shift(mul(a,b),2),P))),CancellationToken.NONE)
                instanceof VerificationResult.Verified);
        assertFalse(optimizer.verify(request,target.withOutputs(List.of(real,mod(imaginary,JavaExpressions.literal(BigInteger.valueOf(101))))),CancellationToken.NONE)
                instanceof VerificationResult.Verified);
    }

    @Test void nonnegativeConstantDistancesRespectTheExistingCoefficientBoundary() {
        for(int distance:new int[]{0,1,PolynomialProof.MAX_COEFFICIENT_BITS-1}) {
            Expr scaled=mul(A,JavaExpressions.literal(BigInteger.ONE.shiftLeft(distance)));
            assertTrue(proof(OptimizationBudget.DEFAULT,CancellationToken.NONE).equivalent(shift(A,distance),scaled));
        }
        assertFalse(proof(OptimizationBudget.DEFAULT,CancellationToken.NONE).equivalent(shift(A,2),mul(A,JavaExpressions.literal(BigInteger.TWO))));
        assertThrows(PolynomialProof.OutsideFragment.class,() -> proof(OptimizationBudget.DEFAULT,CancellationToken.NONE)
                .equivalent(shift(mul(A,JavaExpressions.literal(BigInteger.TWO)),4095),A));
        for(int distance:new int[]{-1,Integer.MIN_VALUE,PolynomialProof.MAX_COEFFICIENT_BITS,Integer.MAX_VALUE})
            assertFalse(proof(OptimizationBudget.DEFAULT,CancellationToken.NONE).equivalent(shift(A,distance),A));
        assertFalse(proof(OptimizationBudget.DEFAULT,CancellationToken.NONE).equivalent(op(NumericOperation.SHIFT_LEFT,A,new VariableExpr("distance")),A));
        assertFalse(proof(OptimizationBudget.DEFAULT,CancellationToken.NONE).equivalent(op(NumericOperation.SHIFT_LEFT,A,JavaExpressions.literal(1L)),A));
        var primitive=new PolynomialProof(NumericKind.INT,false,false,new VerificationWork(squareRequest(List.of(A)),CancellationToken.NONE));
        assertFalse(primitive.equivalent(JavaExpressions.operation(NumericKind.INT,NumericOperation.SHIFT_LEFT,A,JavaExpressions.literal(32)),A));
    }

    @Test void largeShiftAllocationIsPrechargedAndCancellationRemainsCooperative() {
        var tiny=new OptimizationBudget(256,128,32,5000);
        assertThrows(VerificationWork.Stopped.class,() -> proof(tiny,CancellationToken.NONE).equivalent(shift(A,4095),A));
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var stopped=assertThrows(VerificationWork.Stopped.class,() -> proof(OptimizationBudget.DEFAULT,() -> calls.incrementAndGet()>1).equivalent(shift(A,4095),A));
        assertTrue(stopped.cancelled);
    }

    private static Expr shift(Expr value,int distance) { return op(NumericOperation.SHIFT_LEFT,value,JavaExpressions.literal(distance)); }
    private static PolynomialProof proof(OptimizationBudget budget,CancellationToken token) {
        var original=squareRequest(List.of(A));
        var request=new OptimizationRequest(original.plan(),original.sourceTrace(),original.selectedKinds(),original.semanticsRevision(),original.assumptions(),
                original.safetyProfile(),original.goal(),budget,original.checkedPolicy());
        return new PolynomialProof(NumericKind.BIG_INTEGER,true,false,new VerificationWork(request,token));
    }
    private static OptimizationRequest squareRequest(List<Expr> values) {
        var inputs=new LinkedHashMap<String,de.regelsuche.search.program.ComputationBackend.Type>();
        var assumptions=new HashSet<SemanticAssumption>();
        for(int i=0;i<6;i++) {
            String name="valueInput"+i; inputs.put(name,NumericKind.BIG_INTEGER.type());
            assumptions.add(new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_VALUE_SEMANTICS,name,"","source value contract"));
            assumptions.add(new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_BIT_LENGTH_BOUND,name,"4096","source bound"));
        }
        var outputs=new ArrayList<JointComputationPlan.Output>();
        for(int i=0;i<values.size();i++) outputs.add(new JointComputationPlan.Output("component"+i,NumericKind.BIG_INTEGER.type(),values.get(i)));
        var plan=new JointComputationPlan(inputs,Map.of(),outputs);
        var occurrences=new ArrayList<SourceEvaluationTrace.Occurrence>();
        var seen=Collections.newSetFromMap(new IdentityHashMap<Expr,Boolean>());
        values.forEach(value -> trace(value,seen,occurrences));
        return new OptimizationRequest(plan,new SourceEvaluationTrace(occurrences),Set.of(NumericKind.BIG_INTEGER),ComputationOptimizer.SEMANTICS_REVISION,
                assumptions,SafetyProfile.PRESERVE_JAVA,OptimizationGoal.LOWER_ESTIMATED_RUNTIME,new OptimizationBudget(100000,20000,64,5000),CheckedPolicy.NONE);
    }
    private static void trace(Expr expression,Set<Expr> seen,List<SourceEvaluationTrace.Occurrence> result) {
        if(!seen.add(expression) || expression instanceof VariableExpr || JavaExpressions.isLiteral(expression)) return;
        JavaExpressions.operands(expression).forEach(child -> trace(child,seen,result));
        result.add(new SourceEvaluationTrace.Occurrence("algebra"+result.size(),expression,NumericKind.BIG_INTEGER,NumericKind.BIG_INTEGER));
    }
}
