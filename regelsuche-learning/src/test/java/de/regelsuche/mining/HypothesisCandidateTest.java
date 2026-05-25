package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.validation.CandidateProofStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link HypothesisCandidate}. */
class HypothesisCandidateTest {

    @Test
    void constructorCopiesAllCollections() {
        HypothesisCandidate h = new HypothesisCandidate(
            "hyp-1",
            "x^2 + 2*A*x + A^2",
            "(x + A)^2",
            List.of("path-1", "path-2"),
            List.of(new HypothesisCandidate.ExpressionPair("x^2 + 2x + 1", "(x+1)^2")),
            List.of("x is free"),
            0.9,
            CandidateProofStatus.VALIDATED_BY_EXAMPLES,
            null,
            List.of("N1 = 2*A", "N2 = A^2"),
            Map.of("B", List.of("x", "2*x")),
            Instant.now()
        );

        assertEquals("hyp-1", h.id());
        assertEquals(2, h.supportingPaths().size());
        assertEquals(1, h.supportingExpressions().size());
        assertNull(h.counterexampleStatus());
        assertEquals(CandidateProofStatus.VALIDATED_BY_EXAMPLES, h.proofStatus());
        assertFalse(h.expressionPlaceholders().isEmpty());
    }

    @Test
    void blankIdThrows() {
        assertThrows(IllegalArgumentException.class, () ->
            new HypothesisCandidate(
                "  ", "x", "x", null, null, null, 0.5,
                CandidateProofStatus.OBSERVED, null, null, null, null
            )
        );
    }

    @Test
    void noveltyScoreIsClamped() {
        HypothesisCandidate h = new HypothesisCandidate(
            "hyp-clamp", "x", "x", null, null, null, 1.5,
            CandidateProofStatus.OBSERVED, null, null, null, null
        );
        assertEquals(1.0, h.noveltyScore(), 0.001);

        HypothesisCandidate h2 = new HypothesisCandidate(
            "hyp-clamp2", "x", "x", null, null, null, -0.5,
            CandidateProofStatus.OBSERVED, null, null, null, null
        );
        assertEquals(0.0, h2.noveltyScore(), 0.001);
    }

    @Test
    void withProofStatusReturnsUpdatedCopy() {
        HypothesisCandidate original = new HypothesisCandidate(
            "hyp-2", "x", "x", null, null, null, 0.5,
            CandidateProofStatus.OBSERVED, null, null, null, null
        );
        HypothesisCandidate updated = original.withProofStatus(CandidateProofStatus.VALIDATED_BY_EXAMPLES);

        assertEquals(CandidateProofStatus.OBSERVED, original.proofStatus());
        assertEquals(CandidateProofStatus.VALIDATED_BY_EXAMPLES, updated.proofStatus());
        assertEquals(original.id(), updated.id());
    }

    @Test
    void withCounterexampleStatusReturnsUpdatedCopy() {
        HypothesisCandidate original = new HypothesisCandidate(
            "hyp-3", "x", "x", null, null, null, 0.5,
            CandidateProofStatus.OBSERVED, null, null, null, null
        );
        HypothesisCandidate withCex = original.withCounterexampleStatus(true);
        HypothesisCandidate withoutCex = original.withCounterexampleStatus(false);

        assertNull(original.counterexampleStatus());
        assertTrue(withCex.counterexampleStatus());
        assertFalse(withoutCex.counterexampleStatus());
    }

    @Test
    void fromRuleCandidatePopulatesFields() {
        RuleCandidate candidate = new RuleCandidate(
            "x^2 + 2*A*x + A^2",
            "(x + A)^2",
            3,
            5.0,
            8,
            true,
            true,
            true,
            List.of("N1 = 2*A"),
            RuleStatus.MATCHES_KNOWN_RULE,
            CandidateProofStatus.VALIDATED_BY_EXAMPLES,
            "hash-abc",
            List.of("p1", "p2", "p3")
        );

        HypothesisCandidate h = HypothesisCandidate.from(candidate, 0.8);
        assertEquals("hash-abc", h.id());
        assertEquals("x^2 + 2*A*x + A^2", h.leftPattern());
        assertEquals("(x + A)^2", h.rightPattern());
        assertEquals(0.8, h.noveltyScore(), 0.001);
        assertEquals(CandidateProofStatus.VALIDATED_BY_EXAMPLES, h.proofStatus());
        assertEquals(3, h.supportingPaths().size());
        assertNotNull(h.createdAt());
    }

    @Test
    void createdAtDefaultsToNow() {
        HypothesisCandidate h = new HypothesisCandidate(
            "hyp-now", "x", "x", null, null, null, 0.5,
            CandidateProofStatus.OBSERVED, null, null, null, null
        );
        assertNotNull(h.createdAt());
        assertTrue(h.createdAt().isBefore(Instant.now().plusSeconds(1)));
    }
}
