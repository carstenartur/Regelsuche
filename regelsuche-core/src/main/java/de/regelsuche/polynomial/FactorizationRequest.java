package de.regelsuche.polynomial;

import java.util.Objects;
import java.util.Optional;

/**
 * Typed factorization request with explicit structural and algorithmic budgets.
 */
public record FactorizationRequest<C>(
    SparsePolynomial<C> source,
    EvidenceRequirement evidenceRequirement,
    StructuralLimits structuralLimits,
    int maxCandidates,
    long maxWorkUnits
) {
    public FactorizationRequest {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(
            evidenceRequirement,
            "evidenceRequirement");
        Objects.requireNonNull(
            structuralLimits,
            "structuralLimits");
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
        StructuralLimits structuralLimits,
        int maxCandidates,
        long maxWorkUnits
    ) {
        return new FactorizationRequest<>(
            source,
            EvidenceRequirement.VERIFIED_DECOMPOSITION,
            structuralLimits,
            maxCandidates,
            maxWorkUnits);
    }

    public Optional<String> structuralViolation() {
        return structuralLimits.firstViolation(source);
    }

    public String canonicalMaterial() {
        StringBuilder result = new StringBuilder();
        append(result, source.canonicalMaterial());
        append(result, evidenceRequirement.name());
        append(result, structuralLimits.canonicalMaterial());
        append(result, Integer.toString(maxCandidates));
        append(result, Long.toString(maxWorkUnits));
        return result.toString();
    }

    private static void append(
        StringBuilder target,
        String value
    ) {
        target.append('|')
            .append(value.length())
            .append(':')
            .append(value);
    }

    /** Required authority after independent processing of an engine result. */
    public enum EvidenceRequirement {
        VERIFIED_DECOMPOSITION,
        INDEPENDENT_COMPLETE
    }

    /** Request-wide bounds checked before an engine can inspect the source. */
    public record StructuralLimits(
        int maxVariables,
        int maxTotalDegree,
        int maxTerms,
        int maxCoefficientBitLength
    ) {
        public StructuralLimits {
            if (maxVariables < 1
                    || maxTotalDegree < 0
                    || maxTerms < 1
                    || maxCoefficientBitLength < 1) {
                throw new IllegalArgumentException(
                    "factorization structural limits are invalid");
            }
        }

        public Optional<String> firstViolation(
            SparsePolynomial<?> polynomial
        ) {
            Objects.requireNonNull(polynomial, "polynomial");
            if (polynomial.ring().variableCount() > maxVariables) {
                return Optional.of("MAX_VARIABLES_EXCEEDED");
            }
            if (polynomial.totalDegree() > maxTotalDegree) {
                return Optional.of("MAX_TOTAL_DEGREE_EXCEEDED");
            }
            if (polynomial.termCount() > maxTerms) {
                return Optional.of("MAX_TERMS_EXCEEDED");
            }
            if (polynomial.maxCoefficientBitLength()
                    > maxCoefficientBitLength) {
                return Optional.of(
                    "MAX_COEFFICIENT_BIT_LENGTH_EXCEEDED");
            }
            return Optional.empty();
        }

        public String canonicalMaterial() {
            return maxVariables
                + ":" + maxTotalDegree
                + ":" + maxTerms
                + ":" + maxCoefficientBitLength;
        }
    }
}
