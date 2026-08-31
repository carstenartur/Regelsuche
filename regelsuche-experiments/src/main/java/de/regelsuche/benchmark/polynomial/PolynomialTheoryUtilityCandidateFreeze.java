package de.regelsuche.benchmark.polynomial;

import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityProfileAdapter.CandidateBatch;
import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityProfileAdapter.CandidateResult;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Canonical target-blind freeze of the complete polynomial utility result batch.
 *
 * <p>The freeze consumes only the already validated in-memory batch and the
 * content-addressed target-blind execution inputs. It binds, but never opens,
 * the separately sealed qualification resource.</p>
 */
public final class PolynomialTheoryUtilityCandidateFreeze {
    public static final String SCHEMA =
        "regelsuche.polynomial-theory-utility-candidate-freeze/v1";
    public static final String FILE_NAME =
        "polynomial-theory-utility-candidate-freeze-v1.json";
    public static final String EVIDENCE_STATUS =
        "TARGET_BLIND_CANDIDATE_FREEZE";
    public static final String QUALIFICATION_EXPOSURE =
        "HASH_ONLY_NOT_OPENED";

    private static final Pattern SHA_256 =
        Pattern.compile("sha256:[0-9a-f]{64}");
    private static final List<String> FORBIDDEN_FIELDS = List.of(
        "requiredOutcome",
        "reducibilityStatus",
        "multiplicityStatus",
        "referenceExpression",
        "expectedClassifierOutcome",
        "selectedDecision",
        "qualificationResult",
        "expectedOutcome"
    );

    private PolynomialTheoryUtilityCandidateFreeze() {
    }

    /**
     * Serializes one complete validated batch without opening qualification.
     *
     * @param batch exact 600-result target-blind in-memory batch
     * @return immutable content-addressed Candidate-Freeze artifact
     */
    public static Artifact create(CandidateBatch batch) {
        Objects.requireNonNull(batch, "batch");
        PolynomialTheoryUtilityExecutionInputArtifact inputs =
            PolynomialTheoryUtilityExecutionInputs.freeze();
        requireBatchBinding(inputs, batch);
        List<Row> rows = batch.results().stream()
            .map(Row::from)
            .toList();
        requireRows(inputs, rows);

        String canonical = canonical(
            inputs.contentHash(),
            inputs.byteLength(),
            rows
        );
        requireTargetBlind(canonical);
        byte[] bytes = canonical.getBytes(StandardCharsets.UTF_8);
        return new Artifact(
            inputs.contentHash(),
            inputs.byteLength(),
            rows,
            canonical,
            PolynomialTheoryUtilityExecutionIdentity.sha256(bytes),
            bytes.length
        );
    }

    private static void requireBatchBinding(
        PolynomialTheoryUtilityExecutionInputArtifact inputs,
        CandidateBatch batch
    ) {
        if (!CandidateBatch.SCHEMA.equals(batch.schema())
                || !PolynomialTheoryUtilityPreregistration.STUDY_ID.equals(
                    batch.studyId()
                )
                || !CandidateBatch.EVIDENCE_STATUS.equals(
                    batch.evidenceStatus()
                )
                || !inputs.contentHash().equals(batch.inputContentHash())
                || inputs.byteLength() != batch.inputByteLength()
                || batch.results().size() != inputs.inputs().size()
                || batch.results().size()
                    != PolynomialTheoryUtilityExecutionInputs
                        .EXPECTED_INPUT_COUNT) {
            throw new IllegalArgumentException(
                "candidate batch differs from the frozen execution boundary"
            );
        }

        Set<String> identities = new HashSet<>();
        for (int index = 0; index < batch.results().size(); index++) {
            CandidateResult result = Objects.requireNonNull(
                batch.results().get(index),
                "candidate result"
            );
            result.validateAgainst(inputs.inputs().get(index));
            if (!identities.add(result.resultId())) {
                throw new IllegalArgumentException(
                    "candidate batch repeats a result identity"
                );
            }
        }
    }

