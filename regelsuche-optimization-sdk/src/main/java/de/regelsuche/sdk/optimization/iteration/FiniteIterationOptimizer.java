package de.regelsuche.sdk.optimization.iteration;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.iteration.FiniteIteration;
import de.regelsuche.math.algorithms.iteration.FiniteIterationReduction;
import de.regelsuche.sdk.optimization.CancellationToken;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;

/** SDK facade for bounded candidate synthesis. */
public final class FiniteIterationOptimizer {
    /** Source-bound rational antidifference. Public construction permits independent transport/checking. */
    public record Certificate(FiniteIteration source, Expr antidifference) {
        public Certificate { Objects.requireNonNull(source); Objects.requireNonNull(antidifference); }
    }
    /** Arithmetic-operation estimates only: no loop-control, loads or compiler/runtime cost claims. */
    public record Cost(BigInteger originalOperations, long resultOperations) {
        public Cost {
            Objects.requireNonNull(originalOperations);
            if(originalOperations.signum()<0 || resultOperations<0) throw new IllegalArgumentException("NEGATIVE_COST");
        }
    }
    /** Search work and cost are informational; the checker recomputes its own values. */
    public record Candidate(Expr result, Certificate certificate, long work, Cost cost) {
        public Candidate { Objects.requireNonNull(result); Objects.requireNonNull(certificate); Objects.requireNonNull(cost); }
    }
    public record SearchResult(Optional<Candidate> candidate, String reason, long work) {
        public SearchResult { Objects.requireNonNull(candidate); Objects.requireNonNull(reason); }
    }
    public SearchResult search(FiniteIteration source, OptimizationBudget budget, CancellationToken cancellation) {
        var proposal = FiniteIterationReduction.synthesize(source, SeriesSupport.limits(budget), cancellation::isCancelled);
        var candidate = proposal.plan().map(plan -> new Candidate(plan.result(),
            new Certificate(plan.certificate().source(), plan.certificate().antidifference()),
            proposal.work(), sdkCost(plan.cost())));
        return new SearchResult(candidate, proposal.reason(), proposal.work());
    }

    static Cost sdkCost(FiniteIterationReduction.Cost cost) {
        return new Cost(cost.originalOperations(), cost.resultOperations());
    }
}
