package de.regelsuche.sdk.optimization.power;

import de.regelsuche.sdk.optimization.*;
import java.math.BigInteger;
import java.util.Optional;

/** Proposes reuse only under explicit caller-proved domain and original-inverse obligations. */
public final class UnitInverseReuseOptimizer {
    public record SearchResult(Optional<UnitInverseReusePlan> plan,String reason,long work) { }
    public SearchResult search(UnitInverseReusePlan.Premises source,OptimizationBudget budget,CancellationToken cancellation) {
        var work=new PowerWork(budget,cancellation);
        try {
            UnitInverseReusePlan.checkPremises(source,work);
            work.charge(1L+(source.power().bitLength()+63)/64);
            var predecessor=FixedPowerOptimizer.synthesize(source.power().subtract(BigInteger.ONE),work);
            return new SearchResult(Optional.of(new UnitInverseReusePlan(source,predecessor)),"CANDIDATE",work.used);
        } catch(PowerWork.Unavailable stop) { return new SearchResult(Optional.empty(),stop.getMessage(),work.used); }
    }
}
