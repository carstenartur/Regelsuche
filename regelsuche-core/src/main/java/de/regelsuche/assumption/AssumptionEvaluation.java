package de.regelsuche.assumption;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Deterministic aggregate result for one required assumption. */
public record AssumptionEvaluation(
    Assumption requiredAssumption,
    AssumptionSignature contextSignature,
    String evaluatorProfileHash,
    AssumptionTruthValue result,
    boolean conflicting,
    List<AssumptionEvaluationEvidence> evidence
) {
    public AssumptionEvaluation {
        Objects.requireNonNull(requiredAssumption, "requiredAssumption");
        Objects.requireNonNull(contextSignature, "contextSignature");
        evaluatorProfileHash = requireHash(evaluatorProfileHash);
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(evidence, "evidence");
        evidence = evidence.stream()
            .map(item -> Objects.requireNonNull(item, "evidence item"))
            .sorted(Comparator
                .comparing(AssumptionEvaluationEvidence::evaluatorId)
                .thenComparing(
                    AssumptionEvaluationEvidence::evaluatorRevision))
            .toList();
        rejectDuplicateEvaluatorIds(evidence);
        Aggregate aggregate = aggregate(evidence);
        if (result != aggregate.result()) {
            throw new IllegalArgumentException(
                "result does not match the evaluator evidence");
        }
        if (conflicting != aggregate.conflicting()) {
            throw new IllegalArgumentException(
                "conflicting does not match the evaluator evidence");
        }
    }

    public static AssumptionEvaluation from(
        Assumption requiredAssumption,
        AssumptionSignature contextSignature,
        String evaluatorProfileHash,
        List<AssumptionEvaluationEvidence> evidence
    ) {
        Objects.requireNonNull(evidence, "evidence");
        List<AssumptionEvaluationEvidence> snapshot = evidence.stream()
            .map(item -> Objects.requireNonNull(item, "evidence item"))
            .toList();
        Aggregate aggregate = aggregate(snapshot);
        return new AssumptionEvaluation(
            requiredAssumption,
            contextSignature,
            evaluatorProfileHash,
            aggregate.result(),
            aggregate.conflicting(),
            snapshot
        );
    }

    public boolean isSatisfied() {
        return result == AssumptionTruthValue.TRUE && !conflicting;
    }

    public boolean isRejected() {
        return result == AssumptionTruthValue.FALSE && !conflicting;
    }

    public boolean isInconclusive() {
        return result == AssumptionTruthValue.UNKNOWN;
    }

    private static Aggregate aggregate(
        List<AssumptionEvaluationEvidence> evidence
    ) {
        boolean anyTrue = false;
        boolean anyFalse = false;
        for (AssumptionEvaluationEvidence item : evidence) {
            if (item.result() == AssumptionTruthValue.TRUE) {
                anyTrue = true;
            } else if (item.result() == AssumptionTruthValue.FALSE) {
                anyFalse = true;
            }
        }
        if (anyTrue && anyFalse) {
            return new Aggregate(AssumptionTruthValue.UNKNOWN, true);
        }
        if (anyFalse) {
            return new Aggregate(AssumptionTruthValue.FALSE, false);
        }
        if (anyTrue) {
            return new Aggregate(AssumptionTruthValue.TRUE, false);
        }
        return new Aggregate(AssumptionTruthValue.UNKNOWN, false);
    }

    private static void rejectDuplicateEvaluatorIds(
        List<AssumptionEvaluationEvidence> evidence
    ) {
        Set<String> ids = new HashSet<>();
        for (AssumptionEvaluationEvidence item : evidence) {
            if (!ids.add(item.evaluatorId())) {
                throw new IllegalArgumentException(
                    "duplicate assumption evaluator id: "
                        + item.evaluatorId());
            }
        }
    }

    private static String requireHash(String value) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                "evaluatorProfileHash must be a sha256 hash");
        }
        return value;
    }

    private record Aggregate(
        AssumptionTruthValue result,
        boolean conflicting
    ) {
    }
}
