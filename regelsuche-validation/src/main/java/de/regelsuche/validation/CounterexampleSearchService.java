package de.regelsuche.validation;

import java.util.List;
import java.util.Optional;

/**
 * Stable port for counterexample search over a candidate equivalence.
 *
 * <p>Introduced as part of Teil 0 of the Discovery Epic (issue #41,
 * "Interfaces zuerst"): downstream validation, mining and proof features
 * depend on this abstraction instead of a concrete SMT/random-search
 * backend so the mathematical core remains infrastructure-agnostic.
 *
 * <p>{@link RewriteRuleValidationService} provides the existing random-
 * sampling based counterexample search; future SMT/proof-based engines
 * implement the same port.
 */
public interface CounterexampleSearchService {

    /**
     * Try to find a counterexample for a full hypothesis under a bounded budget.
     */
    CounterexampleSearchResult search(HypothesisInput hypothesis, CounterexampleBudget budget);

    /**
     * Backwards-compatible convenience API for callers that only have two expressions.
     */
    default Optional<Counterexample> search(String leftExpression, String rightExpression) {
        HypothesisInput hypothesis = new HypothesisInput(
            "",
            leftExpression,
            rightExpression,
            List.of()
        );
        return search(hypothesis, CounterexampleBudget.defaultBudget()).counterexample();
    }

    /**
     * Lightweight hypothesis payload consumed by counterexample search backends.
     */
    record HypothesisInput(
        String id,
        String leftExpression,
        String rightExpression,
        List<String> assumptions
    ) {
        public HypothesisInput {
            id = id == null ? "" : id;
            if (leftExpression == null || leftExpression.isBlank()) {
                throw new IllegalArgumentException("leftExpression must not be blank");
            }
            if (rightExpression == null || rightExpression.isBlank()) {
                throw new IllegalArgumentException("rightExpression must not be blank");
            }
            assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        }
    }

    /**
     * Search budget. Keeps randomness deterministic by carrying the seed explicitly.
     */
    record CounterexampleBudget(
        int numericRandomSamples,
        boolean includeEdgeCases,
        boolean includeMatrixAssignments,
        long randomSeed
    ) {
        public CounterexampleBudget {
            if (numericRandomSamples < 0) {
                throw new IllegalArgumentException("numericRandomSamples must be >= 0");
            }
        }

        public static CounterexampleBudget defaultBudget() {
            return new CounterexampleBudget(16, true, true, 1L);
        }
    }

    /**
     * Full result of a counterexample search.
     */
    record CounterexampleSearchResult(
        Optional<Counterexample> counterexample,
        List<String> inferredAssumptions,
        List<String> attemptedSources
    ) {
        public CounterexampleSearchResult {
            counterexample = counterexample == null ? Optional.empty() : counterexample;
            inferredAssumptions = inferredAssumptions == null ? List.of() : List.copyOf(inferredAssumptions);
            attemptedSources = attemptedSources == null ? List.of() : List.copyOf(attemptedSources);
        }

        public static CounterexampleSearchResult noCounterexample() {
            return new CounterexampleSearchResult(Optional.empty(), List.of(), List.of());
        }
    }

    /**
     * A concrete counterexample: a variable assignment plus the resulting
     * inequality (e.g. {@code "x=2 ⇒ left=4, right=5"}).
     *
     * @param assignments variable bindings causing the inequality (e.g.
     *     {@code "x=2"}, {@code "y=-1"})
     * @param leftValue evaluated value of the left expression
     * @param rightValue evaluated value of the right expression
     */
    record Counterexample(List<String> assignments, String leftValue, String rightValue) {
        public Counterexample {
            assignments = List.copyOf(assignments);
        }
    }
}
