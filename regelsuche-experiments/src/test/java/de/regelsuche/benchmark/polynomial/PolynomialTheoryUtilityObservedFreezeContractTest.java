package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityCandidateResult.TerminalStatus;
import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityExecutionObservations.Occurrence;
import de.regelsuche.json.JsonReader;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** In-memory component batches are serialization fixtures, never study evidence. */
class PolynomialTheoryUtilityObservedFreezeContractTest {
    @Test
    void freezesTheCompleteRawPartitionAndInterruptedReplayOutcome() {
        var replay = PolynomialTheoryUtilityObservedResultContractTest.interruptedReplay().measured();
        var batch = batch(true, replay, 1);
        var freeze = PolynomialTheoryUtilityCandidateFreeze.create(batch);
        assertEquals("regelsuche.polynomial-theory-utility-candidate-batch/v3", batch.candidateBatch().schema());
        assertEquals("regelsuche.polynomial-theory-utility-candidate-measurement-batch/v2", batch.schema());
        assertEquals("regelsuche.polynomial-theory-utility-candidate-freeze/v2", freeze.schema());
        var document = new JsonReader(freeze.canonicalJson()).readObject();
        var rows = (List<?>) document.get("rows");
        var replayRow = rows.stream().map(PolynomialTheoryUtilityObservedFreezeContractTest::object)
            .filter(value -> object(value.get("result")).get("resultId").equals(replay.result().resultId()))
            .findFirst().orElseThrow();
        var observation = object(object(replayRow.get("result")).get("observations"));
        assertEquals(replay.result().observations().observationId(), observation.get("observationId"));
        var raw = object(observation.get("rawWork"));
        var expected = replay.result().observations().rawWork();
        assertEquals(PolynomialTheoryUtilityCanonicalWorkProjection.REVISION, raw.get("projectionRevision"));
        assertEquals(expected.totalMechanicalWork().stages(), ledger(raw.get("totalMechanicalWork")));
        assertEquals(expected.matchingWork().stages(), ledger(raw.get("matchingWork")));
        assertEquals(expected.sourceValidationWork().stages(), ledger(raw.get("sourceValidationWork")));
        assertEquals(expected.factorizationWork().stages(), ledger(raw.get("factorizationWork")));
        assertEquals(expected.verificationWork().stages(), ledger(raw.get("verificationWork")));
        assertEquals(expected.renderingWork().stages(), ledger(raw.get("renderingWork")));
        assertEquals(expected.reparseWork().stages(), ledger(raw.get("reparseWork")));
        assertEquals(expected.reconstructionWork().stages(), ledger(raw.get("reconstructionWork")));
        assertEquals(expected.occurrenceReplacementWork().stages(), ledger(raw.get("occurrenceReplacementWork")));
        assertEquals(expected.cacheLookupWork().stages(), ledger(raw.get("cacheLookupWork")));
        assertEquals(expected.cacheInsertionWork().stages(), ledger(raw.get("cacheInsertionWork")));
        assertEquals(expected.cacheEvictionWork().stages(), ledger(raw.get("cacheEvictionWork")));
        assertEquals(expected.cacheReplayWork().stages(), ledger(raw.get("cacheReplayWork")));
        assertEquals(expected.evidenceConstructionWork().stages(), ledger(raw.get("evidenceConstructionWork")));
        var occurrences = (List<?>) observation.get("occurrences");
        assertEquals(4, occurrences.size());
        assertEquals(replay.result().observations().occurrences().getFirst().rawWork().stages(),
            ledger(object(occurrences.getFirst()).get("rawWork")));
        var cacheEvents = (List<?>) object(replayRow.get("measurements")).get("cacheEvents");
        assertEquals("BUDGET_INCONCLUSIVE", object(cacheEvents.getLast()).get("replayOutcome"));
        assertEquals("HASH_ONLY_NOT_OPENED", document.get("qualificationExposure"));
    }

    @Test
    void changingOnlyRoundedAwayRawWorkChangesTheFreezeCommitment() {
        var first = batch(true, null, 1);
        var second = batch(true, null, 2);
        assertEquals(first.results().getFirst().work(), second.results().getFirst().work());
        assertNotEquals(first.results().getFirst().resultId(), second.results().getFirst().resultId());
        assertNotEquals(first.measurements().getFirst().measurementId(), second.measurements().getFirst().measurementId());
        assertNotEquals(PolynomialTheoryUtilityCandidateFreeze.create(first).contentHash(),
            PolynomialTheoryUtilityCandidateFreeze.create(second).contentHash());
    }

