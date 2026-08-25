package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.FactorizationRequest;
import java.util.Objects;

/** Explicit structural, intermediate-size and work limits for normalization. */
public record UnivariateContentRequest(
    FactorizationRequest.StructuralLimits structuralLimits,
    int maxIntermediateCoefficientBitLength,
    long maxWorkUnits
) {
    public UnivariateContentRequest {
        Objects.requireNonNull(
            structuralLimits,
            "structuralLimits");
        if (maxIntermediateCoefficientBitLength < 1
                || maxWorkUnits < 1) {
            throw new IllegalArgumentException(
                "univariate content request is invalid");
        }
    }

    public String canonicalMaterial() {
        return structuralLimits.canonicalMaterial()
            + ':' + maxIntermediateCoefficientBitLength
            + ':' + maxWorkUnits;
    }
}
