package de.regelsuche.benchmark.polynomial;

import de.regelsuche.json.JsonWriter;
import java.util.List;

/** Canonical measurement, trace, attempt and cache projections for the freeze. */
final class PolynomialTheoryUtilityCandidateFreezeMeasurementJson {
    private PolynomialTheoryUtilityCandidateFreezeMeasurementJson() {
    }

    static void append(
        JsonWriter row,
        PolynomialTheoryUtilityCandidateMeasurements measurements
    ) {
        row.object("measurements", value -> {
            value.property("schema", measurements.schema());
            value.property(
                "measurementId",
                measurements.measurementId()
            );
            value.property(
                "resultId",
                measurements.result().resultId()
            );
            value.property(
                "formationAssumptionSetId",
                measurements.formationAssumptionSetId()
            );
            value.stringArray(
                "normalizedAssumptions",
                measurements.normalizedAssumptions()
            );
            value.property(
                "sourceAstNodeCount",
                measurements.sourceAstNodeCount()
            );
            appendDerived(value, measurements);
            value.array("transitionTraces", traces ->
                measurements.transitionTraces().forEach(trace ->
                    traces.objectValue(item ->
                        appendTrace(item, trace)
                    )
                )
            );
            value.array("factorizationAttempts", attempts ->
                measurements.factorizationAttempts().forEach(attempt ->
                    attempts.objectValue(item ->
                        appendAttempt(item, attempt)
                    )
                )
            );
            value.array("cacheEvents", events ->
                measurements.cacheEvents().forEach(event ->
                    events.objectValue(item ->
                        appendCacheEvent(item, event)
                    )
                )
            );
        });
    }

    private static void appendDerived(
        JsonWriter json,
        PolynomialTheoryUtilityCandidateMeasurements measurements
    ) {
        json.object("derived", value -> {
            value.property(
                "generatedTransitionCount",
                measurements.generatedTransitionCount()
            );
            intArray(value, "pathDepths", measurements.pathDepths());
            value.property(
                "totalPathDepth",
                measurements.totalPathDepth()
            );
            intArray(
                value,
                "primitiveExpansionLengths",
                measurements.primitiveExpansionLengths()
            );
            value.property(
                "totalPrimitiveExpansionLength",
                measurements.totalPrimitiveExpansionLength()
            );
            intArray(
                value,
                "transformedAstNodeCounts",
                measurements.transformedAstNodeCounts()
            );
            intArray(
                value,
                "astNodeGrowths",
                measurements.astNodeGrowths()
            );
            value.property(
                "factorizationRequestCount",
                measurements.factorizationRequestCount()
            );
            value.property(
                "factorizationCandidateCount",
                measurements.factorizationCandidateCount()
            );
            value.property(
                "cacheHitCount",
                measurements.cacheHitCount()
            );
            value.property(
                "cacheMissCount",
                measurements.cacheMissCount()
            );
            value.property(
                "cacheInsertionCount",
                measurements.cacheInsertionCount()
            );
            value.property(
                "cacheEvictionCount",
                measurements.cacheEvictionCount()
            );
            value.property(
                "cacheReplayCount",
                measurements.cacheReplayCount()
            );
            value.stringArray(
                "primitiveRuleIds",
                measurements.primitiveRuleIds()
            );
        });
    }

    private static void appendTrace(
        JsonWriter json,
        PolynomialTheoryUtilityTransitionTrace trace
    ) {
        json.property("schema", trace.schema());
        json.property("traceId", trace.traceId());
        json.property(
            "transitionId",
            trace.transition().transitionId()
        );
        json.property("pathDepth", trace.pathDepth());
        json.property(
            "primitiveExpansionLength",
            trace.primitiveExpansionLength()
        );
        json.stringArray(
            "normalizedAssumptions",
            trace.normalizedAssumptions()
        );
        json.property(
            "sourceAstNodeCount",
            trace.sourceAstNodeCount()
        );
        json.property(
            "transformedAstNodeCount",
            trace.transformedAstNodeCount()
        );
        json.property("astNodeGrowth", trace.astNodeGrowth());
        json.array("primitiveSteps", steps ->
            trace.primitiveSteps().forEach(step ->
                steps.objectValue(item ->
                    appendPrimitiveStep(item, step)
                )
            )
        );
    }

    private static void appendPrimitiveStep(
        JsonWriter json,
        PolynomialTheoryUtilityTransitionTrace.PrimitiveStep step
    ) {
        json.property("stepId", step.stepId());
        json.property("primitiveIndex", step.primitiveIndex());
        json.property("pathEdgeIndex", step.pathEdgeIndex());
        json.property("transitionId", step.transitionId());
        json.property("ruleId", step.ruleId());
        json.property("evidenceHash", step.evidenceHash());
    }

    private static void appendAttempt(
        JsonWriter json,
        PolynomialTheoryUtilityFactorizationAttempt attempt
    ) {
        json.property("schema", attempt.schema());
        json.property("attemptId", attempt.attemptId());
        json.property("attemptIndex", attempt.attemptIndex());
        json.property(
            "executionInputId",
            attempt.executionInputId()
        );
        json.property("backendId", attempt.backendId());
        json.property("requestId", attempt.requestId());
        json.property(
            "requestEvidenceHash",
            attempt.requestEvidenceHash()
        );
        json.stringArray("candidateIds", attempt.candidateIds());
        json.property(
            "selectedCandidateId",
            attempt.selectedCandidateId()
        );
        json.property("transitionId", attempt.transitionId());
        json.property("verifierOutcome", attempt.verifierOutcome());
        json.property(
            "reportEvidenceHash",
            attempt.reportEvidenceHash()
        );
    }

    private static void appendCacheEvent(
        JsonWriter json,
        PolynomialTheoryUtilityCacheEvent event
    ) {
        json.property("schema", event.schema());
        json.property("eventId", event.eventId());
        json.property("eventIndex", event.eventIndex());
        json.property(
            "executionInputId",
            event.executionInputId()
        );
        json.property("transitionId", event.transitionId());
        json.property("kind", event.kind().name());
        json.property("cacheRevision", event.cacheRevision());
        json.property("entryId", event.entryId());
        json.property("evidenceHash", event.evidenceHash());
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