    private static void requireRows(
        PolynomialTheoryUtilityExecutionInputArtifact inputs,
        List<Row> rows
    ) {
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(rows, "rows");
        if (rows.size() != inputs.inputs().size()
                || rows.size()
                    != PolynomialTheoryUtilityExecutionInputs
                        .EXPECTED_INPUT_COUNT) {
            throw new IllegalArgumentException(
                "candidate freeze must contain one row per frozen input"
            );
        }

        Set<String> resultIds = new HashSet<>();
        Set<String> runIds = new HashSet<>();
        Set<String> profileIds = new HashSet<>();
        for (int index = 0; index < rows.size(); index++) {
            PolynomialTheoryUtilityExecutionInput input =
                inputs.inputs().get(index);
            Row row = Objects.requireNonNull(rows.get(index), "row");
            row.requireAgainst(input);
            if (!resultIds.add(row.candidateResultId())) {
                throw new IllegalArgumentException(
                    "candidate freeze repeats a result identity"
                );
            }
            runIds.add(row.runId());
            profileIds.add(row.profileId());
        }

        int expectedRuns =
            PolynomialTheoryUtilityExecutionPlan.PROFILES.size()
                * PolynomialTheoryUtilityExecutionPlan.CHECKPOINTS.size();
        Set<String> expectedProfiles = new HashSet<>(
            PolynomialTheoryUtilityExecutionPlan.PROFILES.stream()
                .map(PolynomialTheoryUtilityExecutionProfile::profileId)
                .toList()
        );
        if (runIds.size() != expectedRuns
                || !profileIds.equals(expectedProfiles)) {
            throw new IllegalArgumentException(
                "candidate freeze does not cover the frozen profile/run matrix"
            );
        }
    }

    private static String canonical(
        String inputContentHash,
        long inputByteLength,
        List<Row> rows
    ) {
        StringBuilder target = new StringBuilder(rows.size() * 640);
        target.append("{\n  ");
        appendStringField(target, "schema", SCHEMA);
        target.append(",\n  ");
        appendStringField(target, "fileName", FILE_NAME);
        target.append(",\n  ");
        appendStringField(
            target,
            "studyId",
            PolynomialTheoryUtilityPreregistration.STUDY_ID
        );
        target.append(",\n  ");
        appendStringField(target, "evidenceStatus", EVIDENCE_STATUS);
        target.append(",\n  ");
        appendStringField(
            target,
            "qualificationExposure",
            QUALIFICATION_EXPOSURE
        );
        target.append(",\n  \"inputBinding\":{");
        appendStringField(
            target,
            "schema",
            PolynomialTheoryUtilityExecutionInputs.SCHEMA
        );
        target.append(',');
        appendStringField(
            target,
            "evidenceStatus",
            PolynomialTheoryUtilityExecutionInputs.EVIDENCE_STATUS
        );
        target.append(",\"byteLength\":")
            .append(inputByteLength)
            .append(',');
        appendStringField(target, "contentHash", inputContentHash);
        target.append("},\n  \"planBinding\":{");
        appendStringField(
            target,
            "schema",
            PolynomialTheoryUtilityExecutionPlan.SCHEMA
        );
        target.append(",\"byteLength\":")
            .append(PolynomialTheoryUtilityExecutionPlan.EXPECTED_BYTE_LENGTH)
            .append(',');
        appendStringField(
            target,
            "contentHash",
            PolynomialTheoryUtilityExecutionPlan.EXPECTED_CONTENT_HASH
        );
        target.append("},\n  \"formationBinding\":{");
        appendStringField(
            target,
            "path",
            PolynomialTheoryUtilityCaseCorpus.FORMATION_FILE_NAME
        );
        target.append(",\"byteLength\":")
            .append(PolynomialTheoryUtilityCaseCorpus.FORMATION_BYTE_LENGTH)
            .append(',');
        appendStringField(
            target,
            "contentHash",
            PolynomialTheoryUtilityCaseCorpus.FORMATION_CONTENT_HASH
        );
        target.append("},\n  \"qualificationBinding\":{");
        appendStringField(
            target,
            "path",
            PolynomialTheoryUtilityCaseCorpus.QUALIFICATION_FILE_NAME
        );
        target.append(",\"byteLength\":")
            .append(PolynomialTheoryUtilityCaseCorpus.QUALIFICATION_BYTE_LENGTH)
            .append(',');
        appendStringField(
            target,
            "contentHash",
            PolynomialTheoryUtilityCaseCorpus.QUALIFICATION_CONTENT_HASH
        );
        target.append("},\n  \"sourceBatchBinding\":{");
        appendStringField(target, "schema", CandidateBatch.SCHEMA);
        target.append(',');
        appendStringField(
            target,
            "evidenceStatus",
            CandidateBatch.EVIDENCE_STATUS
        );
        target.append("},\n  \"rowCount\":")
            .append(rows.size())
            .append(",\n  \"rows\":[\n");
        for (int index = 0; index < rows.size(); index++) {
            appendRow(target, rows.get(index));
            target.append(index + 1 < rows.size() ? ",\n" : "\n");
        }
        return target.append("  ]\n}\n").toString();
    }

