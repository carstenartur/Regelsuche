package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityMeasuredExecution.MeasuredRun;
import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityProfileAdapter.RunDescriptor;
import de.regelsuche.polynomial.BinaryQuarticFactorizationEngine;
import de.regelsuche.polynomial.PolynomialWorkAuthority;
import de.regelsuche.moves.enumerate.TreePosition;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.transform.PolynomialDecompositionSynthesisOperator;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilitySpecializedAdapterTest {
    @Test
    void positiveSpecializedAttemptKeepsItsIssuedRequestAndCompleteLedger() {
        var parsed = new ExpressionParser().parseExactTerm("x^4 + 4");
        // An issuer-binding component control, never a study result or budget.
        var pipeline = new PolynomialDecompositionSynthesisOperator().factorExpression(parsed,
            new TreePosition(List.of(), ExpressionFormatter.format(parsed.expression())), PolynomialWorkAuthority.unbounded());
        assertTrue(pipeline.generated());
        var input = PolynomialTheoryUtilityExecutionInputs.freeze().inputs().stream()
            .filter(value -> value.profileId().equals("SPECIALIZED_BINARY_QUARTIC_CONTROL")).findFirst().orElseThrow();
        var attempt = PolynomialTheoryUtilityFactorizationAttempt.createObserved(0, input.inputId(), 0, pipeline, "NONE");
        assertSame(pipeline.rawWork(), attempt.observedExecution().rawWork());
        assertEquals(pipeline.certificateHash(), attempt.observedExecution().pipelineEvidenceHash());
        assertEquals(pipeline.report().orElseThrow().candidates().getFirst().verificationCertificateHash(), attempt.selectedCandidateId());
        var failure = assertThrows(IllegalArgumentException.class, () -> new PolynomialTheoryUtilityFactorizationAttempt(
            attempt.attemptId(), 0, input.inputId(), attempt.backendId(), "sha256:" + "f".repeat(64),
            attempt.requestEvidenceHash(), attempt.candidateIds(), attempt.selectedCandidateId(), "NONE",
            attempt.verifierOutcome(), attempt.reportEvidenceHash(), attempt.observedExecution()));
        assertEquals("observed attempt metadata differs from its actual pipeline report", failure.getMessage());
    }

    @Test
    void publicCheckpointRetainsTheSpecializedBackendAndEveryOccurrence() {
        var adapter = new PolynomialTheoryUtilitySpecializedAdapter();
        var inputs = PolynomialTheoryUtilityExecutionInputs.freeze().inputs().stream()
            .filter(value -> value.profileId().equals("SPECIALIZED_BINARY_QUARTIC_CONTROL")
                && value.checkpointId().equals("CP06_FULL")).toList();
        var formation = PolynomialTheoryUtilityCaseCorpus.load().cases();
        var first = inputs.getFirst();
        var measured = new ArrayList<PolynomialTheoryUtilityMeasuredCandidate>();
        try (var run = (MeasuredRun) adapter.openRun(new RunDescriptor(first.runId(), first.profileId(),
                first.checkpointId(), first.adapterId(), inputs.size()))) {
            for (int index = 0; index < inputs.size(); index++) {
                measured.add(run.executeObserved(inputs.get(index), formation.get(index)));
            }
        }
        assertEquals(20, measured.size());
        assertTrue(measured.stream().flatMap(value -> value.measurements().factorizationAttempts().stream()).findAny().isPresent());
        for (int index = 0; index < measured.size(); index++) {
            var result = measured.get(index).result();
            assertEquals(PolynomialTheoryUtilityExecutionObservations.paths(formation.get(index)),
                result.observations().occurrences().stream().map(value -> value.path()).toList());
            assertTrue(result.work().mechanicalWork() <= inputs.get(index).totalMechanicalWork());
            assertTrue(result.work().factorizationWork() <= inputs.get(index).factorizationWork());
            assertTrue(measured.get(index).measurements().cacheEvents().isEmpty());
            for (var attempt : measured.get(index).measurements().factorizationAttempts()) {
                assertEquals(BinaryQuarticFactorizationEngine.ENGINE_ID, attempt.backendId());
                assertNotNull(attempt.observedExecution());
                assertTrue(attempt.observedExecution().rawWork().units("factorization.request-dispatch") > 0);
            }
        }
    }
}
