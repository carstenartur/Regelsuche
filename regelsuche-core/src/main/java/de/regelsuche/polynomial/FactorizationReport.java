package de.regelsuche.polynomial;

import java.util.List;
import java.util.Objects;

/** Immutable engine result; losing and inconclusive outcomes remain explicit. */
public record FactorizationReport<C>(
    String engineId,
    FactorizationStatus status,
    String detailCode,
    long arithmeticSteps,
    List<FactorizationCandidate<C>> candidates
) {
    public FactorizationReport {
        if (engineId == null
                || engineId.isBlank()
                || status == null
                || detailCode == null
                || detailCode.isBlank()
                || arithmeticSteps < 0) {
            throw new IllegalArgumentException(
                "factorization report is invalid");
        }
        candidates = List.copyOf(
            Objects.requireNonNull(candidates, "candidates"));
        boolean success = status
                == FactorizationStatus.COMPLETE_FACTORIZATION
            || status == FactorizationStatus.PARTIAL_FACTORIZATION;
        if (success == candidates.isEmpty()) {
            throw new IllegalArgumentException(
                "factorization report candidate/status mismatch");
        }
        if (status == FactorizationStatus.COMPLETE_FACTORIZATION
                && candidates.stream().anyMatch(candidate ->
                    candidate.completeness()
                        != FactorizationCompleteness
                            .INDEPENDENTLY_CERTIFIED_COMPLETE)) {
            throw new IllegalArgumentException(
                "complete report requires independently certified candidates");
        }
    }

    public boolean successful() {
        return status == FactorizationStatus.COMPLETE_FACTORIZATION
            || status == FactorizationStatus.PARTIAL_FACTORIZATION;
    }

    public static <C> FactorizationReport<C> failure(
        String engineId,
        FactorizationStatus status,
        String detailCode,
        long arithmeticSteps
    ) {
        return new FactorizationReport<>(
            engineId,
            status,
            detailCode,
            arithmeticSteps,
            List.of());
    }
}
