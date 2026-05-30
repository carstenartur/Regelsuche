package de.regelsuche.discovery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.validation.DiscoveryResultKind;
import org.junit.jupiter.api.Test;

class DiscoveryResultKindTest {
    @Test
    void classificationDistinguishesDiscoveryLevels() {
        assertFalse(DiscoveryResultKind.NO_CANDIDATE.hasCandidate());
        assertFalse(DiscoveryResultKind.NO_CANDIDATE.discovered());

        assertTrue(DiscoveryResultKind.HYPOTHESIS_ONLY.hasCandidate());
        assertFalse(DiscoveryResultKind.HYPOTHESIS_ONLY.hasBridge());
        assertFalse(DiscoveryResultKind.HYPOTHESIS_ONLY.hasTransformedResult());
        assertFalse(DiscoveryResultKind.HYPOTHESIS_ONLY.discovered());

        assertTrue(DiscoveryResultKind.BRIDGE_FOUND.hasCandidate());
        assertTrue(DiscoveryResultKind.BRIDGE_FOUND.hasBridge());
        assertFalse(DiscoveryResultKind.BRIDGE_FOUND.hasTransformedResult());
        assertTrue(DiscoveryResultKind.BRIDGE_FOUND.discovered());

        assertTrue(DiscoveryResultKind.FACTORED.hasTransformedResult());
        assertTrue(DiscoveryResultKind.SIMPLIFIED.hasTransformedResult());
        assertTrue(DiscoveryResultKind.MACRO_LEARNED.hasMacroLearning());
        assertFalse(DiscoveryResultKind.MACRO_LEARNED.hasMacroReuse());
        assertTrue(DiscoveryResultKind.MACRO_REUSED.hasMacroLearning());
        assertTrue(DiscoveryResultKind.MACRO_REUSED.hasMacroReuse());
        assertTrue(DiscoveryResultKind.FALSE_POSITIVE.hasCandidate());
        assertTrue(DiscoveryResultKind.FALSE_POSITIVE.isFalsePositive());
        assertFalse(DiscoveryResultKind.FALSE_POSITIVE.discovered());
    }
}
