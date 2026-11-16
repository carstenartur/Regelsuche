package de.regelsuche.sdk.optimization.iteration;

import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import de.regelsuche.symbol.SymbolId;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/** SDK entry point for independent checks of linear transitions and their outputs. */
public final class LinearIterationVerifier {
    public record Verification(boolean verified, String reason, long work) {}

    public Verification verify(LinearIteration source, LinearIterationOptimizer.Candidate candidate,
            OptimizationBudget budget, CancellationToken cancellation) {
        var verifier = new de.regelsuche.math.algorithms.iteration.LinearIterationVerifier();
        var result = verifier.verify(source.mathSource(), LinearIterationAdapter.candidate(candidate),
            LinearIterationAdapter.limits(budget), budget.maximumStates(), cancellation::isCancelled);
        return new Verification(result.verified(), result.reason(), result.work());
    }

    public Verification verifyResult(LinearIteration source, LinearIterationOptimizer.Candidate candidate,
            Map<SymbolId, BigInteger> bindings, List<BigInteger> emitted, OptimizationBudget budget,
            CancellationToken cancellation) {
        var verifier = new de.regelsuche.math.algorithms.iteration.LinearIterationVerifier();
        var result = verifier.verifyResult(source.mathSource(), LinearIterationAdapter.candidate(candidate),
            bindings, emitted, LinearIterationAdapter.limits(budget), budget.maximumStates(), cancellation::isCancelled);
        return new Verification(result.verified(), result.reason(), result.work());
    }
}
