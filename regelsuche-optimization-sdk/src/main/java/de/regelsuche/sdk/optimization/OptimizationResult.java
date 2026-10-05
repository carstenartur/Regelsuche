package de.regelsuche.sdk.optimization;

import de.regelsuche.search.program.*;
import java.util.Map;
import java.util.Objects;

public sealed interface OptimizationResult {
    enum SearchCompletion { EXHAUSTED_BOUNDED_SPACE, IMPROVEMENT_FOUND }
    record CostAssessment(long sourceScore, long candidateScore, long checkWork, int fallbackOperations,
            PreparedJointComputation.Cost sourceCost, PreparedJointComputation.Cost candidateCost,
            boolean estimatedRuntimeImprovement) {}
    record Candidate(JointComputationPlan plan, PreparedJointComputation prepared, VerificationEvidence evidence,
            RuntimeObligations obligations, CostAssessment cost, SearchCompletion searchCompletion, long work) implements OptimizationResult {
        public Candidate {
            var trusted = ComputationOptimizer.prepare(Objects.requireNonNull(plan));
            if (prepared == null || !trusted.nodes().equals(prepared.nodes())
                    || !trusted.outputBindings().equals(prepared.outputBindings())
                    || !trusted.cost().equals(prepared.cost()))
                throw new IllegalArgumentException("PREPARED_PLAN_BINDING_DIFFERS");
            prepared = trusted;
            Objects.requireNonNull(evidence); Objects.requireNonNull(obligations);
            Objects.requireNonNull(cost); Objects.requireNonNull(searchCompletion);
            if (work < 0) throw new IllegalArgumentException("NEGATIVE_OPTIMIZATION_WORK");
        }
    }
    record NoImprovement(String diagnostic, SearchCompletion searchCompletion, long work) implements OptimizationResult {}
    record Unsupported(String diagnostic) implements OptimizationResult {}
    record BudgetExceeded(String diagnostic, long work) implements OptimizationResult {}
    record Cancelled(String diagnostic) implements OptimizationResult {}
    record Refuted(String diagnostic, Map<String, Object> counterexample) implements OptimizationResult {
        public Refuted { counterexample = Map.copyOf(counterexample); }
    }
    record Inconclusive(String diagnostic) implements OptimizationResult {}
}
