package de.regelsuche.sdk.optimization.iteration;

import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import java.util.Objects;
import java.util.Optional;

/** SDK entry point for the bounded, division-free mathematical transition search. */
public final class LinearIterationOptimizer {
    public record Candidate(LinearIteration source, LinearTransition transition, long work) {
        public Candidate {
            Objects.requireNonNull(source);
            Objects.requireNonNull(transition);
        }
    }

    public record SearchResult(Optional<Candidate> candidate, String reason, long work) {}

    public SearchResult search(LinearIteration source, OptimizationBudget budget, CancellationToken cancellation) {
        var optimizer = new de.regelsuche.math.algorithms.iteration.LinearIterationOptimizer();
        var result = optimizer.search(source.mathSource(), LinearIterationAdapter.limits(budget),
            budget.maximumStates(), cancellation::isCancelled);
        var candidate = result.candidate().map(value ->
            new Candidate(source, LinearTransition.fromMath(value.transition()), value.work()));
        return new SearchResult(candidate, result.reason(), result.work());
    }
}
