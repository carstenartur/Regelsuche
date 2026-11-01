package de.regelsuche.sdk.optimization.power;

import de.regelsuche.sdk.optimization.*;
import java.math.BigInteger;
import java.util.*;

/** Bounded binary-chain synthesis. Search proposes a schedule without assuming an algebra. */
public final class FixedPowerOptimizer {
    public record SearchResult(Optional<FixedPowerPlan> plan,String reason,long work) { }
    public SearchResult search(BigInteger exponent,OptimizationBudget budget,CancellationToken cancellation) {
        var work=new PowerWork(budget,cancellation);
        try { return new SearchResult(Optional.of(synthesize(exponent,work)),"CANDIDATE",work.used); }
        catch(PowerWork.Unavailable stop) { return new SearchResult(Optional.empty(),stop.getMessage(),work.used); }
    }
    static FixedPowerPlan synthesize(BigInteger exponent,PowerWork work) {
        work.exponent(exponent);
        var steps=new ArrayList<FixedPowerPlan.Multiplication>();
        int result=exponent.signum()==0 ? 0 : 1;
        for(int bit=exponent.bitLength()-2;bit>=0;bit--) {
            result=append(steps,result,result,work);
            work.charge(1);
            if(exponent.testBit(bit)) result=append(steps,result,1,work);
        }
        work.charge(1L+steps.size()); // bounded constructor transport is included in this request's debit
        return new FixedPowerPlan(exponent,steps,result);
    }
    private static int append(List<FixedPowerPlan.Multiplication> steps,int left,int right,PowerWork work) {
        work.charge(1);
        if(steps.size()>=FixedPowerPlan.MAX_STEPS || steps.size()>=work.budget.maximumStates()) throw new PowerWork.Unavailable("STATE_LIMIT");
        steps.add(new FixedPowerPlan.Multiplication(left,right));
        return steps.size()+1;
    }
}
