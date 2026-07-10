package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import de.regelsuche.validation.CounterexampleSearchService;

/** Unit tests for {@link HypothesisRevision}. */
class HypothesisRevisionTest {

    private static HypothesisCandidate minimalHypothesis(String id, String left, String right) {
        return new HypothesisCandidate(
            id, left, right, null, null, null, 0.5,
            de.regelsuche.validation.CandidateProofStatus.OBSERVED,
            null, null, null, null
        );
    }

    @Test
    void initialCreatesRevisionInProposedState() {
        HypothesisCandidate hyp = minimalHypothesis("hyp-1", "x + 0", "x");
        HypothesisRevision initial = HypothesisRevision.initial(hyp);

        assertNotNull(initial);
        assertEquals("hyp-1-r0", initial.id());
        assertNull(initial.parentId());
        assertEquals("hyp-1", initial.originHypothesisId());
        assertEquals(0, initial.revisionIndex());
        assertEquals("x + 0", initial.leftPattern());
        assertEquals("x", initial.rightPattern());
        assertTrue(initial.assumptions().isEmpty());
        assertNull(initial.triggerEvidence());
        assertEquals("", initial.refinementStrategyName());
        assertEquals(HypothesisRevisionStatus.PROPOSED, initial.status());
        assertNotNull(initial.createdAt());
    }

    @Test
    void withStatusReturnsUpdatedCopy() {
        HypothesisCandidate hyp = minimalHypothesis("hyp-2", "a / b", "a * (1/b)");
        HypothesisRevision revision = HypothesisRevision.initial(hyp);

        HypothesisRevision challenged = revision.withStatus(HypothesisRevisionStatus.CHALLENGED);
        assertEquals(HypothesisRevisionStatus.PROPOSED, revision.status());
        assertEquals(HypothesisRevisionStatus.CHALLENGED, challenged.status());
        assertEquals(revision.id(), challenged.id());
        assertEquals(revision.leftPattern(), challenged.leftPattern());
    }

    @Test
    void blankIdThrows() {
        assertThrows(IllegalArgumentException.class, () ->
            new HypothesisRevision(
                "  ", null, "origin", 0,
                "x", "x", null, null, null,
                HypothesisRevisionStatus.PROPOSED, null
            )
        );
    }

    @Test
    void blankOriginIdThrows() {
        assertThrows(IllegalArgumentException.class, () ->
            new HypothesisRevision(
                "rev-id", null, "  ", 0,
                "x", "x", null, null, null,
                HypothesisRevisionStatus.PROPOSED, null
            )
        );
    }

    @Test
    void negativeRevisionIndexThrows() {
        assertThrows(IllegalArgumentException.class, () ->
            new HypothesisRevision(
                "rev-id", null, "origin", -1,
                "x", "x", null, null, null,
                HypothesisRevisionStatus.PROPOSED, null
            )
        );
    }

    @Test
    void canonicalFingerprintIsDeterministic() {
        HypothesisRevision r1 = new HypothesisRevision(
            "rev-1", null, "hyp", 0, "a + b", "b + a",
            List.of("b != 0", "a > 0"), null, null,
            HypothesisRevisionStatus.PROPOSED, Instant.now()
        );
        HypothesisRevision r2 = new HypothesisRevision(
            "rev-2", null, "hyp", 1, "a + b", "b + a",
            List.of("a > 0", "b != 0"), null, "some-strategy",
            HypothesisRevisionStatus.PROPOSED, Instant.now()
        );

        // Fingerprints should be identical (assumptions sorted)
        assertEquals(r1.canonicalFingerprint(), r2.canonicalFingerprint());
    }

    @Test
    void canonicalFingerprintDiffersOnPatternChange() {
        HypothesisRevision r1 = new HypothesisRevision(
            "rev-1", null, "hyp", 0, "a + b", "b + a",
            List.of(), null, null,
            HypothesisRevisionStatus.PROPOSED, Instant.now()
        );
        HypothesisRevision r2 = new HypothesisRevision(
            "rev-2", null, "hyp", 1, "a * b", "b * a",
            List.of(), null, null,
            HypothesisRevisionStatus.PROPOSED, Instant.now()
        );

        assertFalse(r1.canonicalFingerprint().equals(r2.canonicalFingerprint()));
    }

    @Test
    void statusDefaultsToProposed() {
        HypothesisRevision r = new HypothesisRevision(
            "rev-1", null, "hyp", 0, "x", "x", null, null, null, null, null
        );
        assertEquals(HypothesisRevisionStatus.PROPOSED, r.status());
    }

    @Test
    void createdAtDefaultsToNow() {
        HypothesisRevision r = new HypothesisRevision(
            "rev-1", null, "hyp", 0, "x", "x", null, null, null, null, null
        );
        assertNotNull(r.createdAt());
        assertTrue(r.createdAt().isBefore(Instant.now().plusSeconds(1)));
    }

    @Test
    void assumptionsAreCopiedDefensively() {
        HypothesisRevision r = new HypothesisRevision(
            "rev-1", null, "hyp", 0, "x", "x",
            List.of("x > 0"), null, null, null, null
        );
        assertEquals(1, r.assumptions().size());
        assertEquals("x > 0", r.assumptions().get(0));
    }
}