    @Test
    void rejectsMixingHistoricalAndObservedRowsBeforeAReceiptCanBeIssued() {
        var observed = batch(true, null, 0);
        var rows = new ArrayList<>(observed.results());
        var first = rows.getFirst();
        rows.set(0, PolynomialTheoryUtilityCandidateResult.noTransition(first.input(),
            PolynomialTheoryUtilityObservedResultContractTest.formation(first.input().caseId()), first.detailCode()));
        assertThrows(IllegalArgumentException.class, () -> PolynomialTheoryUtilityProfileAdapter.CandidateBatch.create(
            PolynomialTheoryUtilityExecutionInputs.freeze(), rows));
    }

    @Test
    void usesASeparateFileNameAndKeepsHistoricalFreezeBytesReadable(@TempDir Path directory) throws Exception {
        var historical = PolynomialTheoryUtilityCandidateFreeze.create(batch(false, null, 0));
        var observed = PolynomialTheoryUtilityCandidateFreeze.create(batch(true, null, 0));
        var oldPath = historical.write(directory);
        var newPath = observed.write(directory);
        assertEquals("polynomial-theory-utility-candidate-freeze-v1.json", oldPath.getFileName().toString());
        assertEquals("polynomial-theory-utility-candidate-freeze-v2.json", newPath.getFileName().toString());
        assertArrayEquals(historical.bytes(), java.nio.file.Files.readAllBytes(oldPath));
        assertEquals("regelsuche.polynomial-theory-utility-candidate-freeze/v1", historical.schema());
        assertFalse(historical.canonicalJson().contains("\"observations\""));
        assertFalse(historical.canonicalJson().contains("\"replayOutcome\""));
        assertNotEquals(historical.contentHash(), observed.contentHash());
    }

    static PolynomialTheoryUtilityCandidateMeasurementBatch batch(boolean observed,
            PolynomialTheoryUtilityMeasuredCandidate retained, long firstRawUnits) {
        var inputs = PolynomialTheoryUtilityExecutionInputs.freeze();
        var cases = PolynomialTheoryUtilityCaseCorpus.load().cases();
        var results = new ArrayList<PolynomialTheoryUtilityCandidateResult>();
        var measurements = new ArrayList<PolynomialTheoryUtilityCandidateMeasurements>();
        for (int index = 0; index < inputs.inputs().size(); index++) {
            var input = inputs.inputs().get(index);
            var formation = cases.get(index % cases.size());
            if (retained != null && retained.result().input().equals(input)) {
                results.add(retained.result());
                measurements.add(retained.measurements());
                continue;
            }
            String detail = "SERIALIZATION_COMPONENT_" + index;
            PolynomialTheoryUtilityCandidateResult result;
            if (observed) {
                var occurrences = new ArrayList<Occurrence>();
                for (var path : PolynomialTheoryUtilityExecutionObservations.paths(formation)) {
                    occurrences.add(new Occurrence(occurrences.size(), path, TerminalStatus.NO_TRANSITION,
                        detail, "NONE", "NONE", 0, PolynomialWorkLedger.empty(), List.of(), List.of()));
                }
                var preparation = index == 0 && firstRawUnits > 0
                    ? new PolynomialWorkLedger(Map.of("projection.component-structural-hash", firstRawUnits))
                    : PolynomialWorkLedger.empty();
                result = PolynomialTheoryUtilityCandidateResult.createObserved(input, formation, detail, List.of(),
                    "NOT_REQUESTED", PolynomialTheoryUtilityExecutionObservations.create(preparation, occurrences));
            } else {
                result = PolynomialTheoryUtilityCandidateResult.noTransition(input, formation, detail);
            }
            results.add(result);
            measurements.add(PolynomialTheoryUtilityCandidateMeasurements.create(result, List.of(), List.of(), List.of()));
        }
        return PolynomialTheoryUtilityCandidateMeasurementBatch.create(
            PolynomialTheoryUtilityProfileAdapter.CandidateBatch.create(inputs, results), measurements);
    }

    private static Map<?, ?> object(Object value) {
        return (Map<?, ?>) value;
    }

    private static Map<String, Long> ledger(Object value) {
        var result = new java.util.LinkedHashMap<String, Long>();
        object(value).forEach((stage, units) -> result.put((String) stage, ((Number) units).longValue()));
        return result;
    }
}
