package de.regelsuche.math.algorithms.polynomial;

/** Additional bounded-growth policy for exact content normalization. */
public record UnivariateContentPolicy(
    int maxIntermediateCoefficientBitLength
) {
    public UnivariateContentPolicy {
        if (maxIntermediateCoefficientBitLength < 1) {
            throw new IllegalArgumentException(
                "univariate content policy is invalid");
        }
    }

    public String canonicalMaterial() {
        return Integer.toString(
            maxIntermediateCoefficientBitLength);
    }
}
