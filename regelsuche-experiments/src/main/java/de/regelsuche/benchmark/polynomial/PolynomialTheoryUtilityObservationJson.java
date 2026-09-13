package de.regelsuche.benchmark.polynomial;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.polynomial.PolynomialWorkLedger;

/** Lossless raw evidence encoding used only by the observed freeze revision. */
final class PolynomialTheoryUtilityObservationJson {
    private PolynomialTheoryUtilityObservationJson() { }

    static void append(JsonWriter json, PolynomialTheoryUtilityCandidateResult result) {
        var observations = result.observations();
        var raw = observations.rawWork();
        var projection = PolynomialTheoryUtilityCanonicalWorkProjection.project(result.input(), raw);
        json.object("observations", value -> {
            value.property("schema", PolynomialTheoryUtilityExecutionObservations.SCHEMA);
            value.property("observationId", observations.observationId());
            value.object("rawWork", work -> {
                work.property("projectionRevision", projection.projectionRevision());
                work.property("projectionId", projection.projectionId());
                work.property("rawWorkHash", projection.rawWorkHash());
                work.property("primitiveWork", raw.primitiveWork());
                ledger(work, "totalMechanicalWork", raw.totalMechanicalWork());
                ledger(work, "matchingWork", raw.matchingWork());
                ledger(work, "sourceValidationWork", raw.sourceValidationWork());
                ledger(work, "factorizationWork", raw.factorizationWork());
                ledger(work, "verificationWork", raw.verificationWork());
                ledger(work, "renderingWork", raw.renderingWork());
                ledger(work, "reparseWork", raw.reparseWork());
                ledger(work, "reconstructionWork", raw.reconstructionWork());
                ledger(work, "occurrenceReplacementWork", raw.occurrenceReplacementWork());
                ledger(work, "cacheLookupWork", raw.cacheLookupWork());
                ledger(work, "cacheInsertionWork", raw.cacheInsertionWork());
                ledger(work, "cacheEvictionWork", raw.cacheEvictionWork());
                ledger(work, "cacheReplayWork", raw.cacheReplayWork());
                ledger(work, "evidenceConstructionWork", raw.evidenceConstructionWork());
            });
            ledger(value, "preparationWork", observations.preparationWork());
            value.array("occurrences", occurrences -> observations.occurrences().forEach(occurrence ->
                occurrences.objectValue(item -> {
                    item.property("occurrenceIndex", occurrence.occurrenceIndex());
                    item.array("path", path -> occurrence.path().forEach(path::numberValue));
                    item.property("terminalStatus", occurrence.terminalStatus().name());
                    item.property("detailCode", occurrence.detailCode());
                    item.property("pipelineEvidenceHash", occurrence.pipelineEvidenceHash());
                    item.property("transitionId", occurrence.transitionId());
                    item.property("primitiveWork", occurrence.primitiveWork());
                    ledger(item, "rawWork", occurrence.rawWork());
                    item.stringArray("factorizationAttemptIds", occurrence.factorizationAttemptIds());
                    item.stringArray("cacheEventIds", occurrence.cacheEventIds());
                })
            ));
        });
    }

    private static void ledger(JsonWriter json, String field, PolynomialWorkLedger ledger) {
        json.object(field, value -> ledger.stages().forEach(value::property));
    }
}
