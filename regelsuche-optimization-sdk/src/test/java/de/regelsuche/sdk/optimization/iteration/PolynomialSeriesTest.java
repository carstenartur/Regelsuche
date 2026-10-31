package de.regelsuche.sdk.optimization.iteration;

import de.regelsuche.ast.*;
import de.regelsuche.ast.iteration.IntegerArithmetic;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.symbol.SymbolId;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static de.regelsuche.sdk.optimization.iteration.PolynomialSumTest.*;

class PolynomialSeriesTest {
    static final SymbolId J=new SymbolId(new UUID(1,2),3);
    static PolynomialSum simple(SymbolId index,Set<SymbolId> params,Expr start,long stride,Expr term,Expr initial) {
        return new PolynomialSum(index,params,start,BigInteger.valueOf(stride),term,initial,IntegerArithmetic.EXACT);
    }
    @Test void nestedTriangularAndRectangularBoundsMatchDirectEvaluation() {
        var inner=simple(J,Set.of(I,P),n(1),1,v(J),n(0));
        for(Expr bound:List.of(v(I),v(P))) {
            var source=new PolynomialSeries.NestedSum(inner,bound,I,Set.of(P),n(1),BigInteger.ONE,n(5),IntegerArithmetic.EXACT);
            var plan=PolynomialSeries.nested(source,B,CancellationToken.NONE);
            for(int count=0;count<=20;count++) {
                long expected=5;
                for(int i=1;i<=count;i++) for(int j=1;j<=(bound.equals(v(I))?i:4);j++) expected+=j;
                assertEquals(BigInteger.valueOf(expected),plan.evaluate(BigInteger.valueOf(count),Map.of(P,BigInteger.valueOf(4)),B,CancellationToken.NONE));
            }
            assertTrue(plan.verify(B,CancellationToken.NONE).verified());
        }
    }
    @Test void nestedRejectsNegativeBoundsTamperingAndNonAffineCounts() {
        var source=new PolynomialSeries.NestedSum(simple(J,Set.of(I),n(1),1,v(J),n(0)),v(I),I,Set.of(),n(-1),BigInteger.ONE,n(7),IntegerArithmetic.EXACT);
        var p=PolynomialSeries.nested(source,B,CancellationToken.NONE);
        assertEquals(BigInteger.valueOf(7),p.evaluate(BigInteger.ZERO,Map.of(),B,CancellationToken.NONE));
        assertThrows(IllegalArgumentException.class,()->p.evaluate(BigInteger.valueOf(2),Map.of(),B,CancellationToken.NONE));
        var bad=new PolynomialSeries.NestedPlan(source,p.inner(),plan(simple(I,Set.of(),n(-1),1,n(0),n(7))));
        assertFalse(bad.verify(B,CancellationToken.NONE).verified());
        var nonlinear=new PolynomialSeries.NestedSum(source.inner(),mul(v(I),v(I)),I,Set.of(),n(0),BigInteger.ONE,n(0),IntegerArithmetic.EXACT);
        assertThrows(IllegalArgumentException.class,()->PolynomialSeries.nested(nonlinear,B,CancellationToken.NONE));
    }
    @Test void expandedNestedCertificateOverTransportLimitReturnsFailedVerification() {
        var a=new SymbolId(new UUID(1,2),4); var b=new SymbolId(new UUID(1,2),5); var c=new SymbolId(new UUID(1,2),6);
        var budget=new de.regelsuche.sdk.optimization.OptimizationBudget(20_000_000,256,32,30_000);
        var inner=simple(J,Set.of(I,P,a,b,c),n(1),1,mul(n(30),mul(pow(v(J),4),pow(v(P),16))),n(0));
        var found=new PolynomialSumOptimizer().search(inner,budget,CancellationToken.NONE);
        assertTrue(found.plan().isPresent(),found.reason());
        var innerPlan=found.plan().orElseThrow();
        assertTrue(new PolynomialSumVerifier().verify(inner,innerPlan,budget,CancellationToken.NONE).verified());
        var source=new PolynomialSeries.NestedSum(inner,mul(v(I),add(add(v(a),v(b)),v(c))),I,Set.of(P,a,b,c),n(1),BigInteger.ONE,n(0),IntegerArithmetic.EXACT);
        var outer=plan(simple(I,Set.of(P,a,b,c),n(1),1,n(0),n(0)));
        var candidate=new PolynomialSeries.NestedPlan(source,innerPlan,outer);
        var checked=assertDoesNotThrow(()->candidate.verify(budget,CancellationToken.NONE));
        assertFalse(checked.verified());
        assertEquals("EXPRESSION_LIMIT",checked.reason());
        assertTrue(checked.work()>0);
        assertEquals("EXPRESSION_LIMIT",assertThrows(IllegalArgumentException.class,()->PolynomialSeries.nested(source,budget,CancellationToken.NONE)).getMessage());
        assertThrows(NullPointerException.class,()->candidate.verify(null,CancellationToken.NONE));
    }
    @Test void allEmptyNestedRangesPreserveInitialWithoutReadingInnerTermParameters() {
        var k=new SymbolId(new UUID(1,2),4);
        var source=new PolynomialSeries.NestedSum(simple(J,Set.of(I,P,k),n(1),1,v(k),n(2)),v(P),I,Set.of(P,k),n(0),BigInteger.ONE,n(7),IntegerArithmetic.EXACT);
        var p=PolynomialSeries.nested(source,B,CancellationToken.NONE);
        assertEquals(BigInteger.valueOf(13),p.evaluate(BigInteger.valueOf(3),Map.of(P,BigInteger.ZERO),B,CancellationToken.NONE));
    }
    @Test void alternatingPeriodicTermsAndZeroCounts() {
        var even=plan(simple(I,Set.of(),n(0),2,v(I),n(0)));
        var odd=plan(simple(I,Set.of(),n(1),2,mul(n(-1),v(I)),n(0)));
        var periodic=new PolynomialSeries.PeriodicPlan(List.of(even,odd));
        for(int count=0;count<30;count++) {
            long expected=0; for(int i=0;i<count;i++) expected+=(i%2==0?i:-i);
            assertEquals(BigInteger.valueOf(expected),periodic.evaluate(BigInteger.valueOf(count),Map.of(),B,CancellationToken.NONE));
        }
        assertEquals(BigInteger.TEN.pow(99).negate(),periodic.evaluate(BigInteger.TWO.multiply(BigInteger.TEN.pow(99)),Map.of(),B,CancellationToken.NONE));
    }
    @Test void contiguousPiecewiseRangesAreExactAndBounded() {
        var first=plan(simple(I,Set.of(),n(0),1,v(I),n(0)));
        var second=plan(simple(I,Set.of(),n(3),1,mul(n(2),v(I)),n(0)));
        var pieces=new PolynomialSeries.PiecewisePlan(List.of(new PolynomialSeries.Piece(BigInteger.valueOf(3),first),new PolynomialSeries.Piece(null,second)));
        for(int count=0;count<20;count++) {
            long expected=0; for(int i=0;i<count;i++) expected+=(i<3?i:2*i);
            assertEquals(BigInteger.valueOf(expected),pieces.evaluate(BigInteger.valueOf(count),Map.of(),B,CancellationToken.NONE));
        }
        assertThrows(IllegalArgumentException.class,()->new PolynomialSeries.PiecewisePlan(List.of(new PolynomialSeries.Piece(BigInteger.valueOf(4),first),new PolynomialSeries.Piece(BigInteger.valueOf(3),second))));
        assertThrows(IllegalArgumentException.class,()->pieces.evaluate(BigInteger.ONE,Map.of(),B,()->true));
    }
}
