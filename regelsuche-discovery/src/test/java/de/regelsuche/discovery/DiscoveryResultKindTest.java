package de.regelsuche.discovery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DiscoveryResultKindTest {
    @Test
    void classificationDistinguishesDiscoveryLevels() {
        assertFalse(DiscoveryResultKind.NO_CANDIDATE.discovered());
        assertTrue(DiscoveryResultKind.HYPOTHESIS_ONLY.discovered());
        assertTrue(DiscoveryResultKind.BRIDGE_FOUND.discovered());
        assertTrue(DiscoveryResultKind.FACTORED.discovered());
        assertTrue(DiscoveryResultKind.MACRO_LEARNED.discovered());
        assertTrue(DiscoveryResultKind.MACRO_REUSED.discovered());
        assertFalse(DiscoveryResultKind.FALSE_POSITIVE.discovered());
    }
}
