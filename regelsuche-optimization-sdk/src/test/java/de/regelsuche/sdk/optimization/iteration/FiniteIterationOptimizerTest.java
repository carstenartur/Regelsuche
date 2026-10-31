package de.regelsuche.sdk.optimization.iteration;

import de.regelsuche.ast.*;
import de.regelsuche.ast.iteration.*;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.symbol.SymbolId;
import de.regelsuche.sdk.optimization.*;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FiniteIterationOptimizerTest {
    private static final SymbolId I = new SymbolId(new UUID(0, 1), 1), S = new SymbolId(new UUID(0, 1), 2), P = new SymbolId(new UUID(0, 1), 3);
    private static final Expr INDEX = VariableExpr.scoped(I), STATE = VariableExpr.scoped(S), PARAM = VariableExpr.scoped(P);
    private static final OptimizationBudget B = OptimizationBudget.DEFAULT;
    private static final FiniteIterationOptimizer OPT = new FiniteIterationOptimizer();
    private static final FiniteIterationVerifier CHECK = new FiniteIterationVerifier();
    private static Expr n(long x) { return new NumberExpr(x); }
    private static Expr add(Expr a, Expr b) { return new BinaryExpr(a, BinaryOperator.ADD, b); }
    private static Expr mul(Expr a, Expr b) { return new BinaryExpr(a, BinaryOperator.MUL, b); }
    private static IndexRange range(long start, long step, long count) { return new IndexRange(BigInteger.valueOf(start), BigInteger.valueOf(step), BigInteger.valueOf(count)); }
    private static FiniteIteration sum(IndexRange r, Expr term, IntegerArithmetic a) { return new FiniteIteration.Sum(r, I, Set.of(P), term, a); }
    private static FiniteIterationOptimizer.Candidate candidate(FiniteIteration source) {
        var found = OPT.search(source, B, CancellationToken.NONE);
        assertTrue(found.candidate().isPresent(), found.reason());
        var c = found.candidate().orElseThrow();
        var checked = CHECK.verify(source, c, B, CancellationToken.NONE);
        assertTrue(checked.verified(), checked.reason()); return c;
    }
    private static BigInteger evaluate(Expr e, BigInteger p) {
        if (e instanceof NumberExpr number) return number.value().numerator();
        if (e instanceof VariableExpr variable) { assertEquals(P, variable.symbol().orElseThrow()); return p; }
        var b = (BinaryExpr)e; var l = evaluate(b.left(), p); var r = evaluate(b.right(), p);
        return switch(b.operator()) { case ADD -> l.add(r); case SUB -> l.subtract(r); case MUL -> l.multiply(r); default -> throw new AssertionError(); };
    }
    @Test void sumsOneThroughHundredAndReportsRealCosts() {
        var source = sum(range(1, 1, 100), INDEX, IntegerArithmetic.EXACT);
        var c = candidate(source);
        assertEquals(BigInteger.valueOf(5050), evaluate(c.result(), BigInteger.ZERO));
        assertEquals(BigInteger.valueOf(100), c.cost().originalOperations());
        assertEquals(0, c.cost().resultOperations());
        var v = CHECK.verify(source, c, B, CancellationToken.NONE);
        assertTrue(v.work() > 0); assertEquals(c.cost(), v.cost());
    }
    @Test void checksConstantsSquaresShiftedDescendingEmptyAndSingleton() {
        Object[][] cases = {{1L,1L,100L,n(7),700L},{1L,1L,100L,mul(INDEX, INDEX),338350L},
            {3L,2L,4L,INDEX,24L},{10L,-2L,4L,INDEX,28L},{5L,1L,0L,INDEX,0L},{-7L,3L,1L,INDEX,-7L}};
        for (var c : cases) assertEquals(BigInteger.valueOf((long)c[4]), evaluate(candidate(sum(range((long)c[0],(long)c[1],(long)c[2]),(Expr)c[3],IntegerArithmetic.EXACT)).result(),BigInteger.ZERO));
    }
    @Test void preservesParametersAndArbitraryInitialValue() {
        var source = new FiniteIteration.Recurrence(range(1,1,100), I,S,Set.of(P),mul(PARAM,n(2)), add(STATE,mul(PARAM,INDEX)), IntegerArithmetic.EXACT);
        assertEquals(BigInteger.valueOf(15156), evaluate(candidate(source).result(),BigInteger.valueOf(3)));
        var descending = new FiniteIteration.Recurrence(range(3,-1,3), I,S,Set.of(P),PARAM, new BinaryExpr(STATE,BinaryOperator.SUB,INDEX),IntegerArithmetic.EXACT);
        assertEquals(BigInteger.valueOf(11),evaluate(candidate(descending).result(),BigInteger.valueOf(17)));
    }
    @Test void matchesIndependentSmallLoopsInAllRings() {
        for (var arithmetic : IntegerArithmetic.values()) for (int start=-3;start<=3;start++) for (int step : new int[]{-2,1,3}) {
            long count = 8; Expr term = add(mul(INDEX,INDEX),mul(PARAM,n(2147483647L)));
            var source = new FiniteIteration.Recurrence(range(start,step,count),I,S,Set.of(P),n(Long.MAX_VALUE),add(STATE,term),arithmetic);
            Expr result = candidate(source).result();
            for (long p : new long[]{-9,0,7,Long.MAX_VALUE}) {
                BigInteger expected = BigInteger.valueOf(Long.MAX_VALUE);
                for(int j=0;j<count;j++) { var i=BigInteger.valueOf(start+j*step); expected=expected.add(i.multiply(i)).add(BigInteger.valueOf(p).multiply(BigInteger.valueOf(2147483647L))); }
                BigInteger actual = evaluate(result,BigInteger.valueOf(p));
                if(arithmetic==IntegerArithmetic.JAVA_INT) { expected=BigInteger.valueOf(expected.intValue()); actual=BigInteger.valueOf(actual.intValue()); }
                if(arithmetic==IntegerArithmetic.JAVA_LONG) { expected=BigInteger.valueOf(expected.longValue()); actual=BigInteger.valueOf(actual.longValue()); }
                assertEquals(expected,actual);
            }
        }
    }
    @Test void rejectsNonPolynomialFractionalAndNonlinearSources() {
        for(Expr term : List.of(new BinaryExpr(INDEX,BinaryOperator.DIV,n(1)),new FunctionExpr("sin",INDEX),NumberExpr.exact("1/2"), new BinaryExpr(INDEX,BinaryOperator.POW,n(2))))
            assertTrue(OPT.search(sum(range(1,1,3),term,IntegerArithmetic.JAVA_INT),B,CancellationToken.NONE).candidate().isEmpty());
        var nonlinear = new FiniteIteration.Recurrence(range(1,1,3),I,S,Set.of(),n(1),mul(STATE,STATE),IntegerArithmetic.EXACT);
        assertTrue(OPT.search(nonlinear,B,CancellationToken.NONE).candidate().isEmpty());
    }
    @Test void rejectsTamperedResultCertificateSourceAndArithmetic() {
        var source = sum(range(1,1,100),INDEX,IntegerArithmetic.EXACT); var c=candidate(source);
        assertFalse(CHECK.verify(source,n(5051),c.certificate(),B,CancellationToken.NONE).verified());
        var wrong = new FiniteIterationOptimizer.Certificate(source,n(0));
        assertFalse(CHECK.verify(source,c.result(),wrong,B,CancellationToken.NONE).verified());
        assertFalse(CHECK.verify(sum(range(1,1,99),INDEX,IntegerArithmetic.EXACT),c,B,CancellationToken.NONE).verified());
        assertFalse(CHECK.verify(sum(range(1,1,100),INDEX,IntegerArithmetic.JAVA_INT),c,B,CancellationToken.NONE).verified());
        assertFalse(CHECK.verify(source,NumberExpr.exact("10100/2"),wrong,B,CancellationToken.NONE).verified());
        var forgedCost = new FiniteIterationOptimizer.Candidate(c.result(),c.certificate(),0,new FiniteIterationOptimizer.Cost(BigInteger.ZERO,999));
        assertEquals(c.cost(),CHECK.verify(source,forgedCost,B,CancellationToken.NONE).cost());
    }
    @Test void hugeCountHasBoundedProofAndCancellationAndGrowthFailClosed() {
        var huge = new IndexRange(BigInteger.ONE,BigInteger.ONE,BigInteger.TEN.pow(100));
        var c=candidate(sum(huge,INDEX,IntegerArithmetic.EXACT));
        assertTrue(c.work()<10000);
        assertEquals(new BigInteger("5"+"0".repeat(99)+"5"+"0".repeat(99)),evaluate(c.result(),BigInteger.ZERO));
        var source=sum(range(1,1,100),INDEX,IntegerArithmetic.EXACT);
        assertEquals("CANCELLED",OPT.search(source,B,()->true).reason());
        assertFalse(CHECK.verify(source,c,B,()->true).verified());
        assertTrue(OPT.search(source,new OptimizationBudget(1,128,1,5000),CancellationToken.NONE).candidate().isEmpty());
        Expr high = INDEX; for(int j=0;j<9;j++) high=mul(high,INDEX);
        assertTrue(OPT.search(sum(range(1,1,3),high,IntegerArithmetic.EXACT),B,CancellationToken.NONE).candidate().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> sum(range(1,1,3),new NumberExpr(ExactRational.integer(BigInteger.ONE.shiftLeft(5000))),IntegerArithmetic.EXACT));
    }
    @Test void emittedTreesAndCertificateTreesAreUntrustedAndBounded() {
        var source=sum(range(1,1,100),INDEX,IntegerArithmetic.EXACT); var c=candidate(source);
        Expr deep=n(5050); for(int j=0;j<100;j++) deep=add(deep,n(0));
        assertFalse(CHECK.verify(source,deep,c.certificate(),B,CancellationToken.NONE).verified());
        assertFalse(CHECK.verify(source,c.result(),new FiniteIterationOptimizer.Certificate(source,deep),B,CancellationToken.NONE).verified());
        var huge=new NumberExpr(ExactRational.integer(BigInteger.ONE.shiftLeft(100000)));
        assertFalse(CHECK.verify(source,huge,c.certificate(),B,CancellationToken.NONE).verified());
        assertFalse(CHECK.verify(source,c.result(),new FiniteIterationOptimizer.Certificate(source,huge),B,CancellationToken.NONE).verified());
        assertFalse(CHECK.verify(source,INDEX,c.certificate(),B,CancellationToken.NONE).verified());
        assertFalse(CHECK.verify(source,new BinaryExpr(n(10100),BinaryOperator.DIV,n(2)),c.certificate(),B,CancellationToken.NONE).verified());
        assertFalse(CHECK.verify(source,add(n(5050),new BinaryExpr(n(1),BinaryOperator.SUB,NumberExpr.exact("1/2"))),c.certificate(),B,CancellationToken.NONE).verified());
    }
    @Test void endpointAndInitialTamperingFailAndEquivalentModularEmissionPasses() {
        var source=new FiniteIteration.Recurrence(range(1,1,100),I,S,Set.of(P),PARAM,add(STATE,INDEX),IntegerArithmetic.JAVA_INT);
        var c=candidate(source);
        var changed=new FiniteIteration.Recurrence(source.range(),I,S,Set.of(P),add(PARAM,n(1)),source.update(),source.arithmetic());
        assertFalse(CHECK.verify(changed,c,B,CancellationToken.NONE).verified());
        assertTrue(CHECK.verify(source,add(c.result(),n(4294967296L)),c.certificate(),B,CancellationToken.NONE).verified());
        assertFalse(CHECK.verify(source,n(5050),c.certificate(),B,CancellationToken.NONE).verified());
        var empty=new FiniteIteration.Recurrence(range(7,-1,0),I,S,Set.of(P),PARAM,add(STATE,INDEX),IntegerArithmetic.EXACT);
        assertEquals(BigInteger.valueOf(123),evaluate(candidate(empty).result(),BigInteger.valueOf(123)));
    }
    @Test void higherDegreeUsesTheSameGeneralProofAndGrowthIsBounded() {
        Expr power=INDEX; for(int degree=2;degree<=8;degree++) power=mul(power,INDEX);
        var c=candidate(sum(range(-2,1,5),power,IntegerArithmetic.EXACT));
        assertEquals(BigInteger.valueOf(514),evaluate(c.result(),BigInteger.ZERO));
        var tiny=new OptimizationBudget(100000,1,1,5000);
        assertEquals("TERM_LIMIT",OPT.search(sum(range(1,1,3),add(INDEX,PARAM),IntegerArithmetic.EXACT),tiny,CancellationToken.NONE).reason());
        var coefficient=new NumberExpr(ExactRational.integer(BigInteger.ONE.shiftLeft(3000)));
        assertEquals("COEFFICIENT_LIMIT",OPT.search(sum(range(1,1,3),mul(coefficient,coefficient),IntegerArithmetic.EXACT),B,CancellationToken.NONE).reason());
        int[] polls={0};
        assertEquals("CANCELLED",OPT.search(sum(range(1,1,3),INDEX,IntegerArithmetic.EXACT),B,()->++polls[0]>20).reason());
        int[] proofPolls={0};
        assertEquals("CANCELLED",CHECK.verify(c.certificate().source(),c,B,()->++proofPolls[0]>20).reason());
        assertEquals("WORK_LIMIT",CHECK.verify(c.certificate().source(),c,new OptimizationBudget(1,128,1,5000),CancellationToken.NONE).reason());
    }

}
