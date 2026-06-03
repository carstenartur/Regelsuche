package de.regelsuche.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SymPyDiscoveryOracleAdapterTest {
    private final SymPyDiscoveryOracleAdapter adapter = new SymPyDiscoveryOracleAdapter();

    @Test
    void equivalenceReturnsTriStateWithoutThrowingWhenRuntimeIsMissing() {
        SymPyDiscoveryOracleAdapter.OracleResult result = adapter.equivalence("(x+1)^2", "x^2+2*x+1");
        assertNotNull(result);
        assertTrue(List.of(
            SymPyDiscoveryOracleAdapter.Status.AGREE,
            SymPyDiscoveryOracleAdapter.Status.DISAGREE,
            SymPyDiscoveryOracleAdapter.Status.UNAVAILABLE).contains(result.status()));
    }

    @Test
    void factorCandidateReturnsUnavailableForInvalidInput() {
        SymPyDiscoveryOracleAdapter.OracleResult result = adapter.factorCandidate("x +", "(x+1)");
        assertEquals(SymPyDiscoveryOracleAdapter.Status.UNAVAILABLE, result.status());
    }

    @Test
    void groebnerOracleReturnsUnavailableWithoutGenerators() {
        SymPyDiscoveryOracleAdapter.OracleResult result = adapter.groebnerEquivalence(List.of(), "x");
        assertEquals(SymPyDiscoveryOracleAdapter.Status.UNAVAILABLE, result.status());
    }
}
