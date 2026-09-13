package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityCandidateResult.TerminalStatus;
import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityExecutionObservations.Occurrence;
import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityMeasuredExecution.MeasuredRun;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilityObservedEvidenceIntegrityTest {
    @Test
    void rejectsRebindingThePositivePipelineCertificateWithRecomputedObservationAndResultIds() {
        var result = PolynomialTheoryUtilityObservedResultContractTest.mixedReplay().result();
        var occurrences = new ArrayList<>(result.observations().occurrences());
        var positive = occurrences.getFirst();
        occurrences.set(0, new Occurrence(positive.occurrenceIndex(), positive.path(), positive.terminalStatus(),
            positive.detailCode(), PolynomialTheoryUtilityObservedResultContractTest.hash("foreign-pipeline-certificate"),
            positive.transitionId(), positive.primitiveWork(), positive.rawWork(), positive.factorizationAttemptIds(),
            positive.cacheEventIds()));
        var rebound = PolynomialTheoryUtilityExecutionObservations.create(result.observations().preparationWork(), occurrences);
        assertThrows(IllegalArgumentException.class, () -> rebind(result, rebound));
    }

    @Test
    void rejectsACompletedOccurrenceWhosePipelineEvidenceWasErased() {
        var positive = PolynomialTheoryUtilityObservedResultContractTest.mixedReplay().result()
            .observations().occurrences().getFirst();
        assertEquals(TerminalStatus.VALIDATED_TRANSITION, positive.terminalStatus());
        assertThrows(IllegalArgumentException.class, () -> new Occurrence(positive.occurrenceIndex(), positive.path(),
            positive.terminalStatus(), positive.detailCode(), "NONE", positive.transitionId(), positive.primitiveWork(),
            positive.rawWork(), positive.factorizationAttemptIds(), positive.cacheEventIds()));
    }

    @Test
    void rejectsOmittedReorderedAndReclassifiedOccurrencesIncludingZeroWorkSiblings() {
        var result = PolynomialTheoryUtilityObservedResultContractTest.mixedReplay().result();
        var observations = result.observations();
        var omitted = new ArrayList<>(observations.occurrences());
        omitted.removeLast();
        assertThrows(IllegalArgumentException.class, () -> rebind(result,
            PolynomialTheoryUtilityExecutionObservations.create(observations.preparationWork(), omitted)));
        var reordered = new ArrayList<>(observations.occurrences());
        Collections.swap(reordered, 0, 1);
        assertThrows(IllegalArgumentException.class, () -> rebind(result,
            PolynomialTheoryUtilityExecutionObservations.create(observations.preparationWork(), reordered)));
        var positive = observations.occurrences().getFirst();
        assertThrows(IllegalArgumentException.class, () -> new Occurrence(0, positive.path(),
            TerminalStatus.BUDGET_INCONCLUSIVE, positive.detailCode(), positive.pipelineEvidenceHash(),
            positive.transitionId(), positive.primitiveWork(), positive.rawWork(),
            positive.factorizationAttemptIds(), positive.cacheEventIds()));
        assertThrows(IllegalArgumentException.class, () -> PolynomialTheoryUtilityCandidateResult.createObserved(
            result.input(), PolynomialTheoryUtilityObservedResultContractTest.formation(result.input().caseId()),
            result.detailCode(), List.of(), result.verifierOutcome(), observations));
    }

    @Test
    void rejectsDoubleCountingAndChangingTheRawDimensionPartition() {
        var result = PolynomialTheoryUtilityObservedResultContractTest.mixedReplay().result();
        var observations = result.observations();
        var first = observations.occurrences().getFirst();
        assertThrows(IllegalArgumentException.class, () -> new PolynomialTheoryUtilityExecutionObservations(
            observations.observationId(), observations.rawWork(),
            PolynomialTheoryUtilityExecutionObservations.plus(observations.preparationWork(), first.rawWork()),
            observations.occurrences()));
        var raw = observations.rawWork();
        var wrongDimension = new PolynomialTheoryUtilityCanonicalWorkProjection.RawWork(raw.primitiveWork(),
            raw.totalMechanicalWork(), raw.matchingWork(), raw.sourceValidationWork(),
            PolynomialTheoryUtilityExecutionObservations.plus(raw.factorizationWork(), raw.cacheReplayWork()),
            raw.verificationWork(), raw.renderingWork(), raw.reparseWork(), raw.reconstructionWork(),
            raw.occurrenceReplacementWork(), raw.cacheLookupWork(), raw.cacheInsertionWork(), raw.cacheEvictionWork(),
            PolynomialWorkLedger.empty(), raw.evidenceConstructionWork());
        assertThrows(IllegalArgumentException.class, () -> new PolynomialTheoryUtilityExecutionObservations(
            observations.observationId(), wrongDimension, observations.preparationWork(), observations.occurrences()));
        var wrongPrimitive = PolynomialTheoryUtilityCanonicalWorkProjection.partition(
            raw.primitiveWork() + 1, raw.totalMechanicalWork());
        assertThrows(IllegalArgumentException.class, () -> new PolynomialTheoryUtilityExecutionObservations(
            observations.observationId(), wrongPrimitive, observations.preparationWork(), observations.occurrences()));
    }

    @Test
    void cannotHideInterruptedCacheWorkInPreparationOrLoseItsOccurrenceBinding() {
        var measured = PolynomialTheoryUtilityObservedResultContractTest.interruptedReplay().measured();
        var result = measured.result();
        var occurrences = new ArrayList<>(result.observations().occurrences());
        var first = occurrences.getFirst();
        occurrences.set(0, copy(first, PolynomialWorkLedger.empty(), first.cacheEventIds()));
        var shifted = rebind(result, PolynomialTheoryUtilityExecutionObservations.create(first.rawWork(), occurrences));
        assertEquals(result.work(), shifted.work());
        assertThrows(IllegalArgumentException.class, () -> PolynomialTheoryUtilityCandidateMeasurements.create(
            shifted, List.of(), List.of(), measured.measurements().cacheEvents()));

        occurrences.set(0, copy(first, first.rawWork(), List.of()));
        var omitted = rebind(result, PolynomialTheoryUtilityExecutionObservations.create(
            PolynomialWorkLedger.empty(), occurrences));
        assertThrows(IllegalArgumentException.class, () -> PolynomialTheoryUtilityCandidateMeasurements.create(
            omitted, List.of(), List.of(), measured.measurements().cacheEvents()));
    }

    @Test
    void rejectsWorkShiftedBetweenInterruptedNativeOccurrencesWithRecomputedIds() {
        var input = PolynomialTheoryUtilityObservedResultContractTest.input(
            "ON_DEMAND_VERIFIED_FACTORIZATION", "four-identical-occurrences", "CP03_1_OF_3");
        var execution = PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter.executeObservedCase(
            input, PolynomialTheoryUtilityObservedResultContractTest.formation(input.caseId()));
        var result = execution.measured().result();
        var observations = result.observations();
        var occurrences = new ArrayList<>(observations.occurrences());
        var first = occurrences.getFirst();
        var last = occurrences.getLast();
        assertEquals(TerminalStatus.BUDGET_INCONCLUSIVE, first.terminalStatus());
        assertEquals(TerminalStatus.BUDGET_INCONCLUSIVE, last.terminalStatus());
        assertTrue(first.rawWork().units("projection.study-path-navigation") > 0);
        var shiftedWork = new PolynomialWorkLedger(java.util.Map.of("projection.study-path-navigation", 1L));
        occurrences.set(0, copy(first,
            PolynomialTheoryUtilityExecutionObservations.difference(first.rawWork(), shiftedWork), first.cacheEventIds()));
        occurrences.set(occurrences.size() - 1, copy(last,
            PolynomialTheoryUtilityExecutionObservations.plus(last.rawWork(), shiftedWork), last.cacheEventIds()));
        var reboundObservations = PolynomialTheoryUtilityExecutionObservations.create(
            observations.preparationWork(), occurrences);
        assertEquals(observations.rawWork(), reboundObservations.rawWork());
        assertNotEquals(observations.observationId(), reboundObservations.observationId());
        var rebound = rebind(result, reboundObservations);
        assertEquals(result.work(), rebound.work());
        assertNotEquals(result.resultId(), rebound.resultId());
        var measurements = execution.measured().measurements();
        var measured = PolynomialTheoryUtilityMeasuredCandidate.create(rebound,
            measurements.transitionTraces(), measurements.factorizationAttempts(), measurements.cacheEvents());
        assertNotEquals(measurements.measurementId(), measured.measurements().measurementId());

        assertThrows(IllegalArgumentException.class, () ->
            new PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter.Execution(measured,
                execution.rawWork(), execution.projection(), execution.occurrences(), execution.evidenceHash()));
    }

    @Test
    void rejectsAReplayOutcomeOrEntryReboundEvenWhenAllIdsAreRecomputed() {
        var measured = PolynomialTheoryUtilityObservedResultContractTest.interruptedReplay().measured();
        var result = measured.result();
        var oldEvents = measured.measurements().cacheEvents();
        var old = oldEvents.getLast();
        for (var replacement : List.of(
                PolynomialTheoryUtilityCacheEvent.createReplayAttempt(old.eventIndex(), old.executionInputId(), "NONE",
                    old.cacheRevision(), old.entryId(), old.evidenceHash(), TerminalStatus.TECHNICAL_FAILURE),
                PolynomialTheoryUtilityCacheEvent.createReplayAttempt(old.eventIndex(), old.executionInputId(), "NONE",
                    old.cacheRevision(), PolynomialTheoryUtilityObservedResultContractTest.hash("foreign-entry"),
                    old.evidenceHash(), old.replayOutcome()))) {
            var occurrences = new ArrayList<>(result.observations().occurrences());
            var first = occurrences.getFirst();
            occurrences.set(0, copy(first, first.rawWork(), List.of(oldEvents.getFirst().eventId(), replacement.eventId())));
            var rebound = rebind(result, PolynomialTheoryUtilityExecutionObservations.create(
                PolynomialWorkLedger.empty(), occurrences));
            var failure = assertThrows(IllegalArgumentException.class, () -> PolynomialTheoryUtilityCandidateMeasurements.create(
                rebound, List.of(), List.of(), List.of(oldEvents.getFirst(), replacement)));
            assertEquals("negative replay lacks its lookup entry or terminal outcome", failure.getMessage());
        }
    }

    @Test
    void observedAdapterRunPreservesHistoricWorkAndBindsEveryFactorizationAttempt() {
        var inputs = PolynomialTheoryUtilityExecutionInputs.freeze().inputs().stream()
            .filter(value -> value.profileId().equals("ON_DEMAND_VERIFIED_FACTORIZATION")
                && value.checkpointId().equals("CP06_FULL")).toList();
        var first = inputs.getFirst();
        var evidence = new ArrayList<PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter.Execution>();
        var adapter = PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter.observed(evidence::add);
        var descriptor = new PolynomialTheoryUtilityProfileAdapter.RunDescriptor(first.runId(), first.profileId(),
            first.checkpointId(), first.adapterId(), inputs.size());
        try (var run = (MeasuredRun) adapter.openRun(descriptor)) {
            for (var input : inputs) {
                var formation = PolynomialTheoryUtilityObservedResultContractTest.formation(input.caseId());
                var measured = run.executeMeasured(input, formation);
                var historical = PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter.executeCase(input, formation);
                assertEquals(historical.measured().result().work(), measured.result().work());
                assertEquals(historical.rawWork(), measured.result().observations().rawWork().totalMechanicalWork());
                assertNotEquals(historical.measured().result().resultId(), measured.result().resultId());
                assertEquals(measured.measurements().factorizationAttempts().stream()
                    .map(PolynomialTheoryUtilityFactorizationAttempt::attemptId).toList(),
                    measured.result().observations().occurrences().stream()
                        .flatMap(value -> value.factorizationAttemptIds().stream()).toList());
            }
        }
        assertEquals(inputs.size(), evidence.size());
        var witnessed = evidence.stream().map(value -> value.measured())
            .filter(value -> !value.measurements().factorizationAttempts().isEmpty()).findFirst().orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> PolynomialTheoryUtilityCandidateMeasurements.create(
            witnessed.result(), witnessed.measurements().transitionTraces(), List.of(), List.of()));
    }

    private static PolynomialTheoryUtilityCandidateResult rebind(PolynomialTheoryUtilityCandidateResult result,
            PolynomialTheoryUtilityExecutionObservations observations) {
        return PolynomialTheoryUtilityCandidateResult.createObserved(result.input(),
            PolynomialTheoryUtilityObservedResultContractTest.formation(result.input().caseId()), result.detailCode(),
            result.transitions(), result.verifierOutcome(), observations);
    }

    private static Occurrence copy(Occurrence value, PolynomialWorkLedger raw, List<String> events) {
        return new Occurrence(value.occurrenceIndex(), value.path(), value.terminalStatus(), value.detailCode(),
            value.pipelineEvidenceHash(), value.transitionId(), value.primitiveWork(), raw, value.factorizationAttemptIds(), events);
    }
}
