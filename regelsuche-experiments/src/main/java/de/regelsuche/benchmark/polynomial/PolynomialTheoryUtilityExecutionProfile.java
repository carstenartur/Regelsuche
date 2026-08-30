package de.regelsuche.benchmark.polynomial;

import java.util.List;
import java.util.Set;

/** One frozen adapter policy in the polynomial utility comparison. */
public record PolynomialTheoryUtilityExecutionProfile(
    String profileId,
    String adapterId,
    String scope,
    String factorizationMode,
    String engineId,
    String transformationId,
    String cacheMode,
    String fallbackMode,
    String candidateSelection
) {
    public PolynomialTheoryUtilityExecutionProfile {
        for (String value : List.of(
                profileId,
                adapterId,
                scope,
                factorizationMode,
                engineId,
                transformationId,
                cacheMode,
                fallbackMode,
                candidateSelection)) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                    "profile values must not be blank"
                );
            }
        }
        if (!Set.of("DISABLED", "READ_WRITE").contains(cacheMode)
                || !Set.of(
                    "NONE",
                    "NATIVE_ON_CACHE_MISS_ONLY"
                ).contains(fallbackMode)
                || !Set.of(
                    "NONE",
                    "EXPLICIT_INDEX_ASCENDING"
                ).contains(candidateSelection)) {
            throw new IllegalArgumentException("unfrozen profile policy");
        }
    }
}
