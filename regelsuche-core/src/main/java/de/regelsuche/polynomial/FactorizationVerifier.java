package de.regelsuche.polynomial;

import java.util.Objects;

/** Independently reconstructs a candidate in the source polynomial ring. */
public final class FactorizationVerifier {
    private FactorizationVerifier() {
    }

    public static <C> Verification<C> verify(
        SparsePolynomial<C> source,
        FactorizationCandidate<C> candidate
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(candidate, "candidate");
        if (!source.ring().equals(
                candidate.unresolvedRemainder().ring())
                || candidate.factors().stream().anyMatch(factor ->
                    !source.ring().equals(
                        factor.polynomial().ring()))) {
            return new Verification<>(
                Status.RING_MISMATCH,
                "FACTORIZATION_RING_MISMATCH",
                null);
        }
        SparsePolynomial<C> reconstructed = SparsePolynomial.constant(
            source.ring(),
            candidate.unit());
        for (PolynomialFactor<C> factor : candidate.factors()) {
            reconstructed = reconstructed.multiply(
                factor.polynomial().pow(factor.multiplicity()));
        }
        reconstructed = reconstructed.multiply(
            candidate.unresolvedRemainder());
        if (!source.equals(reconstructed)) {
            return new Verification<>(
                Status.PRODUCT_MISMATCH,
                "FACTORIZATION_PRODUCT_MISMATCH",
                reconstructed);
        }
        return new Verification<>(
            Status.VERIFIED,
            "FACTORIZATION_PRODUCT_RECONSTRUCTED",
            reconstructed);
    }

    public enum Status {
        VERIFIED,
        RING_MISMATCH,
        PRODUCT_MISMATCH
    }

    public record Verification<C>(
        Status status,
        String detailCode,
        SparsePolynomial<C> reconstructed
    ) {
        public Verification {
            Objects.requireNonNull(status, "status");
            if (detailCode == null || detailCode.isBlank()) {
                throw new IllegalArgumentException(
                    "factorization verification detail must not be blank");
            }
            if (status == Status.RING_MISMATCH
                    && reconstructed != null) {
                throw new IllegalArgumentException(
                    "ring mismatch must not expose a reconstruction");
            }
            if (status != Status.RING_MISMATCH
                    && reconstructed == null) {
                throw new IllegalArgumentException(
                    "product verification requires a reconstruction");
            }
        }

        public boolean verified() {
            return status == Status.VERIFIED;
        }
    }
}
