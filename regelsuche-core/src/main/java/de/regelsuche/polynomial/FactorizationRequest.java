package de.regelsuche.polynomial;

import java.util.Objects;

/** Typed factorization request with one non-resettable work budget. */
public record FactorizationRequest<C>(
    SparsePolynomial<C> source,
    EvidenceRequirement evidenceRequirement,
    int maxCandidates,
    long maxWorkUnits
) {
    public FactorizationRequest {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(
            evidenceRequirement,
            "evidenceRequirement");
        if (source.isZero()) {
            throw new IllegalArgumentException(
                "zero polynomial has no finite factorization contract");
        }
        if (maxCandidates < 0 || maxWorkUnits < 1) {
            throw new IllegalArgumentException(
                "factorization request budget is invalid");
        }
    }

    public static <C> FactorizationRequest<C> verifiedDecomposition(
        SparsePolynomial<C> source,
        int maxCandidates,
        long maxWorkUnits
    ) {
        return new FactorizationRequest<>(
            source,
            EvidenceRequirement.VERIFIED_DECOMPOSITION,
            maxCandidates,
            maxWorkUnits);
    }

    /**
     * Required authority after independent processing of an engine result.
     * Backend claims never satisfy {@code INDEPENDENT_COMPLETE} by themselves.
     */
    public enum EvidenceRequirement {
        VERIFIED_DECOMPOSITION,
        INDEPENDENT_COMPLETE
    }
}
