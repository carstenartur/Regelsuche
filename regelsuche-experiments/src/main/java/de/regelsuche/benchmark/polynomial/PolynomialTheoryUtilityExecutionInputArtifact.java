package de.regelsuche.benchmark.polynomial;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Validated target-blind input artifact for the frozen execution matrix. */
public record PolynomialTheoryUtilityExecutionInputArtifact(
    List<PolynomialTheoryUtilityExecutionInput> inputs,
    String canonicalJson
) {
    public PolynomialTheoryUtilityExecutionInputArtifact {
        inputs = List.copyOf(inputs);
        canonicalJson = Objects.requireNonNull(
            canonicalJson,
            "canonicalJson"
        );
        if (inputs.size()
                != PolynomialTheoryUtilityExecutionInputs
                    .EXPECTED_INPUT_COUNT) {
            throw new IllegalArgumentException(
                "execution input artifact count differs");
        }
        if (new HashSet<>(inputs.stream()
                .map(PolynomialTheoryUtilityExecutionInput::inputId)
                .toList()).size() != inputs.size()) {
            throw new IllegalArgumentException(
                "execution input identities are not unique");
        }
        String expected =
            PolynomialTheoryUtilityExecutionInputJson.canonical(inputs);
        if (!expected.equals(canonicalJson)) {
            throw new IllegalArgumentException(
                "execution input rows differ from canonical bytes");
        }
        byte[] bytes = canonicalJson.getBytes(StandardCharsets.UTF_8);
        if (bytes.length
                != PolynomialTheoryUtilityExecutionInputs
                    .EXPECTED_BYTE_LENGTH
                || !PolynomialTheoryUtilityExecutionInputs
                    .EXPECTED_CONTENT_HASH.equals(
                        PolynomialTheoryUtilityExecutionInputIdentity.sha256(
                            bytes))) {
            throw new IllegalArgumentException(
                "execution input artifact identity differs");
        }
    }

    public String schema() {
        return PolynomialTheoryUtilityExecutionInputs.SCHEMA;
    }

    public String studyId() {
        return PolynomialTheoryUtilityPreregistration.STUDY_ID;
    }

    public String evidenceStatus() {
        return PolynomialTheoryUtilityExecutionInputs.EVIDENCE_STATUS;
    }

    public String contentHash() {
        return PolynomialTheoryUtilityExecutionInputs.EXPECTED_CONTENT_HASH;
    }

    public long byteLength() {
        return PolynomialTheoryUtilityExecutionInputs.EXPECTED_BYTE_LENGTH;
    }
}
