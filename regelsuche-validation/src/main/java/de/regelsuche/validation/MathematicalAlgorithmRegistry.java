package de.regelsuche.validation;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;

public interface MathematicalAlgorithmRegistry {
    String POLYNOMIAL_EQUIVALENCE = "polynomialEquivalence";
    String GROEBNER_BASIS = "groebnerBasis";
    String JAS_BACKEND = "jasBackend";
    String SINGULAR_BACKEND = "singularBackend";
    String KNUTH_BENDIX = "knuthBendix";
    String CRITICAL_PAIRS = "criticalPairs";
    String PSLQ = "pslq";
    String NUMERIC_RELATION_SEARCH = "numericRelationSearch";

    Collection<AlgorithmDescriptor> algorithms();

    Optional<AlgorithmDescriptor> find(String algorithmId);

    default boolean isEnabled(String algorithmId) {
        return find(algorithmId).map(AlgorithmDescriptor::enabled).orElse(false);
    }

    record AlgorithmDescriptor(
        String id,
        String capability,
        boolean enabled,
        AlgorithmBudget budget,
        ProofSemantics proofSemantics,
        EnumSet<ResultType> resultTypes
    ) {
        public AlgorithmDescriptor {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("id must not be blank");
            }
            if (capability == null || capability.isBlank()) {
                throw new IllegalArgumentException("capability must not be blank");
            }
            budget = budget == null ? AlgorithmBudget.unbounded() : budget;
            proofSemantics = proofSemantics == null ? ProofSemantics.UNKNOWN : proofSemantics;
            resultTypes = resultTypes == null || resultTypes.isEmpty() ? EnumSet.of(ResultType.DIAGNOSTIC) : EnumSet.copyOf(resultTypes);
        }
    }

    record AlgorithmBudget(
        int maxSteps,
        int maxStates,
        int maxCoefficient,
        double tolerance,
        int maxTerms,
        int maxDegree,
        int maxVariables
    ) {
        public AlgorithmBudget(int maxSteps, int maxStates, int maxCoefficient, double tolerance) {
            this(maxSteps, maxStates, maxCoefficient, tolerance, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        }

        public static AlgorithmBudget unbounded() {
            return new AlgorithmBudget(
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                0.0,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE
            );
        }

        public static AlgorithmBudget bounded(int maxSteps, int maxStates, int maxCoefficient, double tolerance) {
            return new AlgorithmBudget(maxSteps, maxStates, maxCoefficient, tolerance);
        }

        public static AlgorithmBudget bounded(
            int maxSteps,
            int maxStates,
            int maxCoefficient,
            double tolerance,
            int maxTerms,
            int maxDegree,
            int maxVariables
        ) {
            return new AlgorithmBudget(maxSteps, maxStates, maxCoefficient, tolerance, maxTerms, maxDegree, maxVariables);
        }
    }

    enum ProofSemantics {
        PROOF_FOR_SUPPORTED_DOMAIN,
        HYPOTHESIS_ONLY,
        DIAGNOSTIC_ONLY,
        UNKNOWN
    }

    enum ResultType {
        PROOF,
        REFUTATION,
        HYPOTHESIS,
        DIAGNOSTIC
    }

    enum ExecutionStatus {
        SUCCESS,
        UNKNOWN,
        UNAVAILABLE,
        DISABLED,
        BUDGET_EXHAUSTED
    }

    record AlgorithmExecutionResult(ExecutionStatus status, ResultType resultType, String detail, Map<String, Object> payload) {
        public AlgorithmExecutionResult {
            payload = payload == null ? Map.of() : Map.copyOf(payload);
            detail = detail == null ? "" : detail;
        }

        public static AlgorithmExecutionResult disabled(String detail) {
            return new AlgorithmExecutionResult(ExecutionStatus.DISABLED, ResultType.DIAGNOSTIC, detail, Map.of());
        }

        public static AlgorithmExecutionResult unknown(String detail) {
            return new AlgorithmExecutionResult(ExecutionStatus.UNKNOWN, ResultType.DIAGNOSTIC, detail, Map.of());
        }

        public static AlgorithmExecutionResult unavailable(String detail) {
            return new AlgorithmExecutionResult(ExecutionStatus.UNAVAILABLE, ResultType.DIAGNOSTIC, detail, Map.of());
        }
    }
}