    private static void appendRow(StringBuilder target, Row row) {
        target.append("    {");
        appendStringField(
            target,
            "candidateResultId",
            row.candidateResultId()
        );
        target.append(',');
        appendStringField(target, "inputId", row.inputId());
        target.append(',');
        appendStringField(target, "executionRowId", row.executionRowId());
        target.append(',');
        appendStringField(target, "runId", row.runId());
        target.append(',');
        appendStringField(target, "caseId", row.caseId());
        target.append(',');
        appendStringField(target, "profileId", row.profileId());
        target.append(',');
        appendStringField(target, "checkpointId", row.checkpointId());
        target.append(',');
        appendStringField(target, "adapterId", row.adapterId());
        target.append(',');
        appendStringField(
            target,
            "terminalStatus",
            row.terminalStatus().name()
        );
        target.append(',');
        appendStringField(target, "detailCode", row.detailCode());
        target.append(",\"primitiveWorkConsumed\":")
            .append(row.primitiveWorkConsumed())
            .append(",\"mechanicalWorkConsumed\":")
            .append(row.mechanicalWorkConsumed())
            .append(",\"factorizationWorkConsumed\":")
            .append(row.factorizationWorkConsumed())
            .append(",\"generatedTransitions\":")
            .append(row.generatedTransitions())
            .append(',');
        appendStringField(
            target,
            "verifierOutcome",
            row.verifierOutcome()
        );
        target.append(',');
        appendStringField(
            target,
            "transitionEvidenceHash",
            row.transitionEvidenceHash()
        );
        target.append('}');
    }

    private static void appendStringField(
        StringBuilder target,
        String name,
        String value
    ) {
        appendJsonString(target, name);
        target.append(':');
        appendJsonString(target, value);
    }

