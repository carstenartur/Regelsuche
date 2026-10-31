package de.regelsuche.sdk.optimization.iteration;

import de.regelsuche.ast.*;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.sdk.optimization.CancellationToken;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static de.regelsuche.sdk.optimization.iteration.PolynomialSumTest.*;

class RationalTelescopingTest {
    static RationalTelescoping.Source source(long start,long stride,Expr numerator,Expr denominator) {
        return new RationalTelescoping.Source(I,Set.of(P),n(start),BigInteger.valueOf(stride),numerator,denominator,ExactRational.ZERO);
    }
    static RationalTelescoping.Plan plan(RationalTelescoping.Source s) {
        var found=RationalTelescoping.search(s,B,CancellationToken.NONE);
        assertTrue(found.plan().isPresent(),found.reason()); var p=found.plan().orElseThrow();
        assertTrue(RationalTelescoping.verify(s,p,B,CancellationToken.NONE).verified()); return p;
    }
    @Test void reciprocalAdjacentProductsTelescopeExactlyForHugeCounts() {
        var p=plan(source(1,1,n(1),mul(v(I),add(v(I),n(1)))));
        for(int count=0;count<20;count++) {
            var expected=ExactRational.ZERO;
            for(int i=1;i<=count;i++) expected=expected.add(ExactRational.ONE.divide(ExactRational.integer((long)i*(i+1))));
            assertEquals(expected,p.evaluate(BigInteger.valueOf(count),Map.of(),B,CancellationToken.NONE));
        }
        var count=BigInteger.TEN.pow(100);
        assertEquals(ExactRational.integer(count).divide(ExactRational.integer(count.add(BigInteger.ONE))),p.evaluate(count,Map.of(),B,CancellationToken.NONE));
    }
    @Test void symbolicShiftsNonunitStridesAndNonzeroInitial() {
        var x=add(v(I),v(P)); var d=mul(x,add(x,n(2)));
        var s=new RationalTelescoping.Source(I,Set.of(P),n(1),BigInteger.TWO,n(3),d,ExactRational.ONE);
        var p=plan(s); var expected=ExactRational.ONE;
        for(int j=0;j<6;j++) expected=expected.add(ExactRational.integer(3).divide(ExactRational.integer((long)(j*2+4)*(j*2+6))));
        assertEquals(expected,p.evaluate(BigInteger.valueOf(6),Map.of(P,BigInteger.valueOf(3)),B,CancellationToken.NONE));
    }
    @Test void polesIncludeInteriorAndFinalDenominatorsButEmptyDoesNotRead() {
        for(long start:new long[]{0,-1,-5}) {
            var p=plan(source(start,1,n(1),mul(v(I),add(v(I),n(1)))));
            assertThrows(IllegalArgumentException.class,()->p.evaluate(BigInteger.TEN,Map.of(),B,CancellationToken.NONE));
            assertEquals(ExactRational.ZERO,p.evaluate(BigInteger.ZERO,Map.of(),B,CancellationToken.NONE));
        }
        var p=plan(source(-4,3,n(1),mul(v(I),add(v(I),n(3)))));
        assertNotNull(p.evaluate(BigInteger.TEN,Map.of(),B,CancellationToken.NONE)); // zero lies off this lattice
    }
    @Test void independentCheckerRejectsChangedSourcePrimitiveDenominatorAndUnsupportedFragments() {
        var s=source(1,1,n(1),mul(v(I),add(v(I),n(1)))); var p=plan(s);
        assertFalse(RationalTelescoping.verify(s,new RationalTelescoping.Plan(s,n(0),p.primitiveDenominator()),B,CancellationToken.NONE).verified());
        assertFalse(RationalTelescoping.verify(s,new RationalTelescoping.Plan(s,p.primitiveNumerator(),add(p.primitiveDenominator(),n(1))),B,CancellationToken.NONE).verified());
        assertFalse(RationalTelescoping.verify(source(2,1,n(1),s.denominator()),p,B,CancellationToken.NONE).verified());
        assertTrue(RationalTelescoping.search(source(1,1,n(1),add(mul(v(I),v(I)),n(1))),B,CancellationToken.NONE).plan().isEmpty());
        assertTrue(RationalTelescoping.search(s,B,()->true).plan().isEmpty());
        assertThrows(IllegalArgumentException.class,()->p.evaluate(BigInteger.ONE.negate(),Map.of(),B,CancellationToken.NONE));
    }
}
