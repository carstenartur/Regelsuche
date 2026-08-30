package de.regelsuche.benchmark.polynomial;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Target-blind, content-addressed output of the execution runner. */
public final class PolynomialTheoryUtilityCandidateFreeze {
    public static final String SCHEMA =
        "regelsuche.polynomial-theory-utility-candidate-freeze/v1";
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
        "selectedDecision"
    );

    private PolynomialTheoryUtilityCandidateFreeze() {
    }

    public static Artifact create(
        PolynomialTheoryUtilityExecutionInputArtifact inputs,
        List<Row> rows
    ) {
        Objects.requireNonNull(inputs, "inputs");
        List<Row> copy = List.copyOf(rows);
        if (copy.size() != inputs.inputs().size()) {
            throw new IllegalArgumentException(
                "candidate freeze must contain one row per execution input"
            );
        }
        Set<String> candidateIds = new HashSet<>();
        for (int index = 0; index < copy.size(); index++) {
            var input = inputs.inputs().get(index);
            var row = copy.get(index);
            if (!input.inputId().equals(row.inputId())
                    || !input.rowId().equals(row.executionRowId())
                    || !input.runId().equals(row.runId())
                    || !input.caseId().equals(row.caseId())
                    || !input.profileId().equals(row.profileId())
                    || !input.checkpointId().equals(row.checkpointId())
                    || !input.adapterId().equals(row.adapterId())
                    || !candidateIds.add(row.candidateId())) {
                throw new IllegalArgumentException(
                    "candidate freeze order or identity differs at " + index
                );
            }
            row.outcome().requireWithin(input);
        }
        String canonical = canonical(
            inputs.byteLength(),
            inputs.contentHash(),
            copy
        );
        requireTargetBlind(canonical);
        byte[] bytes = canonical.getBytes(StandardCharsets.UTF_8);
        return new Artifact(
            inputs.contentHash(),
            inputs.byteLength(),
            copy,
            canonical,
            PolynomialTheoryUtilityExecutionIdentity.sha256(bytes),
            bytes.length
        );
    }

    public static Row row(
        PolynomialTheoryUtilityExecutionInput input,
        PolynomialTheoryUtilityProfileAdapter.Outcome outcome
    ) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(outcome, "outcome");
        outcome.requireWithin(input);
        String id = candidateId(
            input.inputId(),
            input.rowId(),
            input.runId(),
            input.caseId(),
            input.profileId(),
            input.checkpointId(),
            input.adapterId(),
            outcome
        );
        return new Row(
            id,
            input.inputId(),
            input.rowId(),
            input.runId(),
            input.caseId(),
            input.profileId(),
            input.checkpointId(),
            input.adapterId(),
            outcome
        );
    }

    private static String canonical(
        long inputByteLength,
        String inputContentHash,
        List<Row> rows
    ) {
        StringBuilder target = new StringBuilder(rows.size() * 760);
        target.append("{\n  ");
        appendStringField(target, "schema", SCHEMA);
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
        target.append(",\n  \"inputBinding\": {\"byteLength\":")
            .append(inputByteLength)
            .append(",\"contentHash\":");
        appendJsonString(target, inputContentHash);
        target.append("},\n  \"planBinding\": {\"byteLength\":")
            .append(PolynomialTheoryUtilityExecutionPlan.EXPECTED_BYTE_LENGTH)
            .append(",\"contentHash\":");
        appendJsonString(
            target,
            PolynomialTheoryUtilityExecutionPlan.EXPECTED_CONTENT_HASH
        );
        target.append("},\n  \"rowCount\": ").append(rows.size())
            .append(",\n  \"rows\": [\n");
        for (int index = 0; index < rows.size(); index++) {
            appendRow(target, rows.get(index));
            target.append(index + 1 < rows.size() ? ",\n" : "\n");
        }
        return target.append("  ]\n}\n").toString();
    }

    private static void appendRow(StringBuilder target, Row row) {
        var value = row.outcome();
        target.append("    {");
        appendStringField(target, "candidateId", row.candidateId());
        target.append(',');
        appendStringField(target, "inputId", row.inputId());
        target.append(',');
        appendStringField(
            target,
            "executionRowId",
            row.executionRowId()
        );
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
            value.terminalStatus().name()
        );
        target.append(',');
        appendStringField(target, "detailCode", value.detailCode());
        target.append(',');
        appendStringField(
            target,
            "generatedExpression",
            value.generatedExpression()
        );
        target.append(',');
        appendStringField(
            target,
            "transformationId",
            value.transformationId()
        );
        target.append(',');
        appendStringField(
            target,
            "verifierStatus",
            value.verifierStatus()
        );
        target.append(",\"primitiveWork\":")
            .append(value.primitiveWork())
            .append(",\"sourceValidationWork\":")
            .append(value.sourceValidationWork())
            .append(",\"factorizationWork\":")
            .append(value.factorizationWork())
            .append(",\"renderReparseWork\":")
            .append(value.renderReparseWork())
            .append(",\"cacheLookupWork\":")
            .append(value.cacheLookupWork())
            .append(",\"cacheReplayWork\":")
            .append(value.cacheReplayWork())
            .append(",\"otherMechanicalWork\":")
            .append(value.otherMechanicalWork())
            .append(",\"totalMechanicalWork\":")
            .append(value.totalMechanicalWork())
            .append(",\"factorizationRequests\":")
            .append(value.factorizationRequests())
            .append(",\"factorizationCandidates\":")
            .append(value.factorizationCandidates())
            .append(",\"generatedTransitions\":")
            .append(value.generatedTransitions())
            .append(",\"pathDepth\":").append(value.pathDepth())
            .append(",\"primitiveExpansionLength\":")
            .append(value.primitiveExpansionLength())
            .append(",\"sourceAstNodes\":")
            .append(value.sourceAstNodes())
            .append(",\"transformedAstNodes\":")
            .append(value.transformedAstNodes())
            .append(",\"cacheHits\":").append(value.cacheHits())
            .append(",\"cacheMisses\":").append(value.cacheMisses())
            .append(",\"cacheInsertions\":")
            .append(value.cacheInsertions())
            .append(",\"cacheEvictions\":").append(value.cacheEvictions())
            .append(",\"primitiveRuleIds\":");
        appendStringArray(target, value.primitiveRuleIds());
        target.append(",\"lineageIds\":");
        appendStringArray(target, value.lineageIds());
        target.append('}');
    }

    private static String candidateId(
        String inputId,
        String executionRowId,
        String runId,
        String caseId,
        String profileId,
        String checkpointId,
        String adapterId,
        PolynomialTheoryUtilityProfileAdapter.Outcome value
    ) {
        String material = canonicalOutcome(
            inputId,
            executionRowId,
            runId,
            caseId,
            profileId,
            checkpointId,
            adapterId,
            value
        );
        return PolynomialTheoryUtilityExecutionIdentity.sha256(
            material.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String canonicalOutcome(
        String inputId,
        String executionRowId,
        String runId,
        String caseId,
        String profileId,
        String checkpointId,
        String adapterId,
        PolynomialTheoryUtilityProfileAdapter.Outcome value
    ) {
        StringBuilder target = new StringBuilder();
        for (String item : List.of(
                SCHEMA,
                inputId,
                executionRowId,
                runId,
                caseId,
                profileId,
                checkpointId,
                adapterId,
                value.terminalStatus().name(),
                value.detailCode(),
                value.generatedExpression(),
                value.transformationId(),
                value.verifierStatus(),
                Long.toString(value.primitiveWork()),
                Long.toString(value.sourceValidationWork()),
                Long.toString(value.factorizationWork()),
                Long.toString(value.renderReparseWork()),
                Long.toString(value.cacheLookupWork()),
                Long.toString(value.cacheReplayWork()),
                Long.toString(value.otherMechanicalWork()),
                Integer.toString(value.factorizationRequests()),
                Integer.toString(value.factorizationCandidates()),
                Integer.toString(value.generatedTransitions()),
                Integer.toString(value.pathDepth()),
                Integer.toString(value.primitiveExpansionLength()),
                Integer.toString(value.sourceAstNodes()),
                Integer.toString(value.transformedAstNodes()),
                Integer.toString(value.cacheHits()),
                Integer.toString(value.cacheMisses()),
                Integer.toString(value.cacheInsertions()),
                Integer.toString(value.cacheEvictions()))) {
            appendMaterial(target, item);
        }
        appendMaterials(target, value.primitiveRuleIds());
        appendMaterials(target, value.lineageIds());
        return target.toString();
    }

    private static void appendMaterials(
        StringBuilder target,
        List<String> values
    ) {
        appendMaterial(target, Integer.toString(values.size()));
        values.forEach(value -> appendMaterial(target, value));
    }

    private static void appendMaterial(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
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

    private static void appendStringArray(
        StringBuilder target,
        List<String> values
    ) {
        target.append('[');
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                target.append(',');
            }
            appendJsonString(target, values.get(index));
        }
        target.append(']');
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

    public record Row(
        String candidateId,
        String inputId,
        String executionRowId,
        String runId,
        String caseId,
        String profileId,
        String checkpointId,
        String adapterId,
        PolynomialTheoryUtilityProfileAdapter.Outcome outcome
    ) {
        public Row {
            for (String hash : List.of(candidateId, inputId, executionRowId,
                    runId)) {
                if (hash == null || !SHA_256.matcher(hash).matches()) {
                    throw new IllegalArgumentException(
                        "candidate freeze contains an invalid SHA-256 identity"
                    );
                }
            }
            caseId = requireText(caseId, "caseId");
            profileId = requireText(profileId, "profileId");
            checkpointId = requireText(checkpointId, "checkpointId");
            adapterId = requireText(adapterId, "adapterId");
            outcome = Objects.requireNonNull(outcome, "outcome");
            String expectedId =
                PolynomialTheoryUtilityCandidateFreeze.candidateId(
                    inputId,
                    executionRowId,
                    runId,
                    caseId,
                    profileId,
                    checkpointId,
                    adapterId,
                    outcome
                );
            if (!candidateId.equals(expectedId)) {
                throw new IllegalArgumentException(
                    "candidate identity differs from its terminal outcome"
                );
            }
        }
    }

    public record Artifact(
        String inputContentHash,
        long inputByteLength,
        List<Row> rows,
        String canonicalJson,
        String contentHash,
        long byteLength
    ) {
        public Artifact {
            if (inputContentHash == null
                    || !SHA_256.matcher(inputContentHash).matches()
                    || inputByteLength < 1
                    || contentHash == null
                    || !SHA_256.matcher(contentHash).matches()
                    || byteLength < 1) {
                throw new IllegalArgumentException(
                    "candidate freeze artifact identity is invalid"
                );
            }
            rows = List.copyOf(rows);
            canonicalJson = Objects.requireNonNull(
                canonicalJson,
                "canonicalJson"
            );
            String expectedCanonical = canonical(
                inputByteLength,
                inputContentHash,
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
                        PolynomialTheoryUtilityExecutionIdentity.sha256(bytes))) {
                throw new IllegalArgumentException(
                    "candidate freeze bytes differ from their identity"
                );
            }
        }

        public String schema() {
            return SCHEMA;
        }

        public String evidenceStatus() {
            return EVIDENCE_STATUS;
        }
    }

    private static String requireText(String value, String name) {
        String text = Objects.requireNonNull(value, name);
        if (text.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return text;
    }
}
