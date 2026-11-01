package de.regelsuche.sdk.optimization.power;

import de.regelsuche.sdk.optimization.*;
import java.math.BigInteger;
import java.util.Objects;

/** Independent conditional monoid theorem. Powers of x commute with x; if x^k has a
 * two-sided inverse y, x commutes with y as well, so x^(k-1)y is a two-sided inverse of x.
 * There is no commutativity or primality assumption about the surrounding algebra.
 */
public final class UnitInverseReuseVerifier {
    public record Verification(boolean verified,String reason,long work) { }
    public Verification verify(UnitInverseReusePlan.Premises expected,UnitInverseReusePlan plan,
            OptimizationBudget budget,CancellationToken cancellation) {
        var work=new PowerWork(budget,cancellation);
        try {
            UnitInverseReusePlan.checkPremises(expected,work); Objects.requireNonNull(plan);
            UnitInverseReusePlan.checkPremises(plan.source(),work);
            // Validation scans were paid; separately reserve full bounded field comparisons.
            var actual=plan.source();
            work.charge(5L+expected.algebraId().length()+expected.domainId().length()+expected.baseBinding().length()
                +expected.poweredBinding().length()+expected.knownInverseBinding().length()+(expected.power().bitLength()+63)/64);
            if(!expected.equals(actual)) throw new PowerWork.Unavailable("INVERSE_SOURCE_OR_DOMAIN_MISMATCH");
            work.charge(1L+(expected.power().bitLength()+63)/64);
            FixedPowerVerifier.check(expected.power().subtract(BigInteger.ONE),plan.predecessorPower(),true,work);
            return new Verification(true,"VERIFIED_CONDITIONAL_UNIT_INVERSE_REUSE",work.used);
        } catch(PowerWork.Unavailable stop) { return new Verification(false,stop.getMessage(),work.used); }
    }
}
