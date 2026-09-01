package de.regelsuche.benchmark.polynomial;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.util.AtomicJsonFile;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Canonical target-blind freeze of a complete measured candidate batch. */
public record PolynomialTheoryUtilityCandidateFreeze(
    String contentHash,
    long byteLength,
    PolynomialTheoryUtilityCandidateMeasurementBatch measuredBatch,
    String canonicalJson
) {
    public static final String SCHEMA =
        "regelsuche.polynomial-theory-utility-candidate-freeze/v1";
    public static final String FILE_NAME =
        "polynomial-theory-utility-candidate-freeze-v1.json";
    public static final String EVIDENCE_STATUS =
        "CANDIDATES_FROZEN_QUALIFICATION_NOT_OPENED";
    public static final String QUALIFICATION_EXPOSURE =
        "HASH_ONLY_NOT_OPENED";

    private static final Pattern SHA_256 =
        Pattern.compile("sha256:[0-9a-f]{64}");

    public PolynomialTheoryUtilityCandidateFreeze {
        contentHash = requireHash(contentHash, "contentHash");
        if (byteLength < 1L) {
            throw new IllegalArgumentException(
                "candidate freeze byteLength must be positive"
            );
        }
        measuredBatch = Objects.requireNonNull(
            measuredBatch,
            "measuredBatch"
        );
        canonicalJson = requireText(canonicalJson, "canonicalJson");

        String expected = canonical(measuredBatch);
        if (!canonicalJson.equals(expected)) {
            throw new IllegalArgumentException(
                "candidate freeze differs from canonical measured bytes"
            );
        }
        byte[] bytes = utf8(canonicalJson);
        if (byteLength != bytes.length
                || !contentHash.equals(
                    PolynomialTheoryUtilityExecutionIdentity.sha256(bytes)
                )) {
            throw new IllegalArgumentException(
                "candidate freeze identity differs from canonical bytes"
            );
        }
    }

    public static PolynomialTheoryUtilityCandidateFreeze create(
        PolynomialTheoryUtilityCandidateMeasurementBatch measuredBatch
    ) {
        var retained = Objects.requireNonNull(
            measuredBatch,
            "measuredBatch"
        );
        String canonical = canonical(retained);
        byte[] bytes = utf8(canonical);
        return new PolynomialTheoryUtilityCandidateFreeze(
            PolynomialTheoryUtilityExecutionIdentity.sha256(bytes),
            bytes.length,
            retained,
            canonical
        );
    }

    public String schema() {
        return SCHEMA;
    }

    public String studyId() {
        return PolynomialTheoryUtilityPreregistration.STUDY_ID;
    }

    public String evidenceStatus() {
        return EVIDENCE_STATUS;
    }

    public String qualificationExposure() {
        return QUALIFICATION_EXPOSURE;
    }

    public int rowCount() {
        return measuredBatch.rowCount();
    }

    public byte[] bytes() {
        return utf8(canonicalJson);
    }

    public boolean verify(byte[] candidate) {
        return candidate != null
            && MessageDigest.isEqual(bytes(), candidate);
    }

    public void requireVerified(byte[] candidate) {
        if (!verify(candidate)) {
            throw new IllegalArgumentException(
                "candidate freeze bytes differ from the frozen artifact"
            );
        }
    }

    public void validateAgainst(
        PolynomialTheoryUtilityCandidateMeasurementBatch expected
    ) {
        if (!measuredBatch.equals(
                Objects.requireNonNull(expected, "expected"))) {
            throw new IllegalArgumentException(
                "candidate freeze refers to another measured batch"
            );
        }
    }

    public Path write(Path directory) throws IOException {
        Path root = Objects.requireNonNull(directory, "directory")
            .toAbsolutePath()
            .normalize();
        Files.createDirectories(root);
        Path qualification = root.resolve(
            PolynomialTheoryUtilityCaseCorpus.QUALIFICATION_FILE_NAME
        );
        if (Files.exists(qualification, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(
                "candidate freeze output contains sealed qualification"
            );
        }
        Path target = root.resolve(FILE_NAME);
        AtomicJsonFile.writeUtf8(target, canonicalJson);
        requireVerified(Files.readAllBytes(target));
        if (Files.exists(qualification, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(
                "sealed qualification appeared during candidate freeze"
            );
        }
        return target;
    }

    private static String canonical(
        PolynomialTheoryUtilityCandidateMeasurementBatch measuredBatch
    ) {
        var batch = Objects.requireNonNull(
            measuredBatch,
            "measuredBatch"
        );
        JsonWriter json = new JsonWriter().beginObject();
        json.property("schema", SCHEMA);
        json.property(
            "studyId",
            PolynomialTheoryUtilityPreregistration.STUDY_ID
        );
        json.property("evidenceStatus", EVIDENCE_STATUS);
        json.property(
            "qualificationExposure",
            QUALIFICATION_EXPOSURE
        );
        binding(
            json,
            "preregistrationBinding",
            PolynomialTheoryUtilityPreregistration.FILE_NAME,
            PolynomialTheoryUtilityPreregistration.BYTE_LENGTH,
            PolynomialTheoryUtilityPreregistration.CONTENT_HASH
        );
        binding(
            json,
            "formationBinding",
            PolynomialTheoryUtilityCaseCorpus.FORMATION_FILE_NAME,
            PolynomialTheoryUtilityCaseCorpus.FORMATION_BYTE_LENGTH,
            PolynomialTheoryUtilityCaseCorpus.FORMATION_CONTENT_HASH
        );
        binding(
            json,
            "qualificationBinding",
            PolynomialTheoryUtilityCaseCorpus.QUALIFICATION_FILE_NAME,
            PolynomialTheoryUtilityCaseCorpus.QUALIFICATION_BYTE_LENGTH,
            PolynomialTheoryUtilityCaseCorpus.QUALIFICATION_CONTENT_HASH
        );
        binding(
            json,
            "planBinding",
            PolynomialTheoryUtilityExecutionPlan.FILE_NAME,
            PolynomialTheoryUtilityExecutionPlan.EXPECTED_BYTE_LENGTH,
            PolynomialTheoryUtilityExecutionPlan.EXPECTED_CONTENT_HASH
        );
        binding(
            json,
            "executionInputBinding",
            PolynomialTheoryUtilityExecutionInputs.FILE_NAME,
            PolynomialTheoryUtilityExecutionInputs.EXPECTED_BYTE_LENGTH,
            PolynomialTheoryUtilityExecutionInputs.EXPECTED_CONTENT_HASH
        );
        appendMeasuredBatch(json, batch);
        json.array("rows", rows -> {
            for (int index = 0; index < batch.rowCount(); index++) {
                int rowIndex = index;
                rows.objectValue(row -> appendRow(
                    row,
                    rowIndex,
                    batch.results().get(rowIndex),
                    batch.measurements().get(rowIndex)
                ));
            }
        });
        return json.endObject().toString() + "\n";
    }

    private static void binding(
        JsonWriter json,
        String field,
        String path,
        long byteLength,
        String bindingHash
    ) {
        json.object(field, value -> {
            value.property("path", path);
            value.property("byteLength", byteLength);
            value.property("contentHash", bindingHash);
        });
    }

    private static void appendMeasuredBatch(
        JsonWriter json,
        PolynomialTheoryUtilityCandidateMeasurementBatch batch
    ) {
        json.object("measuredBatch", value -> {
            value.property("schema", batch.schema());
            value.property("batchId", batch.batchId());
            value.property("evidenceStatus", batch.evidenceStatus());
            value.property(
                "candidateBatchSchema",
                batch.candidateBatch().schema()
            );
            value.property(
                "candidateBatchEvidenceStatus",
                batch.candidateBatch().evidenceStatus()
            );
            value.property("inputContentHash", batch.inputContentHash());
            value.property("inputByteLength", batch.inputByteLength());
            value.property("rowCount", batch.rowCount());
        });
    }

    private static void appendRow(
        JsonWriter row,
        int index,
        PolynomialTheoryUtilityCandidateResult result,
        PolynomialTheoryUtilityCandidateMeasurements measurements
    ) {
        row.property("rowIndex", index);
        appendInput(row, result.input());
        appendResult(row, result);
        appendMeasurements(row, measurements);
    }

    private static void appendInput(
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

    private static void appendResult(
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
        json.property("executionInputId", transition.executionInputId());
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
        json.property("transformationId", transition.transformationId());
        json.property("backendId", transition.backendId());
        json.property("sourceEvidenceHash", transition.sourceEvidenceHash());
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
            value.property("factorizationWork", work.factorizationWork());
            value.property("verificationWork", work.verificationWork());
            value.property("renderingWork", work.renderingWork());
            value.property("reparseWork", work.reparseWork());
            value.property("reconstructionWork", work.reconstructionWork());
            value.property(
                "occurrenceReplacementWork",
                work.occurrenceReplacementWork()
            );
            value.property("cacheLookupWork", work.cacheLookupWork());
            value.property("cacheInsertionWork", work.cacheInsertionWork());
            value.property("cacheEvictionWork", work.cacheEvictionWork());
            value.property("cacheReplayWork", work.cacheReplayWork());
            value.property(
                "evidenceConstructionWork",
                work.evidenceConstructionWork()
            );
            value.property("mechanicalWork", work.mechanicalWork());
            value.property("totalWork", work.totalWork());
        });
    }

    private static void appendMeasurements(
        JsonWriter row,
        PolynomialTheoryUtilityCandidateMeasurements measurements
    ) {
        row.object("measurements", value -> {
            value.property("schema", measurements.schema());
            value.property("measurementId", measurements.measurementId());
            value.property("resultId", measurements.result().resultId());
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
                    traces.objectValue(item -> appendTrace(item, trace))
                )
            );
            value.array("factorizationAttempts", attempts ->
                measurements.factorizationAttempts().forEach(attempt ->
                    attempts.objectValue(item -> appendAttempt(item, attempt))
                )
            );
            value.array("cacheEvents", events ->
                measurements.cacheEvents().forEach(event ->
                    events.objectValue(item -> appendCacheEvent(item, event))
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
            value.property("totalPathDepth", measurements.totalPathDepth());
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
            intArray(value, "astNodeGrowths", measurements.astNodeGrowths());
            value.property(
                "factorizationRequestCount",
                measurements.factorizationRequestCount()
            );
            value.property(
                "factorizationCandidateCount",
                measurements.factorizationCandidateCount()
            );
            value.property("cacheHitCount", measurements.cacheHitCount());
            value.property("cacheMissCount", measurements.cacheMissCount());
            value.property(
                "cacheInsertionCount",
                measurements.cacheInsertionCount()
            );
            value.property(
                "cacheEvictionCount",
                measurements.cacheEvictionCount()
            );
            value.property("cacheReplayCount", measurements.cacheReplayCount());
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
        json.property("transitionId", trace.transition().transitionId());
        json.property("pathDepth", trace.pathDepth());
        json.property(
            "primitiveExpansionLength",
            trace.primitiveExpansionLength()
        );
        json.stringArray(
            "normalizedAssumptions",
            trace.normalizedAssumptions()
        );
        json.property("sourceAstNodeCount", trace.sourceAstNodeCount());
        json.property(
            "transformedAstNodeCount",
            trace.transformedAstNodeCount()
        );
        json.property("astNodeGrowth", trace.astNodeGrowth());
        json.array("primitiveSteps", steps ->
            trace.primitiveSteps().forEach(step ->
                steps.objectValue(item -> appendPrimitiveStep(item, step))
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
        json.property("executionInputId", attempt.executionInputId());
        json.property("backendId", attempt.backendId());
        json.property("requestId", attempt.requestId());
        json.property("requestEvidenceHash", attempt.requestEvidenceHash());
        json.stringArray("candidateIds", attempt.candidateIds());
        json.property("selectedCandidateId", attempt.selectedCandidateId());
        json.property("transitionId", attempt.transitionId());
        json.property("verifierOutcome", attempt.verifierOutcome());
        json.property("reportEvidenceHash", attempt.reportEvidenceHash());
    }

    private static void appendCacheEvent(
        JsonWriter json,
        PolynomialTheoryUtilityCacheEvent event
    ) {
        json.property("schema", event.schema());
        json.property("eventId", event.eventId());
        json.property("eventIndex", event.eventIndex());
        json.property("executionInputId", event.executionInputId());
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

    private static byte[] utf8(String value) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException(
                "candidate freeze contains invalid Unicode",
                exception
            );
        }
    }

    private static String requireHash(String value, String name) {
        String text = requireText(value, name);
        if (!SHA_256.matcher(text).matches()) {
            throw new IllegalArgumentException(name + " is not SHA-256");
        }
        return text;
    }

    private static String requireText(String value, String name) {
        String text = Objects.requireNonNull(value, name);
        if (text.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return text;
    }
}
