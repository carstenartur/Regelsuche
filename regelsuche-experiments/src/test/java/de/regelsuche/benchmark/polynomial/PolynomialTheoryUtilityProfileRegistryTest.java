package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilityProfileRegistryTest {
    @Test
    void fiveProfileReadinessDoesNotInventExternalCanonicalWork() {
        var entries = PolynomialTheoryUtilityProfileRegistry.entries();
        assertEquals(5, entries.size());
        var adapters = PolynomialTheoryUtilityProfileRegistry.availableAdapters();
        assertEquals(4, adapters.size());
        assertTrue(adapters.stream()
            .allMatch(value -> value.resultSchema().equals(PolynomialTheoryUtilityCandidateResult.OBSERVED_SCHEMA)));
        var failure = assertThrows(IllegalStateException.class,
            PolynomialTheoryUtilityProfileRegistry::requireRunnableAdapters);
        assertTrue(failure.getMessage().contains("EXTERNAL_CANONICAL_INTERNAL_WORK_UNAVAILABLE"));
    }
}
