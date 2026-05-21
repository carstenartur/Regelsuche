package de.regelsuche.proof;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.mining.CandidateProofStatus;
import org.junit.jupiter.api.Test;

class ProofStatusDescriptionTest {

    @Test
    void everyEnumValueHasADescription() {
        for (CandidateProofStatus status : CandidateProofStatus.values()) {
            ProofStatusDescription.Description description = ProofStatusDescription.of(status);
            assertNotNull(description, "missing description for " + status);
            assertEquals(status, description.status());
            assertTrue(description.label() != null && !description.label().isBlank(),
                "label missing for " + status);
            assertTrue(description.summaryDe() != null && !description.summaryDe().isBlank(),
                "German summary missing for " + status);
            assertTrue(description.summaryEn() != null && !description.summaryEn().isBlank(),
                "English summary missing for " + status);
        }
    }

    @Test
    void lookupByNameIsCaseInsensitive() {
        assertEquals(CandidateProofStatus.FORMALLY_PROVABLE,
            ProofStatusDescription.ofName("formally_provable").status());
        assertEquals(CandidateProofStatus.SYMBOLICALLY_VERIFIED,
            ProofStatusDescription.ofName("SYMBOLICALLY_VERIFIED").status());
    }
}
