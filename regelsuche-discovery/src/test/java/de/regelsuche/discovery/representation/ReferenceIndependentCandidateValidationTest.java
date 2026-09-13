package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation.ReferenceIndependentValidationFixtures.FREEZE;
import static de.regelsuche.discovery.representation.ReferenceIndependentValidationFixtures.FREEZE_HASH;
import static de.regelsuche.discovery.representation.ReferenceIndependentValidationFixtures.PLAN;
import static de.regelsuche.discovery.representation.ReferenceIndependentValidationFixtures.REVISION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.validation.SymPyOracleValidator;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ReferenceIndependentCandidateValidationTest {
    @Test
    void validatesEveryArchivedLineageWithoutOpeningHistoricalQualification() {
        var artifact = Evidence.ARTIFACT;
        assertEquals(144, artifact.content().rows().size());
        assertEquals(4316, artifact.content().summary().candidates());
        assertEquals(4316, artifact.content().summary().oracleCalls());
        assertEquals(FREEZE_HASH, artifact.content().candidateFreezeHash());
        var original = FreezeArtifact.fromCanonicalJson(FREEZE);
        for (int index = 0; index < 144; index++) {
            assertEquals(original.content().rows().get(index).candidates()
                    .stream().map(CandidateEvidence::candidateHash).toList(),
                artifact.content().rows().get(index).outcomes().stream()
                    .map(outcome -> outcome.candidate().candidateHash()).toList());
        }
        assertFalse(artifact.toCanonicalJson().contains("referenceMatched"));
        assertFalse(artifact.toCanonicalJson().contains("expectedOutcome"));
        assertFalse(artifact.toCanonicalJson().contains("qualificationHash"));
        assertEquals(artifact, ReferenceIndependentCandidateValidation.Artifact
            .fromCanonicalJson(artifact.toCanonicalJson()));
    }

    @Test
    void rejectsWrongExpectedFreezeBeforeAnyOracleWork() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IllegalArgumentException.class, () ->
            ReferenceIndependentCandidateValidationRunner.run(
                PLAN, FREEZE, "sha256:" + "0".repeat(64), REVISION,
                budget(4316), (left, right) -> {
                    calls.incrementAndGet();
                    return new SymPyOracleValidator()
                        .validateEquivalence(left, right);
                }));
        assertEquals(0, calls.get());
    }

    @Test
    void exhaustedOracleBudgetRetainsEveryCandidateAndChargesOnlyAdmittedCalls() {
        AtomicInteger calls = new AtomicInteger();
        var artifact = ReferenceIndependentCandidateValidationRunner.run(
            PLAN, FREEZE, FREEZE_HASH, REVISION, budget(2),
            (left, right) -> {
                calls.incrementAndGet();
                return new SymPyOracleValidator().validateEquivalence(left, right);
            });
        assertEquals(4316, artifact.content().summary().candidates());
        assertEquals(2, calls.get());
        assertEquals(2, artifact.content().summary().oracleCalls());
        assertEquals(4314L, artifact.content().rows().stream()
            .flatMap(row -> row.outcomes().stream())
            .filter(outcome -> outcome.terminalReason().equals("ORACLE_BUDGET_EXHAUSTED"))
            .count());
    }

    @Test
    void distinguishesExistingNumericalOracleEvidenceFromExactOrFormalProof() {
        var outcomes = Evidence.ARTIFACT.content().rows().stream()
            .flatMap(row -> row.outcomes().stream()).toList();
        assertTrue(outcomes.stream().anyMatch(outcome ->
            outcome.validation().oracleStatus().equals("AGREE")
                && outcome.evidenceStrength().equals("DETERMINISTIC_NUMERIC_SAMPLES")));
        assertTrue(outcomes.stream().allMatch(outcome ->
            outcome.formalProofStatus().equals("NOT_ESTABLISHED")
                && outcome.primitiveReplayStatus().equals("FROZEN_LINEAGE_NOT_REPLAYED")));
    }

    static ReferenceIndependentCandidateValidation.Budget budget(int calls) {
        return new ReferenceIndependentCandidateValidation.Budget(calls, 8192, 5000);
    }

    static final class Evidence {
        static final ReferenceIndependentCandidateValidation.Artifact ARTIFACT =
            ReferenceIndependentCandidateValidationRunner.run(
                PLAN, FREEZE, FREEZE_HASH, REVISION, budget(4316),
                new SymPyOracleValidator());
    }
}
