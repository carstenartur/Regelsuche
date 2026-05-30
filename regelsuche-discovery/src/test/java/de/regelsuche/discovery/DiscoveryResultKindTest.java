package de.regelsuche.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.validation.DiscoveryEvidenceKind;
import de.regelsuche.validation.DiscoveryResultKind;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class DiscoveryResultKindTest {
    @Test
    void resultKindStaysSeparateFromEvidence() {
        assertEquals(5, DiscoveryResultKind.values().length);
        assertTrue(EnumSet.allOf(DiscoveryResultKind.class).containsAll(EnumSet.of(
            DiscoveryResultKind.NO_CANDIDATE,
            DiscoveryResultKind.HYPOTHESIS_ONLY,
            DiscoveryResultKind.BRIDGE_FOUND,
            DiscoveryResultKind.TRANSFORMED,
            DiscoveryResultKind.FALSE_POSITIVE
        )));
        assertTrue(EnumSet.allOf(DiscoveryEvidenceKind.class).containsAll(EnumSet.of(
            DiscoveryEvidenceKind.FACTORED,
            DiscoveryEvidenceKind.SIMPLIFIED,
            DiscoveryEvidenceKind.MACRO_LEARNED,
            DiscoveryEvidenceKind.MACRO_REUSED,
            DiscoveryEvidenceKind.EQUIVALENCE_VALIDATED
        )));
    }
}
