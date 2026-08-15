package de.regelsuche.assumption;

import java.util.Objects;

/** Evidence emitted by one assumption evaluator. */
public record AssumptionEvaluationEvidence(
    String evaluatorId,
    String evaluatorRevision,
    AssumptionTruthValue result,
    AssumptionEvaluationDisposition disposition,
    String explanation,
    String evidenceReference
) {
    public AssumptionEvaluationEvidence {
        evaluatorId = requireText(evaluatorId, "evaluatorId");
        evaluatorRevision = requireText(evaluatorRevision, "evaluatorRevision");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(disposition, "disposition");
        explanation = explanation == null ? "" : explanation;
        evidenceReference = evidenceReference == null ? "" : evidenceReference;
        if (disposition != AssumptionEvaluationDisposition.EVALUATED
                && result != AssumptionTruthValue.UNKNOWN) {
            throw new IllegalArgumentException(
                "non-evaluated evidence must have UNKNOWN result");
        }
    }

    public static AssumptionEvaluationEvidence evaluated(
        String evaluatorId,
        String evaluatorRevision,
        AssumptionTruthValue result,
        String explanation,
        String evidenceReference
    ) {
        return new AssumptionEvaluationEvidence(
            evaluatorId,
            evaluatorRevision,
            result,
            AssumptionEvaluationDisposition.EVALUATED,
            explanation,
            evidenceReference
        );
    }

    public static AssumptionEvaluationEvidence inconclusive(
        String evaluatorId,
        String evaluatorRevision,
        AssumptionEvaluationDisposition disposition,
        String explanation,
        String evidenceReference
    ) {
        if (disposition == AssumptionEvaluationDisposition.EVALUATED) {
            throw new IllegalArgumentException(
                "use evaluated(...) for EVALUATED evidence");
        }
        return new AssumptionEvaluationEvidence(
            evaluatorId,
            evaluatorRevision,
            AssumptionTruthValue.UNKNOWN,
            disposition,
            explanation,
            evidenceReference
        );
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
