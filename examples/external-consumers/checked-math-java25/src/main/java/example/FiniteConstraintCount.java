package example;

import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintCounter;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import de.regelsuche.sdk.optimization.combinatorics.FiniteConstraintSdk;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/** Requires the current 0.5.4-SNAPSHOT source SDK, not the published 0.5.3 binary. */
class FiniteConstraintCount {
    public static void main(String[] args) {
        var budget = new OptimizationBudget(100_000, 1_000, 1, 5_000);
        var table = List.of(BigInteger.ONE, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO);
        var factors = List.of(new FiniteConstraintCounter.Factor(List.of(0, 1), table),
            new FiniteConstraintCounter.Factor(List.of(1, 2), table));
        for (var condition : List.of(Map.<Integer, Boolean>of(), Map.of(1, true), Map.of(1, false))) {
            var problem = new FiniteConstraintCounter.Problem(3, factors, condition);
            var result = FiniteConstraintSdk.count(problem, budget, CancellationToken.NONE);
            var certificate = result.certificate().orElseThrow(() -> new IllegalStateException(result.reason()));
            if (!FiniteConstraintSdk.verify(problem, certificate, budget, CancellationToken.NONE).valid())
                throw new IllegalStateException("Unverified count");
            System.out.println("condition=" + condition + ", count=" + certificate.total());
        }
    }
}
