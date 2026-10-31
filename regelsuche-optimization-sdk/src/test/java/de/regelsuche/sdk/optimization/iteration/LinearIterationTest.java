package de.regelsuche.sdk.optimization.iteration;

import de.regelsuche.ast.*;
import de.regelsuche.ast.iteration.IntegerArithmetic;
import de.regelsuche.symbol.SymbolId;
import de.regelsuche.sdk.optimization.*;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LinearIterationTest {
    static final SymbolId I=id(1), S=id(2), T=id(3), A=id(4), N=id(5);
    static SymbolId id(int n) { return new SymbolId(new UUID(0,1),n); }
    static Expr v(SymbolId x) { return VariableExpr.scoped(x); }
    static Expr n(long x) { return new NumberExpr(x); }
    static Expr add(Expr a,Expr b) { return new BinaryExpr(a,BinaryOperator.ADD,b); }
    static Expr mul(Expr a,Expr b) { return new BinaryExpr(a,BinaryOperator.MUL,b); }
    static final OptimizationBudget B=OptimizationBudget.DEFAULT;
    static LinearIteration scalar(Expr update, Expr count, IntegerArithmetic ring) {
        return new LinearIteration(I,n(1),BigInteger.ONE,count,List.of(new LinearIteration.State(S,n(3))),Set.of(A,N),List.of(new LinearIteration.Update(S,update)),LinearIteration.UpdateOrder.SEQUENTIAL,ring);
    }
    static LinearIterationOptimizer.Candidate candidate(LinearIteration source) {
        var r=new LinearIterationOptimizer().search(source,B,CancellationToken.NONE);
        assertTrue(r.candidate().isPresent(),r.reason());
        var c=r.candidate().orElseThrow();
        var checked=new LinearIterationVerifier().verify(source,c,B,CancellationToken.NONE);
        assertTrue(checked.verified(),checked.reason()); return c;
    }
    static List<BigInteger> evaluate(LinearIteration s,Map<SymbolId,BigInteger> bindings) {
        var r=new LinearIterationEvaluator().evaluate(s,candidate(s),bindings,B,CancellationToken.NONE);
        assertTrue(r.values().isPresent(),r.reason()); return r.values().orElseThrow();
    }
    @Test void affineMatchesIndependentLoopsIncludingSpecialFactorsAndOverflow() {
        for(var ring:IntegerArithmetic.values()) for(long a:new long[]{0,1,-1,3,Long.MAX_VALUE}) {
            var s=scalar(add(mul(v(A),v(S)),n(7)),n(15),ring);
            var expected=BigInteger.valueOf(3);
            for(int j=0;j<15;j++) expected=ring.normalize(expected.multiply(BigInteger.valueOf(a)).add(BigInteger.valueOf(7)));
            assertEquals(List.of(expected),evaluate(s,Map.of(A,BigInteger.valueOf(a))));
        }
    }
    @Test void polynomialForcingAndNonunitNegativeStride() {
        var s=new LinearIteration(I,n(8),BigInteger.valueOf(-2),n(9),List.of(new LinearIteration.State(S,n(3))),Set.of(),List.of(new LinearIteration.Update(S,add(v(S),mul(v(I),v(I))))),LinearIteration.UpdateOrder.SEQUENTIAL,IntegerArithmetic.EXACT);
        long expected=3; for(int k=0;k<9;k++) expected+=(8-2*k)*(8-2*k);
        assertEquals(List.of(BigInteger.valueOf(expected)),evaluate(s,Map.of()));
        assertEquals(4,candidate(s).transition().matrix().size());
    }
    @Test void fibonacciAndOrderedUpdatesPreserveEveryOutput() {
        var states=List.of(new LinearIteration.State(S,n(0)),new LinearIteration.State(T,n(1)));
        var updates=List.of(new LinearIteration.Update(S,v(T)),new LinearIteration.Update(T,add(v(S),v(T))));
        for(var order:LinearIteration.UpdateOrder.values()) {
            var s=new LinearIteration(I,n(0),BigInteger.ONE,n(10),states,Set.of(),updates,order,IntegerArithmetic.EXACT);
            assertEquals(order==LinearIteration.UpdateOrder.SIMULTANEOUS ? List.of(BigInteger.valueOf(55),BigInteger.valueOf(89)): List.of(BigInteger.valueOf(512),BigInteger.valueOf(1024)),evaluate(s,Map.of()));
        }
    }
    @Test void geometricAccumulationAndProductForHugeCountAreLogarithmic() {
        var s=new LinearIteration(I,n(0),BigInteger.ONE,v(N),List.of(new LinearIteration.State(S,n(0)),new LinearIteration.State(T,n(1))),Set.of(N,A),List.of(new LinearIteration.Update(S,add(v(S),v(T))),new LinearIteration.Update(T,mul(v(A),v(T)))),LinearIteration.UpdateOrder.SEQUENTIAL,IntegerArithmetic.JAVA_LONG);
        var huge=BigInteger.TEN.pow(100);
        assertEquals(List.of(BigInteger.ZERO,BigInteger.ONE),evaluate(s,Map.of(N,huge,A,BigInteger.valueOf(-1))));
    }
    @Test void zeroCountAvoidsUpdateOnlyAndIndexStartBindings() {
        var s=new LinearIteration(I,v(A),BigInteger.ONE,n(0),List.of(new LinearIteration.State(S,n(9))),Set.of(A),List.of(new LinearIteration.Update(S,mul(v(A),v(S)))),LinearIteration.UpdateOrder.SEQUENTIAL,IntegerArithmetic.EXACT);
        assertEquals(List.of(BigInteger.valueOf(9)),evaluate(s,Map.of()));
    }
    @Test void rejectsTamperedMatrixInitialSourceAndEmittedResult() {
        var s=scalar(add(v(S),n(2)),n(3),IntegerArithmetic.EXACT); var c=candidate(s);
        var matrix=new ArrayList<>(c.transition().matrix()); matrix.set(0,List.of(n(1),n(3)));
        var forged=new LinearIterationOptimizer.Candidate(s,new LinearTransition(matrix,c.transition().initial(),1,0),0);
        var verifier=new LinearIterationVerifier();
        assertFalse(verifier.verify(s,forged,B,CancellationToken.NONE).verified());
        var forgedInitial=new LinearIterationOptimizer.Candidate(s,new LinearTransition(c.transition().matrix(),List.of(n(4),n(1)),1,0),0);
        assertFalse(verifier.verify(s,forgedInitial,B,CancellationToken.NONE).verified());
        assertFalse(verifier.verify(scalar(add(v(S),n(2)),n(4),IntegerArithmetic.EXACT),c,B,CancellationToken.NONE).verified());
        assertTrue(verifier.verifyResult(s,c,Map.of(),List.of(BigInteger.valueOf(9)),B,CancellationToken.NONE).verified());
        assertFalse(verifier.verifyResult(s,c,Map.of(),List.of(BigInteger.TEN),B,CancellationToken.NONE).verified());
    }
    @Test void invalidScopeDimensionAndNonlinearityFailClosed() {
        assertThrows(IllegalArgumentException.class,()->scalar(v(id(999)),n(2),IntegerArithmetic.EXACT));
        assertThrows(IllegalArgumentException.class,()->new LinearTransition(List.of(List.of(n(1))),List.of(n(1)),1,0));
        for(var update:List.of(mul(v(S),v(S)),mul(v(I),v(S)),new BinaryExpr(v(S),BinaryOperator.DIV,n(2))))
            assertTrue(new LinearIterationOptimizer().search(scalar(update,n(2),IntegerArithmetic.EXACT),B,CancellationToken.NONE).candidate().isEmpty());
    }
    @Test void budgetsCancellationAndExactGrowthAreBounded() {
        var s=scalar(mul(n(2),v(S)),v(N),IntegerArithmetic.EXACT); var c=candidate(s);
        assertEquals("CANCELLED",new LinearIterationOptimizer().search(s,B,()->true).reason());
        assertFalse(new LinearIterationVerifier().verify(s,c,new OptimizationBudget(1,128,1,5000),CancellationToken.NONE).verified());
        var e=new LinearIterationEvaluator();
        assertEquals("VALUE_LIMIT",e.evaluate(s,c,Map.of(N,BigInteger.TEN.pow(100)),B,CancellationToken.NONE).reason());
        assertEquals("NEGATIVE_COUNT",e.evaluate(s,c,Map.of(N,BigInteger.valueOf(-1)),B,CancellationToken.NONE).reason());
        assertEquals("CANCELLED",e.evaluate(s,c,Map.of(N,BigInteger.TEN),B,()->true).reason());
    }
    @Test void degreeEightForcingKeepsIndependentParameterDegree() {
        Expr forcing=v(A); for(int k=0;k<8;k++) forcing=mul(forcing,v(I));
        var source=scalar(add(v(S),forcing),n(5),IntegerArithmetic.EXACT);
        BigInteger expected=BigInteger.valueOf(3);
        for(int i=1;i<=5;i++) expected=expected.add(BigInteger.valueOf(i).pow(8).multiply(BigInteger.valueOf(7)));
        assertEquals(List.of(expected),evaluate(source,Map.of(A,BigInteger.valueOf(7))));
        assertEquals(8,candidate(source).transition().indexDegree());
    }
    @Test void supportsEightStatesAndRejectsNineOrRepeatedAssignments() {
        var states=new ArrayList<LinearIteration.State>(); var updates=new ArrayList<LinearIteration.Update>();
        for(int k=0;k<8;k++) {
            var symbol=id(20+k); states.add(new LinearIteration.State(symbol,n(k)));
            updates.add(new LinearIteration.Update(symbol,add(v(symbol),n(2))));
        }
        var source=new LinearIteration(I,n(0),BigInteger.ONE,n(4),states,Set.of(),updates,LinearIteration.UpdateOrder.SIMULTANEOUS,IntegerArithmetic.EXACT);
        assertEquals(java.util.stream.IntStream.range(0,8).mapToObj(k->BigInteger.valueOf(k+8)).toList(),evaluate(source,Map.of()));
        var repeated=new ArrayList<>(updates); repeated.set(1,updates.getFirst());
        assertThrows(IllegalArgumentException.class,()->new LinearIteration(I,n(0),BigInteger.ONE,n(4),states,Set.of(),repeated,LinearIteration.UpdateOrder.SIMULTANEOUS,IntegerArithmetic.EXACT));
        states.add(new LinearIteration.State(id(28),n(8))); updates.add(new LinearIteration.Update(id(28),n(8)));
        assertThrows(IllegalArgumentException.class,()->new LinearIteration(I,n(0),BigInteger.ONE,n(4),states,Set.of(),updates,LinearIteration.UpdateOrder.SIMULTANEOUS,IntegerArithmetic.EXACT));
    }
    @Test void maliciousAugmentationAndBindingsFailClosedAndTransportIsImmutable() {
        var source=scalar(add(v(S),v(I)),n(5),IntegerArithmetic.JAVA_INT); var c=candidate(source);
        var rows=new ArrayList<>(c.transition().matrix()); rows.set(2,List.of(n(0),n(2),n(1)));
        var forged=new LinearIterationOptimizer.Candidate(source,new LinearTransition(rows,c.transition().initial(),1,1),Long.MIN_VALUE);
        var verifier=new LinearIterationVerifier();
        assertFalse(verifier.verify(source,forged,B,CancellationToken.NONE).verified());
        rows.set(0,List.of(v(S),n(0),n(1)));
        assertFalse(verifier.verify(source,new LinearIterationOptimizer.Candidate(source,new LinearTransition(rows,c.transition().initial(),1,1),0),B,CancellationToken.NONE).verified());
        assertThrows(UnsupportedOperationException.class,()->c.transition().matrix().getFirst().set(0,n(99)));
        var sourceWithParam=scalar(mul(v(A),v(S)),v(N),IntegerArithmetic.JAVA_INT);
        var candidateWithParam=candidate(sourceWithParam); var evaluator=new LinearIterationEvaluator();
        assertEquals("MISSING_PARAMETER",evaluator.evaluate(sourceWithParam,candidateWithParam,Map.of(N,BigInteger.ONE),B,CancellationToken.NONE).reason());
        assertEquals("VALUE_LIMIT",evaluator.evaluate(sourceWithParam,candidateWithParam,Map.of(N,BigInteger.ONE.shiftLeft(5000)),B,CancellationToken.NONE).reason());
        assertTrue(verifier.verify(source,new LinearIterationOptimizer.Candidate(source,c.transition(),Long.MIN_VALUE),B,CancellationToken.NONE).verified());
    }

}
