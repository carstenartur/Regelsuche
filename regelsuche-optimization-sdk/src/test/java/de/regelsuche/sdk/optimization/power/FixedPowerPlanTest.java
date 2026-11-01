package de.regelsuche.sdk.optimization.power;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.sdk.optimization.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FixedPowerPlanTest {
    @Test void synthesizedChainsReplayForIdentityBaseAndLargerPowers() {
        var optimizer=new FixedPowerOptimizer(); var verifier=new FixedPowerVerifier();
        for(int exponent:new int[]{0,1,2,3,15,255,65537}) {
            var n=BigInteger.valueOf(exponent);
            var result=optimizer.search(n,OptimizationBudget.DEFAULT,CancellationToken.NONE);
            var plan=result.plan().orElseThrow();
            assertTrue(verifier.verify(n,plan,true,OptimizationBudget.DEFAULT,CancellationToken.NONE).verified());
            // Evaluate in a composite modular ring; the expected value is JDK's independent implementation.
            var values=new ArrayList<BigInteger>(List.of(BigInteger.ONE,BigInteger.valueOf(7)));
            for(var step:plan.multiplications()) values.add(values.get(step.left()).multiply(values.get(step.right())).mod(BigInteger.valueOf(15)));
            assertEquals(BigInteger.valueOf(7).modPow(n,BigInteger.valueOf(15)),values.get(plan.resultIndex()));
            if(exponent>3) assertTrue(plan.multiplications().size()<exponent-1);
        }
    }
    @Test void verifierRejectsTamperingAndMissingAlgebraPremise() {
        var verifier=new FixedPowerVerifier();
        var square=new FixedPowerPlan(BigInteger.TWO,List.of(new FixedPowerPlan.Multiplication(1,1)),2);
        assertTrue(verifier.verify(BigInteger.TWO,square,true,OptimizationBudget.DEFAULT,CancellationToken.NONE).verified());
        assertFalse(verifier.verify(BigInteger.TWO,square,false,OptimizationBudget.DEFAULT,CancellationToken.NONE).verified());
        assertFalse(verifier.verify(BigInteger.valueOf(3),square,true,OptimizationBudget.DEFAULT,CancellationToken.NONE).verified());
        var forwardReference=new FixedPowerPlan(BigInteger.TWO,List.of(new FixedPowerPlan.Multiplication(2,1)),2);
        assertFalse(verifier.verify(BigInteger.TWO,forwardReference,true,OptimizationBudget.DEFAULT,CancellationToken.NONE).verified());
        var wrongResult=new FixedPowerPlan(BigInteger.TWO,square.multiplications(),1);
        assertFalse(verifier.verify(BigInteger.TWO,wrongResult,true,OptimizationBudget.DEFAULT,CancellationToken.NONE).verified());
        var wrongStep=new FixedPowerPlan(BigInteger.TWO,List.of(new FixedPowerPlan.Multiplication(0,1)),2);
        assertFalse(verifier.verify(BigInteger.TWO,wrongStep,true,OptimizationBudget.DEFAULT,CancellationToken.NONE).verified());
    }
    @Test void constructorRejectsOversizedCollectionBeforeCopyingOrInspectingElements() {
        List<FixedPowerPlan.Multiplication> oversized=new AbstractList<>() {
            @Override public int size() { return 8193; }
            @Override public FixedPowerPlan.Multiplication get(int index) { throw new AssertionError("unbounded copy"); }
            @Override public Object[] toArray() { throw new AssertionError("unbounded copy"); }
        };
        assertThrows(IllegalArgumentException.class,() -> new FixedPowerPlan(BigInteger.ONE,oversized,1));
    }
    @Test void lyingCollectionCannotDefeatTheStepCapByAdvertisingSmallSize() {
        var reads=new AtomicInteger();
        List<FixedPowerPlan.Multiplication> misleading=new AbstractList<>() {
            @Override public int size() { return 1; }
            @Override public FixedPowerPlan.Multiplication get(int index) { throw new AssertionError("indexed copy"); }
            @Override public Object[] toArray() { throw new AssertionError("unbounded copy"); }
            @Override public Iterator<FixedPowerPlan.Multiplication> iterator() { return new Iterator<>() {
                public boolean hasNext() { return true; }
                public FixedPowerPlan.Multiplication next() { reads.incrementAndGet(); return new FixedPowerPlan.Multiplication(1,1); }
            }; }
        };
        assertThrows(IllegalArgumentException.class,() -> new FixedPowerPlan(BigInteger.ONE,misleading,1));
        assertTrue(reads.get()<=8192);
    }
    @Test void searchAndReplayAreCancellableAndWorkAndStateBounded() {
        var optimizer=new FixedPowerOptimizer(); var verifier=new FixedPowerVerifier();
        var square=optimizer.search(BigInteger.TWO,OptimizationBudget.DEFAULT,CancellationToken.NONE).plan().orElseThrow();
        assertEquals("CANCELLED",optimizer.search(BigInteger.TWO,OptimizationBudget.DEFAULT,() -> true).reason());
        assertEquals("CANCELLED",verifier.verify(BigInteger.TWO,square,true,OptimizationBudget.DEFAULT,() -> true).reason());
        var tiny=new OptimizationBudget(1,128,1,5000);
        assertTrue(optimizer.search(BigInteger.valueOf(255),tiny,CancellationToken.NONE).plan().isEmpty());
        assertFalse(verifier.verify(BigInteger.TWO,square,true,tiny,CancellationToken.NONE).verified());
        assertTrue(optimizer.search(BigInteger.valueOf(255),new OptimizationBudget(10000,1,1,5000),CancellationToken.NONE).plan().isEmpty());
        assertTrue(optimizer.search(BigInteger.ONE.shiftLeft(4096),OptimizationBudget.DEFAULT,CancellationToken.NONE).plan().isEmpty());
    }
    @Test void fullTransportCapCanBeVerifiedAndCancellationIsCheckedDuringReplay() {
        var plan=new FixedPowerPlan(BigInteger.ZERO,Collections.nCopies(8192,new FixedPowerPlan.Multiplication(0,0)),8193);
        var budget=new OptimizationBudget(2_000_000,8192,1,5000);
        var verifier=new FixedPowerVerifier();
        assertTrue(verifier.verify(BigInteger.ZERO,plan,true,budget,CancellationToken.NONE).verified());
        var checkpoints=new AtomicInteger();
        assertEquals("CANCELLED",verifier.verify(BigInteger.ZERO,plan,true,budget,() -> checkpoints.incrementAndGet()>20).reason());
    }
    @Test void deadlineAppliesEvenWhenTheWorkAllowanceRemains() {
        var budget=new OptimizationBudget(2_000_000,128,1,1);
        CancellationToken elapsed=() -> {
            long start=System.nanoTime();
            while(System.nanoTime()-start<2_000_000) Thread.onSpinWait();
            return false;
        };
        assertEquals("TIME_LIMIT",new FixedPowerOptimizer().search(BigInteger.TWO,budget,elapsed).reason());
    }
}
