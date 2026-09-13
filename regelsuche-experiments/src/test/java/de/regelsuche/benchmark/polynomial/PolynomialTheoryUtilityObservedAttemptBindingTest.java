package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityExecutionObservations.Occurrence;
import de.regelsuche.json.JsonReader;
import de.regelsuche.math.algorithms.polynomial.NativeUnivariateFactorizationEngine;
import de.regelsuche.math.algorithms.polynomial.NativeUnivariateFactorizationPolicy;
import de.regelsuche.moves.enumerate.TreePosition;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.polynomial.ExactNestedFactorizationTransformationPipeline;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilityObservedAttemptBindingTest {
    @Test
    void rejectsAnExistingAttemptWithAReplacedRequestHashAndCompletelyRecomputedIds() {
        var execution = witness();
        var original = execution.measured().measurements().factorizationAttempts().getFirst();
        var failure = assertThrows(IllegalArgumentException.class, () -> measured(execution, List.of(metadata(original,
            PolynomialTheoryUtilityObservedResultContractTest.hash("foreign-request"), original.reportEvidenceHash())), null));
        assertEquals("observed attempt metadata differs from its actual pipeline report", failure.getMessage());
    }

    @Test
    void rejectsAnExistingAttemptWithAReplacedReportHashAndCompletelyRecomputedIds() {
        var execution = witness();
        var original = execution.measured().measurements().factorizationAttempts().getFirst();
        var failure = assertThrows(IllegalArgumentException.class, () -> measured(execution, List.of(metadata(original,
            original.requestEvidenceHash(), PolynomialTheoryUtilityObservedResultContractTest.hash("foreign-report"))), null));
        assertEquals("observed attempt metadata differs from its actual pipeline report", failure.getMessage());
    }

    @Test
    void rejectsAnActualAttemptMovedFromAnotherSourceAndOccurrencePath() {
        var execution = witness();
        var nestedFormation = formation("nested-single-occurrence");
        var parsed = new ExpressionParser().parseExactTerm(nestedFormation.sourceExpression());
        var path = List.of(1);
        var subtree = new TreePosition(path, "pending").subtreeAt(parsed.expression()).orElseThrow();
        var nested = new ExactNestedFactorizationTransformationPipeline().transform(parsed,
            new TreePosition(path, ExpressionFormatter.format(subtree)),
            NativeUnivariateFactorizationEngine.rationals(NativeUnivariateFactorizationPolicy.boundedDefaults()), 0);
        var moved = PolynomialTheoryUtilityFactorizationAttempt.createObserved(0,
            execution.measured().result().input().inputId(), 0, nested, "NONE");
        var failure = assertThrows(IllegalArgumentException.class, () -> measured(execution, List.of(moved), null));
        assertEquals("observed attempt belongs to another source or occurrence pipeline", failure.getMessage());
    }

    @Test
    void rejectsMovingAnAttemptAndItsFullLedgerToAnIdenticalSiblingInTheSameSource() {
        var input = PolynomialTheoryUtilityExecutionInputs.freeze().inputs().stream()
            .filter(value -> value.profileId().equals("ON_DEMAND_VERIFIED_FACTORIZATION")
                && value.caseId().equals("two-identical-occurrences") && value.checkpointId().equals("CP06_FULL"))
            .findFirst().orElseThrow();
        var execution = PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter.executeObservedCase(
            input, formation(input.caseId()));
        var original = execution.measured();
        var result = original.result();
        assertEquals(List.of(), result.transitions());
        assertEquals(1, original.measurements().factorizationAttempts().size());
        var first = result.observations().occurrences().getFirst();
        var second = result.observations().occurrences().getLast();
        var shifted = PolynomialTheoryUtilityExecutionObservations.create(result.observations().preparationWork(),
            List.of(atPosition(second, first), atPosition(first, second)));
        assertEquals(result.observations().rawWork(), shifted.rawWork());
        var rebound = PolynomialTheoryUtilityCandidateResult.createObserved(input, formation(input.caseId()),
            result.detailCode(), result.transitions(), result.verifierOutcome(), shifted);
        var failure = assertThrows(IllegalArgumentException.class, () -> PolynomialTheoryUtilityMeasuredCandidate.create(
            rebound, original.measurements().transitionTraces(), original.measurements().factorizationAttempts(),
            original.measurements().cacheEvents()));
        assertEquals("observed attempt belongs to another source or occurrence pipeline", failure.getMessage());
    }

    @Test
    void rejectsRetainingOnlyPartOfAnActualAttemptLedgerAfterRehashingThePackage() {
        var execution = witness();
        var result = execution.measured().result();
        var raw = result.observations().occurrences().getFirst().rawWork();
        var factorization = PolynomialTheoryUtilityCanonicalWorkProjection.partition(0, raw).factorizationWork();
        var stage = factorization.stages().entrySet().stream().filter(value -> value.getValue() > 1)
            .findFirst().orElseThrow();
        var partial = new LinkedHashMap<>(raw.stages());
        partial.put(stage.getKey(), stage.getValue() / 2);

        var failure = assertThrows(IllegalArgumentException.class, () -> measured(execution,
            execution.measured().measurements().factorizationAttempts(), new PolynomialWorkLedger(partial)));
        assertTrue(failure.getMessage().startsWith("observed attempt raw work differs"));
    }

    @Test
    void rejectsAnExtraValidAttemptWithRecomputedIdsButNoAdditionalWorkOrPipeline() {
        var execution = witness();
        var measured = execution.measured();
        var result = measured.result();
        var original = measured.measurements().factorizationAttempts().getFirst();
        var extra = PolynomialTheoryUtilityFactorizationAttempt.createObserved(
            measured.measurements().factorizationAttempts().size(), original.executionInputId(), 0,
            execution.occurrences().getFirst().pipeline(), "NONE");
        var attempts = new ArrayList<>(measured.measurements().factorizationAttempts());
        attempts.add(extra);
        var occurrences = new ArrayList<>(result.observations().occurrences());
        var own = occurrences.getLast();
        assertEquals(List.of(original.attemptId()), own.factorizationAttemptIds());
        occurrences.set(occurrences.size() - 1, new Occurrence(own.occurrenceIndex(), own.path(),
            own.terminalStatus(), own.detailCode(), own.pipelineEvidenceHash(), own.transitionId(),
            own.primitiveWork(), own.rawWork(), List.of(original.attemptId(), extra.attemptId()), own.cacheEventIds()));
        var observed = PolynomialTheoryUtilityExecutionObservations.create(
            result.observations().preparationWork(), occurrences);
        var rebound = PolynomialTheoryUtilityCandidateResult.createObserved(result.input(), formation(result.input().caseId()),
            result.detailCode(), result.transitions(), result.verifierOutcome(), observed);
        assertEquals(result.work(), rebound.work());
        assertEquals(result.observations().rawWork(), rebound.observations().rawWork());

        var failure = assertThrows(IllegalArgumentException.class, () -> {
            var forged = PolynomialTheoryUtilityMeasuredCandidate.create(rebound,
                measured.measurements().transitionTraces(), attempts, measured.measurements().cacheEvents());
            new PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter.Execution(forged,
                execution.rawWork(), execution.projection(), execution.occurrences(), execution.evidenceHash());
        });
        assertEquals("observed attempts repeat the same pipeline execution", failure.getMessage());
    }

    @Test
    void keepsTheHistoricalAttemptConstructorAndIdentityWhileRejectingObservedDowngrade() {
        var execution = witness();
        var observed = execution.measured().measurements().factorizationAttempts().getFirst();
        var historical = PolynomialTheoryUtilityFactorizationAttempt.create(observed.attemptIndex(),
            observed.executionInputId(), observed.backendId(), observed.requestId(), observed.requestEvidenceHash(),
            observed.candidateIds(), observed.selectedCandidateId(), observed.transitionId(), observed.verifierOutcome(),
            observed.reportEvidenceHash());
        var reconstructed = new PolynomialTheoryUtilityFactorizationAttempt(historical.attemptId(), historical.attemptIndex(),
            historical.executionInputId(), historical.backendId(), historical.requestId(), historical.requestEvidenceHash(),
            historical.candidateIds(), historical.selectedCandidateId(), historical.transitionId(), historical.verifierOutcome(),
            historical.reportEvidenceHash());
        assertEquals(historical, reconstructed);
        assertNull(historical.observedExecution());
        assertEquals("regelsuche.polynomial-theory-utility-factorization-attempt/v1", historical.schema());
        assertEquals("regelsuche.polynomial-theory-utility-factorization-attempt/v2", observed.schema());
        assertNotEquals(historical.attemptId(), observed.attemptId());
        assertThrows(IllegalArgumentException.class, () -> measured(execution, List.of(historical), null));
    }

    @Test
    void retainsTheActualPipelineLedgerAndReproducesItsBoundAttemptIdentity() {
        var first = witness();
        var second = witness();
        var observed = first.measured().measurements().factorizationAttempts().getFirst();
        var binding = observed.observedExecution();
        var pipeline = first.occurrences().getFirst().pipeline();
        assertEquals(pipeline.factorization().orElseThrow().totalWork(), binding.rawWork());
        assertEquals(pipeline.position().path(), binding.path());
        assertEquals(pipeline.certificateHash(), binding.pipelineEvidenceHash());
        assertEquals(observed, metadata(observed, observed.requestEvidenceHash(), observed.reportEvidenceHash()));
        assertEquals(first.measured(), second.measured());
    }

    @Test
    void serializesTheBoundAttemptRevisionAndAllItsActualWorkWithoutWritingAStudyArtifact() {
        var measured = witness().measured();
        var attempt = measured.measurements().factorizationAttempts().getFirst();
        var batch = PolynomialTheoryUtilityObservedFreezeContractTest.batch(true, measured, 0);
        var freeze = PolynomialTheoryUtilityCandidateFreeze.create(batch);
        var document = new JsonReader(freeze.canonicalJson()).readObject();
        var row = ((List<?>) document.get("rows")).stream().map(value -> (Map<?, ?>) value)
            .filter(value -> ((Map<?, ?>) value.get("result")).get("resultId").equals(measured.result().resultId()))
            .findFirst().orElseThrow();
        var stored = (Map<?, ?>) ((List<?>) ((Map<?, ?>) row.get("measurements")).get("factorizationAttempts")).getFirst();
        var binding = (Map<?, ?>) stored.get("observedExecution");
        assertEquals("regelsuche.polynomial-theory-utility-factorization-attempt/v2", stored.get("schema"));
        assertEquals(attempt.attemptId(), stored.get("attemptId"));
        assertEquals(attempt.observedExecution().occurrenceIndex(), ((Number) binding.get("occurrenceIndex")).intValue());
        assertEquals(attempt.observedExecution().path(), ((List<?>) binding.get("path")).stream()
            .map(value -> ((Number) value).intValue()).toList());
        assertEquals(attempt.observedExecution().pipelineEvidenceHash(), binding.get("pipelineEvidenceHash"));
        assertEquals(attempt.observedExecution().sourceRootEvidenceHash(), binding.get("sourceRootEvidenceHash"));
        var ledger = new LinkedHashMap<String, Long>();
        ((Map<?, ?>) binding.get("rawWork")).forEach((stage, units) -> ledger.put((String) stage, ((Number) units).longValue()));
        assertEquals(attempt.observedExecution().rawWork(), new PolynomialWorkLedger(ledger));
        freeze.requireVerified(freeze.bytes());
    }

    @Test
    void preservesTheOriginalHistoricalReceiptContainingAnActualFactorizationAttempt() {
        // Pinned by compiling the unchanged production sources from 6cf6db945f58.
        // Other rows are synthetic serialization controls, not a study execution.
        var input = PolynomialTheoryUtilityExecutionInputs.freeze().inputs().stream()
            .filter(value -> value.profileId().equals("ON_DEMAND_VERIFIED_FACTORIZATION")
                && value.checkpointId().equals("CP06_FULL") && value.caseId().equals("z03-cubic-unity"))
            .findFirst().orElseThrow();
        var historical = PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter.executeCase(
            input, formation(input.caseId())).measured();
        assertEquals(1, historical.measurements().factorizationAttempts().size());
        assertEquals("sha256:304ba0050567dfdcb3b97c446f3024b4d0954289b454ca17c4278eddfcddcc51",
            historical.measurements().factorizationAttempts().getFirst().attemptId());
        var freeze = PolynomialTheoryUtilityCandidateFreeze.create(
            PolynomialTheoryUtilityObservedFreezeContractTest.batch(false, historical, 0));
        assertEquals("sha256:3edd27c493faa03bba877903a5c19f8755e8a1824531a4dd880d842a2a5c6c6a", freeze.contentHash());
        assertEquals(1_301_094, freeze.byteLength());
        assertFalse(freeze.canonicalJson().contains("\"observedExecution\""));
        freeze.requireVerified(freeze.bytes());
    }

    private static Occurrence atPosition(Occurrence evidence, Occurrence position) {
        return new Occurrence(position.occurrenceIndex(), position.path(), evidence.terminalStatus(), evidence.detailCode(),
            evidence.pipelineEvidenceHash(), evidence.transitionId(), evidence.primitiveWork(), evidence.rawWork(),
            evidence.factorizationAttemptIds(), evidence.cacheEventIds());
    }

    private static PolynomialTheoryUtilityFactorizationAttempt metadata(
            PolynomialTheoryUtilityFactorizationAttempt original, String requestEvidence, String reportEvidence) {
        var legacy = PolynomialTheoryUtilityFactorizationAttempt.create(original.attemptIndex(), original.executionInputId(),
            original.backendId(), original.requestId(), requestEvidence, original.candidateIds(),
            original.selectedCandidateId(), original.transitionId(), original.verifierOutcome(), reportEvidence);
        var binding = original.observedExecution();
        String schema = "regelsuche.polynomial-theory-utility-factorization-attempt/v2";
        // Recompute the complete public v2 identity, rather than relying on a stale-ID rejection.
        String reboundId = PolynomialTheoryUtilityObservedResultContractTest.hash(
            frame(schema) + frame(legacy.attemptId()) + frame(binding.canonicalMaterial()));
        return new PolynomialTheoryUtilityFactorizationAttempt(reboundId, legacy.attemptIndex(), legacy.executionInputId(),
            legacy.backendId(), legacy.requestId(), legacy.requestEvidenceHash(), legacy.candidateIds(),
            legacy.selectedCandidateId(), legacy.transitionId(), legacy.verifierOutcome(), legacy.reportEvidenceHash(), binding);
    }

    private static String frame(String value) { return value.length() + ":" + value; }

    private static PolynomialTheoryUtilityMeasuredCandidate measured(
            PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter.Execution execution,
            List<PolynomialTheoryUtilityFactorizationAttempt> attempts, PolynomialWorkLedger replacementRaw) {
        var original = execution.measured();
        var result = original.result();
        var own = result.observations().occurrences().getFirst();
        var occurrence = new Occurrence(own.occurrenceIndex(), own.path(), own.terminalStatus(), own.detailCode(),
            own.pipelineEvidenceHash(), own.transitionId(), own.primitiveWork(),
            replacementRaw == null ? own.rawWork() : replacementRaw,
            attempts.stream().map(PolynomialTheoryUtilityFactorizationAttempt::attemptId).toList(), own.cacheEventIds());
        var observations = PolynomialTheoryUtilityExecutionObservations.create(
            result.observations().preparationWork(), List.of(occurrence));
        var rebound = PolynomialTheoryUtilityCandidateResult.createObserved(result.input(), formation(result.input().caseId()),
            result.detailCode(), result.transitions(), result.verifierOutcome(), observations);
        return PolynomialTheoryUtilityMeasuredCandidate.create(rebound,
            original.measurements().transitionTraces(), attempts, original.measurements().cacheEvents());
    }

    private static PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter.Execution witness() {
        for (var input : PolynomialTheoryUtilityExecutionInputs.freeze().inputs()) {
            if (!input.profileId().equals("ON_DEMAND_VERIFIED_FACTORIZATION")
                    || !input.checkpointId().equals("CP06_FULL")) continue;
            var execution = PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter.executeObservedCase(
                input, formation(input.caseId()));
            if (execution.measured().measurements().factorizationAttempts().size() == 1
                    && execution.measured().result().observations().occurrences().size() == 1) return execution;
        }
        throw new AssertionError("public native controls did not retain a factorization attempt");
    }

    private static PolynomialTheoryUtilityCaseCorpus.FormationCase formation(String caseId) {
        return PolynomialTheoryUtilityCaseCorpus.load().cases().stream()
            .filter(value -> value.caseId().equals(caseId)).findFirst().orElseThrow();
    }
}
