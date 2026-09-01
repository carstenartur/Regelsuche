package de.regelsuche.benchmark.polynomial;

import de.regelsuche.json.JsonWriter;
import java.util.Objects;

/** Canonical compact JSON for a complete measured Candidate-Freeze. */
final class PolynomialTheoryUtilityCandidateFreezeJson {
    private PolynomialTheoryUtilityCandidateFreezeJson() {
    }

    static String canonical(
        PolynomialTheoryUtilityCandidateMeasurementBatch measuredBatch
    ) {
        var batch = Objects.requireNonNull(
            measuredBatch,
            "measuredBatch"
        );
        JsonWriter json = new JsonWriter().beginObject();
        json.property(
            "schema",
            PolynomialTheoryUtilityCandidateFreeze.SCHEMA
        );
        json.property(
            "studyId",
            PolynomialTheoryUtilityPreregistration.STUDY_ID
        );
        json.property(
            "evidenceStatus",
            PolynomialTheoryUtilityCandidateFreeze.EVIDENCE_STATUS
        );
        json.property(
            "qualificationExposure",
            PolynomialTheoryUtilityCandidateFreeze.QUALIFICATION_EXPOSURE
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
        String contentHash
    ) {
        json.object(field, value -> {
            value.property("path", path);
            value.property("byteLength", byteLength);
            value.property("contentHash", contentHash);
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
        PolynomialTheoryUtilityCandidateFreezeResultJson.appendInput(
            row,
            result.input()
        );
        PolynomialTheoryUtilityCandidateFreezeResultJson.appendResult(
            row,
            result
        );
        PolynomialTheoryUtilityCandidateFreezeMeasurementJson.append(
            row,
            measurements
        );
    }
}
