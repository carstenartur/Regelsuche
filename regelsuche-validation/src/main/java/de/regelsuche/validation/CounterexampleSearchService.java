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
        long randomSeed,
        boolean includeComplexAssignments,
        boolean includeRationalAssignments,
        int maxMatrixDimension,
        long timeoutMillis
    ) {
        public CounterexampleBudget {
            if (numericRandomSamples < 0) {
                throw new IllegalArgumentException("numericRandomSamples must be >= 0");
            }
            if (maxMatrixDimension < 0) {
                throw new IllegalArgumentException("maxMatrixDimension must be >= 0");
            }
            if (timeoutMillis < 0) {
                throw new IllegalArgumentException("timeoutMillis must be >= 0");
            }
        }

        public CounterexampleBudget(
            int numericRandomSamples,
            boolean includeEdgeCases,
            boolean includeMatrixAssignments,
            long randomSeed
        ) {
            this(numericRandomSamples, includeEdgeCases, includeMatrixAssignments, randomSeed, false);
        }

        public CounterexampleBudget(
            int numericRandomSamples,
            boolean includeEdgeCases,
            boolean includeMatrixAssignments,
            long randomSeed,
            boolean includeComplexAssignments
        ) {
            this(numericRandomSamples, includeEdgeCases, includeMatrixAssignments, randomSeed,
                includeComplexAssignments, false, includeMatrixAssignments ? 2 : 0, 0L);
        }

        public static CounterexampleBudget defaultBudget() {
            return new CounterexampleBudget(16, true, true, 1L, false, true, 2, 0L);
        }

        public int maxNumericSamples() {
            return numericRandomSamples;
        }

        public boolean includeBoundaryValues() {
            return includeEdgeCases;
        }

        public boolean includeMatrices() {
            return includeMatrixAssignments;
        }

        public boolean includeComplex() {
            return includeComplexAssignments;
        }

        public boolean includeRationals() {
            return includeRationalAssignments;
        }

        public List<String> sourceFlags() {
            List<String> flags = new java.util.ArrayList<>();
            if (includeEdgeCases) {
                flags.add("boundary-values");
            }
            if (includeRationalAssignments) {
                flags.add("rational-samples");
            }
            if (numericRandomSamples > 0) {
                flags.add("numeric-random");
            }
            if (includeMatrixAssignments) {
                flags.add("matrix-non-commutative");
            }
            if (includeComplexAssignments) {
                flags.add("complex-samples");
            }
            return List.copyOf(flags);
        }
    }

    /**
     * Full result of a counterexample search.
     */
    record CounterexampleSearchResult(
        Status status,
        Optional<Counterexample> counterexample,
        List<String> inferredAssumptions,
        List<String> attemptedSources,
        String explanation
    ) {
        public CounterexampleSearchResult(Optional<Counterexample> counterexample, List<String> inferredAssumptions, List<String> attemptedSources) {
            this(deriveStatus(counterexample, attemptedSources), counterexample, inferredAssumptions, attemptedSources,
                deriveExplanation(deriveStatus(counterexample, attemptedSources), attemptedSources));
        }

        public CounterexampleSearchResult(
            Status status,
            Optional<Counterexample> counterexample,
            List<String> inferredAssumptions,
            List<String> attemptedSources
        ) {
            this(status, counterexample, inferredAssumptions, attemptedSources,
                deriveExplanation(status == null ? deriveStatus(counterexample, attemptedSources) : status, attemptedSources));
        }

        public CounterexampleSearchResult {
            status = status == null ? deriveStatus(counterexample, attemptedSources) : status;
            counterexample = counterexample == null ? Optional.empty() : counterexample;
            inferredAssumptions = inferredAssumptions == null ? List.of() : List.copyOf(inferredAssumptions);
            attemptedSources = attemptedSources == null ? List.of() : List.copyOf(attemptedSources);
            explanation = explanation == null || explanation.isBlank()
                ? deriveExplanation(status, attemptedSources)
                : explanation;
        }

        public static CounterexampleSearchResult noCounterexample() {
            return new CounterexampleSearchResult(Status.NO_COUNTEREXAMPLE_FOUND, Optional.empty(), List.of(), List.of());
        }

        public static CounterexampleSearchResult inconclusive() {
            return new CounterexampleSearchResult(Status.INCONCLUSIVE, Optional.empty(), List.of(), List.of());
        }

        public static CounterexampleSearchResult inconclusive(String explanation) {
            return new CounterexampleSearchResult(Status.INCONCLUSIVE, Optional.empty(), List.of(), List.of(), explanation);
        }

        public static CounterexampleSearchResult counterexampleFound(
            Counterexample counterexample,
            List<String> inferredAssumptions,
            List<String> attemptedSources
        ) {
            return new CounterexampleSearchResult(
                Status.COUNTEREXAMPLE_FOUND,
                Optional.of(counterexample),
                inferredAssumptions,
                attemptedSources,
                "refuting sample found"
            );
        }

        public static CounterexampleSearchResult noCounterexampleFound(
            List<String> inferredAssumptions,
            List<String> attemptedSources
        ) {
            return new CounterexampleSearchResult(
                Status.NO_COUNTEREXAMPLE_FOUND,
                Optional.empty(),
                inferredAssumptions,
                attemptedSources,
                deriveExplanation(Status.NO_COUNTEREXAMPLE_FOUND, attemptedSources)
            );
        }

        private static Status deriveStatus(Optional<Counterexample> counterexample, List<String> attemptedSources) {
            if (counterexample != null && counterexample.isPresent()) {
                return Status.COUNTEREXAMPLE_FOUND;
            }
            if (attemptedSources == null || attemptedSources.isEmpty()) {
                return Status.INCONCLUSIVE;
            }
            return Status.NO_COUNTEREXAMPLE_FOUND;
        }

        private static String deriveExplanation(Status status, List<String> attemptedSources) {
            return switch (status) {
                case COUNTEREXAMPLE_FOUND -> "refuting sample found";
                case NO_COUNTEREXAMPLE_FOUND -> "no refutation found within configured budget";
                case INCONCLUSIVE -> attemptedSources == null || attemptedSources.isEmpty()
                    ? "no executable counterexample source was available"
                    : "counterexample search ended without a reliable verdict";
            };
        }
    }

    enum Status {
        NO_COUNTEREXAMPLE_FOUND,
        COUNTEREXAMPLE_FOUND,
        INCONCLUSIVE
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
