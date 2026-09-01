package de.regelsuche.benchmark.polynomial;

import de.regelsuche.json.JsonWriter;
import java.util.List;

/** Canonical input, result, transition and work projections for the freeze. */
final class PolynomialTheoryUtilityCandidateFreezeResultJson {
    private PolynomialTheoryUtilityCandidateFreezeResultJson() {
    }

    static void appendInput(
        JsonWriter row,
        PolynomialTheoryUtilityExecutionInput input
    ) {
        row.object("input", value -> {
            value.property("inputId", input.inputId());
            value.property("rowId", input.rowId());
            value.property("runId", input.runId());
            value.property("caseId", input.caseId());
            value.property("profileId", input.profileId());
            value.property("checkpointId", input.checkpointId());
            value.property("adapterId", input.adapterId());
            value.property(
                "admittedPrimitiveWork",
                input.admittedPrimitiveWork()
            );
            value.property(
                "totalMechanicalWork",
                input.totalMechanicalWork()
            );
            value.property(
                "factorizationWork",
                input.factorizationWork()
            );
            value.property("inputStatus", input.inputStatus());
        });
    }

    static void appendResult(
        JsonWriter row,
        PolynomialTheoryUtilityCandidateResult result
    ) {
        row.object("result", value -> {
            value.property("schema", result.schema());
            value.property("resultId", result.resultId());
            value.property("inputId", result.input().inputId());
            value.property(
                "sourceRootExpression",
                result.sourceRootExpression()
            );
            value.property(
                "terminalStatus",
                result.terminalStatus().name()
            );
            value.property("detailCode", result.detailCode());
            value.property("verifierOutcome", result.verifierOutcome());
            value.property(
                "transitionEvidenceHash",
                result.transitionEvidenceHash()
            );
            appendWork(value, "work", result.work());
            value.array("transitions", transitions ->
                result.transitions().forEach(transition ->
                    transitions.objectValue(item ->
                        appendTransition(item, transition)
                    )
                )
            );
        });
    }

    private static void appendTransition(
        JsonWriter json,
        PolynomialTheoryUtilityTransitionOutcome transition
    ) {
        json.property("schema", transition.schema());
        json.property("transitionId", transition.transitionId());
        json.property("transitionIndex", transition.transitionIndex());
        json.property(
            "executionInputId",
            transition.executionInputId()
        );
        intArray(json, "occurrencePath", transition.occurrencePath());
        json.property(
            "sourceOccurrenceExpression",
            transition.sourceOccurrenceExpression()
        );
        json.property(
            "transformedOccurrenceExpression",
            transition.transformedOccurrenceExpression()
        );
        json.property(
            "sourceRootExpression",
            transition.sourceRootExpression()
        );
        json.property(
            "transformedRootExpression",
            transition.transformedRootExpression()
        );
        json.property(
            "transformationId",
            transition.transformationId()
        );
        json.property("backendId", transition.backendId());
        json.property(
            "sourceEvidenceHash",
            transition.sourceEvidenceHash()
        );
        json.property(
            "transitionEvidenceHash",
            transition.transitionEvidenceHash()
        );
        json.property(
            "cacheDisposition",
            transition.cacheDisposition().name()
        );
        json.property("cacheRevision", transition.cacheRevision());
        json.property("cacheEntryId", transition.cacheEntryId());
        json.property(
            "evictedCacheEntryId",
            transition.evictedCacheEntryId()
        );
        appendWork(json, "work", transition.work());
    }

    private static void appendWork(
        JsonWriter json,
        String field,
        PolynomialTheoryUtilityWorkBreakdown work
    ) {
        json.object(field, value -> {
            value.property("primitiveWork", work.primitiveWork());
            value.property("matchingWork", work.matchingWork());
            value.property(
                "sourceValidationWork",
                work.sourceValidationWork()
            );
            value.property(
                "factorizationWork",
                work.factorizationWork()
            );
            value.property(
                "verificationWork",
                work.verificationWork()
            );
            value.property("renderingWork", work.renderingWork());
            value.property("reparseWork", work.reparseWork());
            value.property(
                "reconstructionWork",
                work.reconstructionWork()
            );
            value.property(
                "occurrenceReplacementWork",
                work.occurrenceReplacementWork()
            );
            value.property(
                "cacheLookupWork",
                work.cacheLookupWork()
            );
            value.property(
                "cacheInsertionWork",
                work.cacheInsertionWork()
            );
            value.property(
                "cacheEvictionWork",
                work.cacheEvictionWork()
            );
            value.property(
                "cacheReplayWork",
                work.cacheReplayWork()
            );
            value.property(
                "evidenceConstructionWork",
                work.evidenceConstructionWork()
            );
            value.property("mechanicalWork", work.mechanicalWork());
            value.property("totalWork", work.totalWork());
        });
    }

    private static void intArray(
        JsonWriter json,
        String field,
        List<Integer> values
    ) {
        json.array(field, array ->
            values.forEach(value -> array.numberValue(value))
        );
    }
}
