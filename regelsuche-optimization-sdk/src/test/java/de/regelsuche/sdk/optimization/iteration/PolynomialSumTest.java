package de.regelsuche.sdk.optimization.iteration;

import de.regelsuche.ast.*;
import de.regelsuche.ast.iteration.IntegerArithmetic;
import de.regelsuche.symbol.SymbolId;
import de.regelsuche.sdk.optimization.*;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PolynomialSumTest {
    static final SymbolId I=new SymbolId(new UUID(1,2),1), P=new SymbolId(new UUID(1,2),2);
    static final OptimizationBudget B=new OptimizationBudget(2_000_000,256,32,30_000);
    static Expr n(long v) { return new NumberExpr(v); }
    static Expr v(SymbolId s) { return VariableExpr.scoped(s); }
    static Expr add(Expr a,Expr b) { return new BinaryExpr(a,BinaryOperator.ADD,b); }
    static Expr mul(Expr a,Expr b) { return new BinaryExpr(a,BinaryOperator.MUL,b); }
    static Expr pow(Expr x,int p) { Expr r=n(1); for(int j=0;j<p;j++) r=mul(r,x); return r; }
    static PolynomialSum source(Expr term) { return new PolynomialSum(I,Set.of(P),v(P),BigInteger.valueOf(-2),term,n(7),IntegerArithmetic.EXACT); }
    static PolynomialSumPlan plan(PolynomialSum s) {
        var r=new PolynomialSumOptimizer().search(s,B,CancellationToken.NONE);
        assertTrue(r.plan().isPresent(),r.reason()); var p=r.plan().orElseThrow();
        var check=new PolynomialSumVerifier().verify(s,p,B,CancellationToken.NONE);
        assertTrue(check.verified(),check.reason()); return p;
    }
    @Test void powersZeroThroughEightWithSymbolicStartsDescendingStrideAndParameterProducts() {
        for(int d=0;d<=8;d++) {
            var p=plan(source(mul(pow(v(I),d),pow(v(P),3))));
            assertTrue(p.coefficients().size()<=d+1);
            for(int count=0;count<12;count++) {
                BigInteger expected=BigInteger.valueOf(7);
                for(int j=0;j<count;j++) expected=expected.add(BigInteger.valueOf(3-2*j).pow(d).multiply(BigInteger.valueOf(27)));
                assertEquals(expected,p.evaluate(BigInteger.valueOf(count),Map.of(P,BigInteger.valueOf(3)),B,CancellationToken.NONE));
            }
        }
    }
    @Test void largeAndHugeCountsUseCompactExactArithmetic() {
        var s=new PolynomialSum(I,Set.of(),n(1),BigInteger.ONE,v(I),n(0),IntegerArithmetic.EXACT);
        var p=plan(s);
        for(var count:List.of(BigInteger.valueOf(50000),BigInteger.TEN.pow(100)))
            assertEquals(count.multiply(count.add(BigInteger.ONE)).divide(BigInteger.TWO),p.evaluate(count,Map.of(),B,CancellationToken.NONE));
        assertThrows(IllegalArgumentException.class,()->p.evaluate(BigInteger.ONE.negate(),Map.of(),B,CancellationToken.NONE));
    }
    @Test void checkerRejectsCoefficientInitialSourceAndScopeTampering() {
        var s=source(v(I)); var p=plan(s); var check=new PolynomialSumVerifier();
        for(var bad:List.of(new PolynomialSumPlan(s,p.initial(),List.of(n(9))),new PolynomialSumPlan(s,n(8),p.coefficients()),new PolynomialSumPlan(source(n(2)),p.initial(),p.coefficients()),new PolynomialSumPlan(s,p.initial(),List.of(VariableExpr.scoped(new SymbolId(new UUID(1,2),7))))))
            assertFalse(check.verify(s,bad,B,CancellationToken.NONE).verified());
    }
    @Test void generatedCoefficientTreesRespectTransportLimits() {
        var q=new SymbolId(new UUID(1,2),3); var r=new SymbolId(new UUID(1,2),4); var t=new SymbolId(new UUID(1,2),5);
        var s=new PolynomialSum(I,Set.of(P,q,r,t),add(add(v(q),v(r)),v(t)),BigInteger.ONE,mul(pow(v(I),8),pow(v(P),16)),n(0),IntegerArithmetic.EXACT);
        var found=new PolynomialSumOptimizer().search(s,B,CancellationToken.NONE);
        assertTrue(found.plan().isEmpty()); assertEquals("EXPRESSION_LIMIT",found.reason());
    }
    @Test void multivariateIntegerValuedCoefficientsSupportNegativeParameters() {
        var coefficient=mul(NumberExpr.exact("1/2"),mul(v(P),add(v(P),n(-1))));
        var s=new PolynomialSum(I,Set.of(P),n(1),BigInteger.ONE,mul(coefficient,v(I)),n(0),IntegerArithmetic.EXACT);
        var p=plan(s);
        for(int value=-4;value<=4;value++) assertEquals(BigInteger.valueOf(15L*value*(value-1)/2),p.evaluate(BigInteger.valueOf(5),Map.of(P,BigInteger.valueOf(value)),B,CancellationToken.NONE));
    }
    @Test void modularArithmeticRequiresIntegralSourceLeavesAndNormalizesExactResult() {
        for(var arithmetic:List.of(IntegerArithmetic.JAVA_INT,IntegerArithmetic.JAVA_LONG)) {
            var source=new PolynomialSum(I,Set.of(),n(1),BigInteger.ONE,v(I),n(Long.MAX_VALUE),arithmetic);
            var count=BigInteger.TEN.pow(30);
            var expected=arithmetic.normalize(BigInteger.valueOf(Long.MAX_VALUE).add(count.multiply(count.add(BigInteger.ONE)).divide(BigInteger.TWO)));
            assertEquals(expected,plan(source).evaluate(count,Map.of(),B,CancellationToken.NONE));
            var rational=new PolynomialSum(I,Set.of(),n(1),BigInteger.ONE,mul(NumberExpr.exact("1/2"),mul(v(I),add(v(I),n(-1)))),n(0),arithmetic);
            assertTrue(new PolynomialSumOptimizer().search(rational,B,CancellationToken.NONE).plan().isEmpty());
        }
    }
    @Test void missingSymbolsLimitsAndCancellationFailClosedButZeroSkipsUnusedReads() {
        var s=source(v(I)); var p=plan(s);
        assertEquals(BigInteger.valueOf(7),p.evaluate(BigInteger.ZERO,Map.of(),B,CancellationToken.NONE));
        assertThrows(IllegalArgumentException.class,()->p.evaluate(BigInteger.ONE,Map.of(),B,CancellationToken.NONE));
        assertFalse(new PolynomialSumOptimizer().search(s,B,()->true).plan().isPresent());
        assertFalse(new PolynomialSumOptimizer().search(s,new OptimizationBudget(1,1,1,1000),CancellationToken.NONE).plan().isPresent());
        assertFalse(new PolynomialSumOptimizer().search(source(pow(v(I),9)),B,CancellationToken.NONE).plan().isPresent());
        assertThrows(IllegalArgumentException.class,()->p.evaluate(BigInteger.ONE.shiftLeft(65537),Map.of(P,BigInteger.ONE),B,CancellationToken.NONE));
    }
    @Test void integerValuedRationalTermsRequireProofAndNeverAcceptJavaDivision() {
        var integral=mul(NumberExpr.exact("1/2"),mul(v(I),add(v(I),n(-1))));
        assertEquals(BigInteger.valueOf(14),plan(source(integral)).evaluate(BigInteger.valueOf(3),Map.of(P,BigInteger.valueOf(4)),B,CancellationToken.NONE));
        assertTrue(new PolynomialSumOptimizer().search(source(NumberExpr.exact("1/2")),B,CancellationToken.NONE).plan().isEmpty());
        assertTrue(new PolynomialSumOptimizer().search(source(new BinaryExpr(v(I),BinaryOperator.DIV,n(2))),B,CancellationToken.NONE).plan().isEmpty());
    }
}
