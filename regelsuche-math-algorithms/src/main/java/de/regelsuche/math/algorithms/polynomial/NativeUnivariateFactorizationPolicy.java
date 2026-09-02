package de.regelsuche.math.algorithms.polynomial;

import java.util.Objects;

/** Complete bounded policy for the native univariate Z[x]/Q[x] engine. */
public record NativeUnivariateFactorizationPolicy(
    UnivariateContentPolicy contentPolicy,
    SuitablePrimeSelectionPolicy suitablePrimePolicy,
    ZassenhausRecombinationPolicy recombinationPolicy,
    long maxEngineWorkUnits
) {
    /**
     * Preserves the historical request-owned work authority.
     *
     * <p>Callers that need a stricter backend-only boundary use
     * {@link #withMaxEngineWorkUnits(long)}. The outer request remains the
     * authority for backend plus independent verification work.</p>
     */
    public NativeUnivariateFactorizationPolicy(
        UnivariateContentPolicy contentPolicy,
        SuitablePrimeSelectionPolicy suitablePrimePolicy,
        ZassenhausRecombinationPolicy recombinationPolicy
    ) {
        this(
            contentPolicy,
            suitablePrimePolicy,
            recombinationPolicy,
            Long.MAX_VALUE
        );
    }

    public NativeUnivariateFactorizationPolicy {
        Objects.requireNonNull(contentPolicy, "contentPolicy");
        Objects.requireNonNull(
            suitablePrimePolicy,
            "suitablePrimePolicy");
        Objects.requireNonNull(
            recombinationPolicy,
            "recombinationPolicy");
        if (maxEngineWorkUnits < 1L) {
            throw new IllegalArgumentException(
                "native engine work authority must be positive"
            );
        }
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

    public NativeUnivariateFactorizationPolicy withMaxEngineWorkUnits(
        long maximum
    ) {
        return new NativeUnivariateFactorizationPolicy(
            contentPolicy,
            suitablePrimePolicy,
            recombinationPolicy,
            maximum
        );
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
        if (maxEngineWorkUnits != Long.MAX_VALUE) {
            AlgorithmEvidence.append(
                result,
                "regelsuche.native-engine-work-authority/v1"
            );
            AlgorithmEvidence.append(
                result,
                Long.toString(maxEngineWorkUnits)
            );
        }
        return result.toString();
    }
}
