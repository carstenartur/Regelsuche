package de.regelsuche.sdk.optimization.power;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.sdk.optimization.*;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class UnitInverseReusePlanTest {
    private static UnitInverseReusePlan.Premises source(String base,boolean originalInverse) {
        return new UnitInverseReusePlan.Premises(BigInteger.valueOf(3),"ring","residue-15",base,"cubed","inverse-cubed",true,originalInverse);
    }
    @Test void retainedTwoSidedInverseYieldsInverseWithNoPrimeAssumption() {
        var source=source("x",true);
        var plan=new UnitInverseReuseOptimizer().search(source,OptimizationBudget.DEFAULT,CancellationToken.NONE).plan().orElseThrow();
        var verification=new UnitInverseReuseVerifier().verify(source,plan,OptimizationBudget.DEFAULT,CancellationToken.NONE);
        assertTrue(verification.verified(),verification.reason());
        assertEquals(BigInteger.TWO,plan.predecessorPower().exponent());
        // x=2, x^3=8 and 8^-1=2 in Z/15Z: x^2 * 2 = 8 = 2^-1.
        assertEquals(BigInteger.valueOf(8),BigInteger.valueOf(4).multiply(BigInteger.TWO).mod(BigInteger.valueOf(15)));
    }
    @Test void missingSuccessfulInverseDifferentDomainAndTamperedPowerAreRejected() {
        var source=source("x",true); var optimizer=new UnitInverseReuseOptimizer(); var verifier=new UnitInverseReuseVerifier();
        var plan=optimizer.search(source,OptimizationBudget.DEFAULT,CancellationToken.NONE).plan().orElseThrow();
        assertTrue(optimizer.search(source("x",false),OptimizationBudget.DEFAULT,CancellationToken.NONE).plan().isEmpty());
        var other=new UnitInverseReusePlan.Premises(source.power(),source.algebraId(),"different-domain",source.baseBinding(),source.poweredBinding(),source.knownInverseBinding(),true,true);
        assertFalse(verifier.verify(other,plan,OptimizationBudget.DEFAULT,CancellationToken.NONE).verified());
        var wrong=new FixedPowerOptimizer().search(BigInteger.valueOf(3),OptimizationBudget.DEFAULT,CancellationToken.NONE).plan().orElseThrow();
        assertFalse(verifier.verify(source,new UnitInverseReusePlan(source,wrong),OptimizationBudget.DEFAULT,CancellationToken.NONE).verified());
        var noMonoid=new UnitInverseReusePlan.Premises(source.power(),source.algebraId(),source.domainId(),source.baseBinding(),source.poweredBinding(),source.knownInverseBinding(),false,true);
        assertFalse(verifier.verify(noMonoid,new UnitInverseReusePlan(noMonoid,plan.predecessorPower()),OptimizationBudget.DEFAULT,CancellationToken.NONE).verified());
    }
    @Test void bindingLengthIsCheckedBeforeBlankScanningAndBlankScanningIsBudgetedAndCancellable() {
        assertThrows(IllegalArgumentException.class,() -> source(" ".repeat(4097),true));
        var blank=source(" ".repeat(4096),true); var optimizer=new UnitInverseReuseOptimizer();
        assertEquals("CANCELLED",optimizer.search(blank,OptimizationBudget.DEFAULT,() -> true).reason());
        assertEquals("WORK_LIMIT",optimizer.search(blank,new OptimizationBudget(100,128,1,5000),CancellationToken.NONE).reason());
        assertTrue(optimizer.search(blank,OptimizationBudget.DEFAULT,CancellationToken.NONE).plan().isEmpty());
        var plan=new UnitInverseReusePlan(blank,new FixedPowerPlan(BigInteger.TWO,java.util.List.of(new FixedPowerPlan.Multiplication(1,1)),2));
        assertEquals("CANCELLED",new UnitInverseReuseVerifier().verify(blank,plan,OptimizationBudget.DEFAULT,() -> true).reason());
        assertEquals("WORK_LIMIT",new UnitInverseReuseVerifier().verify(blank,plan,new OptimizationBudget(100,128,1,5000),CancellationToken.NONE).reason());
        var valid=source("x".repeat(4096),true);
        assertTrue(optimizer.search(valid,OptimizationBudget.DEFAULT,CancellationToken.NONE).plan().isPresent());
    }
}