    private static void appendJsonString(
        StringBuilder target,
        String value
    ) {
        target.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> target.append("\\\"");
                case '\\' -> target.append("\\\\");
                case '\b' -> target.append("\\b");
                case '\f' -> target.append("\\f");
                case '\n' -> target.append("\\n");
                case '\r' -> target.append("\\r");
                case '\t' -> target.append("\\t");
                default -> {
                    if (character < 0x20) {
                        target.append("\\u");
                        target.append(Character.forDigit(
                            character >>> 12 & 0xf,
                            16
                        ));
                        target.append(Character.forDigit(
                            character >>> 8 & 0xf,
                            16
                        ));
                        target.append(Character.forDigit(
                            character >>> 4 & 0xf,
                            16
                        ));
                        target.append(Character.forDigit(character & 0xf, 16));
                    } else {
                        target.append(character);
                    }
                }
            }
        }
        target.append('"');
    }

    private static void requireTargetBlind(String canonical) {
        FORBIDDEN_FIELDS.forEach(field -> {
            if (canonical.contains("\"" + field + "\":")) {
                throw new IllegalArgumentException(
                    "candidate freeze leaks forbidden field: " + field
                );
            }
        });
    }

    /** One canonical row derived from an existing CandidateResult identity. */
    public record Row(
        String candidateResultId,
        String inputId,
        String executionRowId,
        String runId,
        String caseId,
        String profileId,
        String checkpointId,
        String adapterId,
        CandidateResult.TerminalStatus terminalStatus,
        String detailCode,
        long primitiveWorkConsumed,
        long mechanicalWorkConsumed,
        long factorizationWorkConsumed,
        int generatedTransitions,
        String verifierOutcome,
        String transitionEvidenceHash
    ) {
        public Row {
            requireHash(candidateResultId, "candidateResultId");
            requireHash(inputId, "inputId");
            requireHash(executionRowId, "executionRowId");
            requireHash(runId, "runId");
            caseId = requireText(caseId, "caseId");
            profileId = requireText(profileId, "profileId");
            checkpointId = requireText(checkpointId, "checkpointId");
            adapterId = requireText(adapterId, "adapterId");
            terminalStatus = Objects.requireNonNull(
                terminalStatus,
                "terminalStatus"
            );
            detailCode = requireText(detailCode, "detailCode");
            verifierOutcome = requireText(verifierOutcome, "verifierOutcome");
            transitionEvidenceHash = requireText(
                transitionEvidenceHash,
                "transitionEvidenceHash"
            );
            if (primitiveWorkConsumed < 0
                    || mechanicalWorkConsumed < 0
                    || factorizationWorkConsumed < 0
                    || generatedTransitions < 0
                    || factorizationWorkConsumed > mechanicalWorkConsumed) {
                throw new IllegalArgumentException(
                    "candidate freeze row contains invalid work values"
                );
            }
            boolean validated = terminalStatus
                == CandidateResult.TerminalStatus.VALIDATED_TRANSITION;
            if (validated) {
                if (generatedTransitions < 1
                        || !"VERIFIED".equals(verifierOutcome)
                        || !SHA_256.matcher(
                            transitionEvidenceHash
                        ).matches()) {
                    throw new IllegalArgumentException(
                        "validated candidate freeze row lacks evidence"
                    );
                }
            } else if (generatedTransitions != 0
                    || !CandidateResult.NO_TRANSITION_EVIDENCE.equals(
                        transitionEvidenceHash
                    )) {
                throw new IllegalArgumentException(
                    "non-transition candidate freeze row retains evidence"
                );
            }
        }

        private static Row from(CandidateResult result) {
            PolynomialTheoryUtilityExecutionInput input = result.input();
            return new Row(
                result.resultId(),
                input.inputId(),
                input.rowId(),
                input.runId(),
                input.caseId(),
                input.profileId(),
                input.checkpointId(),
                input.adapterId(),
                result.terminalStatus(),
                result.detailCode(),
                result.primitiveWorkConsumed(),
                result.mechanicalWorkConsumed(),
                result.factorizationWorkConsumed(),
                result.generatedTransitions(),
                result.verifierOutcome(),
                result.transitionEvidenceHash()
            );
        }

        private void requireAgainst(
            PolynomialTheoryUtilityExecutionInput input
        ) {
            if (!input.inputId().equals(inputId)
                    || !input.rowId().equals(executionRowId)
                    || !input.runId().equals(runId)
                    || !input.caseId().equals(caseId)
                    || !input.profileId().equals(profileId)
                    || !input.checkpointId().equals(checkpointId)
                    || !input.adapterId().equals(adapterId)) {
                throw new IllegalArgumentException(
                    "candidate freeze row differs from its frozen input"
                );
            }
            CandidateResult reconstructed = CandidateResult.create(
                input,
                terminalStatus,
                detailCode,
                primitiveWorkConsumed,
                mechanicalWorkConsumed,
                factorizationWorkConsumed,
                generatedTransitions,
                verifierOutcome,
                transitionEvidenceHash
            );
            if (!candidateResultId.equals(reconstructed.resultId())) {
                throw new IllegalArgumentException(
                    "candidate freeze row reuses an invalid result identity"
                );
            }
        }
    }

    /** Immutable content-addressed Candidate-Freeze artifact. */
    public record Artifact(
        String inputContentHash,
        long inputByteLength,
        List<Row> rows,
        String canonicalJson,
        String contentHash,
        long byteLength
    ) {
        public Artifact {
            requireHash(inputContentHash, "inputContentHash");
            requireHash(contentHash, "contentHash");
            if (inputByteLength < 1 || byteLength < 1) {
                throw new IllegalArgumentException(
                    "candidate freeze byte lengths must be positive"
                );
            }
            rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
            canonicalJson = Objects.requireNonNull(
                canonicalJson,
                "canonicalJson"
            );

            PolynomialTheoryUtilityExecutionInputArtifact inputs =
                PolynomialTheoryUtilityExecutionInputs.freeze();
            if (!inputContentHash.equals(inputs.contentHash())
                    || inputByteLength != inputs.byteLength()) {
                throw new IllegalArgumentException(
                    "candidate freeze input binding is not canonical"
                );
            }
            requireRows(inputs, rows);
            String expectedCanonical = canonical(
                inputContentHash,
                inputByteLength,
                rows
            );
            if (!expectedCanonical.equals(canonicalJson)) {
                throw new IllegalArgumentException(
                    "candidate freeze rows differ from canonical bytes"
                );
            }
            requireTargetBlind(canonicalJson);
            byte[] bytes = canonicalJson.getBytes(StandardCharsets.UTF_8);
            if (bytes.length != byteLength
                    || !contentHash.equals(
                        PolynomialTheoryUtilityExecutionIdentity.sha256(bytes)
                    )) {
                throw new IllegalArgumentException(
                    "candidate freeze content identity differs"
                );
            }
        }

        public String schema() {
            return SCHEMA;
        }

        public String fileName() {
            return FILE_NAME;
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

        public byte[] canonicalBytes() {
            return canonicalJson.getBytes(StandardCharsets.UTF_8);
        }
    }

    private static String requireText(String value, String name) {
        String text = Objects.requireNonNull(value, name);
        if (text.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return text;
    }

    private static void requireHash(String value, String name) {
        if (value == null || !SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " is not SHA-256");
        }
    }
}
