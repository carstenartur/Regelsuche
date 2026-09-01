package de.regelsuche.benchmark.polynomial;

import java.util.List;
import java.util.Objects;

/**
 * One target-blind execution result together with its complete measurements.
 *
 * <p>The value prevents a runner from retaining a terminal result while
 * silently dropping the ordered path, factorization or cache evidence that
 * justified it.</p>
 */
public record PolynomialTheoryUtilityMeasuredCandidate(
    PolynomialTheoryUtilityCandidateResult result,
    PolynomialTheoryUtilityCandidateMeasurements measurements
) {
    public PolynomialTheoryUtilityMeasuredCandidate {
        result = Objects.requireNonNull(result, "result");
        measurements = Objects.requireNonNull(measurements, "measurements");
        measurements.validateAgainst(result);
    }

    public static PolynomialTheoryUtilityMeasuredCandidate create(
        PolynomialTheoryUtilityCandidateResult result,
        List<PolynomialTheoryUtilityTransitionTrace> transitionTraces,
        List<PolynomialTheoryUtilityFactorizationAttempt> factorizationAttempts,
        List<PolynomialTheoryUtilityCacheEvent> cacheEvents
    ) {
        var retained = Objects.requireNonNull(result, "result");
        return new PolynomialTheoryUtilityMeasuredCandidate(
            retained,
            PolynomialTheoryUtilityCandidateMeasurements.create(
                retained,
                transitionTraces,
                factorizationAttempts,
                cacheEvents
            )
        );
    }

    /**
     * Creates the only valid implicit measurement: an entirely zero-work row.
     *
     * <p>Any consumed work or transition requires an adapter-owned measured
     * result. This prevents a legacy adapter from returning preflight,
     * validation or technical work while silently dropping the observations
     * that explain it.</p>
     */
    public static PolynomialTheoryUtilityMeasuredCandidate
            withoutObservations(
                PolynomialTheoryUtilityCandidateResult result
            ) {
        var retained = Objects.requireNonNull(result, "result");
        if (!PolynomialTheoryUtilityWorkBreakdown.zero().equals(
                retained.work())
                || !retained.transitions().isEmpty()) {
            throw new IllegalArgumentException(
                "implicit measurements require a zero-work result"
            );
        }
        return create(retained, List.of(), List.of(), List.of());
    }
}
