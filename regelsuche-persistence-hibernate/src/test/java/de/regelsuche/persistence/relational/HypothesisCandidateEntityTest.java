package de.regelsuche.persistence.relational;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.mining.HypothesisCandidate;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.validation.CounterexampleSearchService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HypothesisCandidateEntityTest {

    @Test
    void domainCandidateRoundTripsThroughRelationalMappingWithoutLosingEvidence() {
        Instant createdAt = Instant.parse("2026-08-09T08:00:00Z");
        Map<String, List<String>> placeholders = new LinkedHashMap<>();
        placeholders.put("?B", List.of("b", "b+1"));
        placeholders.put("?A", List.of("a"));
        HypothesisCandidate candidate = new HypothesisCandidate(
            "hypothesis-1",
            "?A + 0",
            "?A",
            List.of("path-2", "path-1"),
            List.of(
                new HypothesisCandidate.ExpressionPair("x+0", "x"),
                new HypothesisCandidate.ExpressionPair("y+0", "y")),
            List.of("x != 0", "y != 0"),
            0.75,
            CandidateProofStatus.SYMBOLICALLY_VERIFIED,
            false,
            CounterexampleSearchService.Status.NO_COUNTEREXAMPLE_FOUND,
            List.of("small-integers", "boundary-values"),
            "no counterexample in configured search",
            List.of("?A != 0"),
            placeholders,
            createdAt);

        HypothesisCandidateEntity entity = HypothesisCandidateEntity.from(candidate);
        HypothesisCandidate restored = entity.toHypothesisCandidate();

        assertEquals(candidate, restored);
        assertEquals("hypothesis-1", entity.id());
        assertNull(entity.experimentId());
        assertEquals("?A + 0", entity.leftPattern());
        assertEquals("?A", entity.rightPattern());
        assertEquals(candidate.assumptions(), entity.assumptions());
        assertEquals(candidate.supportingPaths(), entity.supportingPaths());
        assertEquals(candidate.supportingExpressions(), entity.supportingExpressions());
        assertEquals(candidate.parameterRelations(), entity.parameterRelations());
        assertEquals(candidate.expressionPlaceholders(), entity.expressionPlaceholders());
        assertEquals(CandidateProofStatus.SYMBOLICALLY_VERIFIED.name(), entity.proofStatus());
        assertEquals(Boolean.FALSE, entity.counterexampleFound());
        assertEquals(
            CounterexampleSearchService.Status.NO_COUNTEREXAMPLE_FOUND.name(),
            entity.counterexampleStatus());
        assertEquals(candidate.counterexampleAttemptedSources(), entity.counterexampleAttemptedSources());
        assertEquals(candidate.counterexampleExplanation(), entity.counterexampleExplanation());
        assertEquals(0.75, entity.noveltyScore());
        assertEquals(createdAt, entity.createdAt());
        assertTrue(entity.counterexamples().isEmpty());
    }

    @Test
    void legacyConstructorDerivesCounterexampleStatusAndAppliesSafeDefaults() {
        HypothesisCandidateEntity entity = new HypothesisCandidateEntity(
            "hypothesis-defaults",
            "   ",
            "x",
            "x",
            null,
            null,
            null,
            null,
            null,
            null,
            true,
            2.5,
            null);

        assertNull(entity.experimentId());
        assertEquals(List.of(), entity.assumptions());
        assertEquals(List.of(), entity.supportingPaths());
        assertEquals(List.of(), entity.supportingExpressions());
        assertEquals(List.of(), entity.parameterRelations());
        assertEquals(Map.of(), entity.expressionPlaceholders());
        assertEquals(CandidateProofStatus.OBSERVED.name(), entity.proofStatus());
        assertEquals(Boolean.TRUE, entity.counterexampleFound());
        assertEquals(
            CounterexampleSearchService.Status.COUNTEREXAMPLE_FOUND.name(),
            entity.counterexampleStatus());
        assertEquals(List.of(), entity.counterexampleAttemptedSources());
        assertEquals("", entity.counterexampleExplanation());
        assertEquals(1.0, entity.noveltyScore());
        assertNotNull(entity.createdAt());
    }

    @Test
    void explicitConstructorClampsNegativeNoveltyAndRejectsNullPatterns() {
        HypothesisCandidateEntity entity = new HypothesisCandidateEntity(
            "hypothesis-negative-novelty",
            "experiment",
            "x",
            "x",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Map.of(),
            CandidateProofStatus.OBSERVED.name(),
            null,
            null,
            List.of(),
            null,
            -4.0,
            Instant.parse("2026-08-09T08:00:00Z"));

        assertEquals(0.0, entity.noveltyScore());
        assertEquals("experiment", entity.experimentId());
        assertEquals("", entity.counterexampleExplanation());

        assertThrows(
            IllegalArgumentException.class,
            () -> new HypothesisCandidateEntity(
                "invalid-pattern",
                null,
                null,
                "x",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                null,
                null,
                0.5,
                Instant.parse("2026-08-09T08:00:00Z")));
    }
}
