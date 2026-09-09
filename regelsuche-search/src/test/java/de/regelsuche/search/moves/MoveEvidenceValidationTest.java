package de.regelsuche.search.moves;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class MoveEvidenceValidationTest {
    @Test void missingProvenanceAndNullValueEvidenceHaveExplicitArgumentErrors() {
        for (String provenance : new String[]{null, "", " "}) assertThrows(IllegalArgumentException.class,
            () -> new MoveProvider.Descriptor("id", "family", SearchMove.SourceKind.LEARNED,
                SearchMove.ProofStrength.REPLAYABLE, List.of(), SearchMove.ValueEvidence.UNKNOWN, provenance));
        assertThrows(IllegalArgumentException.class, () -> new SearchMove.ValueEvidence(0, 0, 0, -1, 1, false, null));
        assertEquals("", SearchMove.ValueEvidence.UNKNOWN.evidenceId(), "unknown is explicit and is not a provenance claim");
    }
}
