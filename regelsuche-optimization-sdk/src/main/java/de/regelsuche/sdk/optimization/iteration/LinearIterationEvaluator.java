package de.regelsuche.sdk.optimization.iteration;

import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import de.regelsuche.symbol.SymbolId;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** SDK entry point for checked binary powering of a linear iteration. */
public final class LinearIterationEvaluator {
    public record Evaluation(Optional<List<BigInteger>> values, String reason, long work) {
        public Evaluation {
            values = values.map(List::copyOf);
        }
    }

    public Evaluation evaluate(LinearIteration source, LinearIterationOptimizer.Candidate candidate,
            Map<SymbolId, BigInteger> bindings, OptimizationBudget budget, CancellationToken cancellation) {
        var evaluator = new de.regelsuche.math.algorithms.iteration.LinearIterationEvaluator();
        var evaluated = evaluator.evaluate(source.mathSource(), LinearIterationAdapter.candidate(candidate),
            bindings, LinearIterationAdapter.limits(budget), budget.maximumStates(), cancellation::isCancelled);
        return new Evaluation(evaluated.values(), evaluated.reason(), evaluated.work());
    }
}
