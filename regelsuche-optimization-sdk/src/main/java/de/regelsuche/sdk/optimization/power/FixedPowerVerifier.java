package de.regelsuche.sdk.optimization.power;

import de.regelsuche.sdk.optimization.*;
import java.math.BigInteger;
import java.util.Objects;

/** Replays exponent addition without invoking chain search. The result is conditional
 * on caller-proved total, associative multiplication with a two-sided identity in one domain.
 * Overflowing, throwing, nonassociative, or effectful operations do not satisfy that premise.
 */
public final class FixedPowerVerifier {
    public record Verification(boolean verified,String reason,long work) { }
    public Verification verify(BigInteger expectedExponent,FixedPowerPlan plan,boolean totalAssociativeMultiplicationWithIdentity,
            OptimizationBudget budget,CancellationToken cancellation) {
        var work=new PowerWork(budget,cancellation);
        try {
            check(expectedExponent,plan,totalAssociativeMultiplicationWithIdentity,work);
            return new Verification(true,"VERIFIED_CONDITIONAL_FIXED_POWER",work.used);
        } catch(PowerWork.Unavailable stop) { return new Verification(false,stop.getMessage(),work.used); }
    }
    static void check(BigInteger expected,FixedPowerPlan plan,boolean monoid,PowerWork work) {
        work.exponent(expected); Objects.requireNonNull(plan);
        if(!monoid) throw new PowerWork.Unavailable("TOTAL_ASSOCIATIVE_MULTIPLICATION_WITH_IDENTITY_REQUIRED");
        work.exponent(plan.exponent());
        if(!expected.equals(plan.exponent())) throw new PowerWork.Unavailable("POWER_SOURCE_MISMATCH");
        int count=plan.multiplications().size();
        if(count>FixedPowerPlan.MAX_STEPS || count>work.budget.maximumStates()) throw new PowerWork.Unavailable("STATE_LIMIT");
        work.charge(2L+count); // reserve the bounded replay array before allocation
        var exponents=new BigInteger[count+2]; exponents[0]=BigInteger.ZERO; exponents[1]=BigInteger.ONE;
        for(int i=0;i<count;i++) {
            work.charge(1);
            var step=plan.multiplications().get(i);
            if(step.left()<0 || step.right()<0 || step.left()>=i+2 || step.right()>=i+2)
                throw new PowerWork.Unavailable("POWER_FORWARD_OR_INVALID_REFERENCE");
            BigInteger left=exponents[step.left()],right=exponents[step.right()];
            work.charge(1L+(Math.max(left.bitLength(),right.bitLength())+63)/64);
            var result=left.add(right);
            if(result.bitLength()>FixedPowerPlan.MAX_EXPONENT_BITS) throw new PowerWork.Unavailable("POWER_EXPONENT_LIMIT");
            exponents[i+2]=result;
        }
        work.charge(1L+(expected.bitLength()+63)/64);
        if(plan.resultIndex()<0 || plan.resultIndex()>=exponents.length || !expected.equals(exponents[plan.resultIndex()]))
            throw new PowerWork.Unavailable("POWER_RESULT_MISMATCH");
    }
}
