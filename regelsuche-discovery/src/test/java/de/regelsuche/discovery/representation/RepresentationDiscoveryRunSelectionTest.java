package de.regelsuche.discovery.representation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RepresentationDiscoveryRunSelectionTest {
    @Test
    void objectReferencesAreOptionalSha256Identities() {
        RepresentationDiscoveryRunSelection selection =
            RepresentationDiscoveryRunSelection.create(
                sha("run"),
                sha("candidate"),
                sha("state"),
                sha("edge"),
                "/0/1",
                sha("proof")
            );

        assertEquals(sha("candidate"), selection.candidateId());
        assertEquals(sha("state"), selection.stateId());
        assertEquals(sha("edge"), selection.edgeId());
        assertEquals(sha("proof"), selection.proofObligationId());
    }

    @Test
    void arbitraryObjectReferenceTextFailsClosed() {
        assertThrows(IllegalArgumentException.class, () ->
            RepresentationDiscoveryRunSelection.create(
                sha("run"), "candidate-1", "", "", "", ""));
        assertThrows(IllegalArgumentException.class, () ->
            RepresentationDiscoveryRunSelection.create(
                sha("run"), "", "state-1", "", "", ""));
        assertThrows(IllegalArgumentException.class, () ->
            RepresentationDiscoveryRunSelection.create(
                sha("run"), "", "", "edge-1", "", ""));
        assertThrows(IllegalArgumentException.class, () ->
            RepresentationDiscoveryRunSelection.create(
                sha("run"), "", "", "", "", "proof-1"));
    }

    @Test
    void anOccurrenceOnlySelectionRemainsValid() {
        RepresentationDiscoveryRunSelection selection =
            RepresentationDiscoveryRunSelection.create(
                sha("run"), "", "", "", "/2/0", "");

        assertEquals("/2/0", selection.occurrencePath());
    }

    private static String sha(String value) {
        return KnownStructureCatalog.sha256(value);
    }
}
