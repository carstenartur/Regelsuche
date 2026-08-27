package de.regelsuche.math.algorithms.polynomial;

import java.util.Objects;

/** Complete bounded policy for the native univariate Z[x]/Q[x] engine. */
public record NativeUnivariateFactorizationPolicy(
    UnivariateContentPolicy contentPolicy,
    SuitablePrimeSelectionPolicy suitablePrimePolicy,
    ZassenhausRecombinationPolicy recombinationPolicy
) {
    public NativeUnivariateFactorizationPolicy {
        Objects.requireNonNull(contentPolicy, "contentPolicy");
        Objects.requireNonNull(
            suitablePrimePolicy,
            "suitablePrimePolicy");
        Objects.requireNonNull(
            recombinationPolicy,
            "recombinationPolicy");
    }

    public static NativeUnivariateFactorizationPolicy boundedDefaults() {
        FiniteFieldFactorizationPolicy finiteField =
            FiniteFieldFactorizationPolicy.deterministicBerlekamp(
                257,
                1_000_000);
        return new NativeUnivariateFactorizationPolicy(
            new UnivariateContentPolicy(65_536),
            SuitablePrimeSelectionPolicy.deterministicAscending(
                257,
                55,
                finiteField),
            ZassenhausRecombinationPolicy.boundedDefaults());
    }

    public String canonicalMaterial() {
        StringBuilder result = new StringBuilder();
        AlgorithmEvidence.append(
            result,
            contentPolicy.canonicalMaterial());
        AlgorithmEvidence.append(
            result,
            suitablePrimePolicy.canonicalMaterial());
        AlgorithmEvidence.append(
            result,
            recombinationPolicy.canonicalMaterial());
        return result.toString();
    }
}
