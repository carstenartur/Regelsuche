package de.regelsuche.polynomial;

import java.util.Objects;

/** Typed factorization request with one non-resettable arithmetic budget. */
public record FactorizationRequest<C>(
    SparsePolynomial<C> source,
    FactorizationCompleteness minimumCompleteness,
    int maxCandidates,
    long maxArithmeticSteps
) {
    public FactorizationRequest {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(
            minimumCompleteness,
            "minimumCompleteness");
        if (source.isZero()) {
            throw new IllegalArgumentException(
                "zero polynomial has no finite factorization contract");
        }
        if (maxCandidates < 0 || maxArithmeticSteps < 1) {
            throw new IllegalArgumentException(
                "factorization request budget is invalid");
        }
    }

    public static <C> FactorizationRequest<C> verifiedDecomposition(
        SparsePolynomial<C> source,
        int maxCandidates,
        long maxArithmeticSteps
    ) {
        return new FactorizationRequest<>(
            source,
            FactorizationCompleteness.DECOMPOSITION_ONLY,
            maxCandidates,
            maxArithmeticSteps);
    }
}
