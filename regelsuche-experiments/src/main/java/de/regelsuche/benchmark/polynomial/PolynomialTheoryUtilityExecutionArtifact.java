package de.regelsuche.benchmark.polynomial;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** Exact content-addressed execution plan artifact. */
public record PolynomialTheoryUtilityExecutionArtifact(
    List<PolynomialTheoryUtilityExecutionRow> rows,
    String canonicalJson
) {
    public PolynomialTheoryUtilityExecutionArtifact {
        rows = List.copyOf(rows);
        canonicalJson = Objects.requireNonNull(canonicalJson, "canonicalJson");
        String expectedCanonical =
            PolynomialTheoryUtilityExecutionJson.canonical(rows);
        byte[] bytes = canonicalJson.getBytes(StandardCharsets.UTF_8);
        if (!expectedCanonical.equals(canonicalJson)
                || rows.size()
                    != PolynomialTheoryUtilityExecutionPlan.EXPECTED_ROW_COUNT
                || bytes.length
                    != PolynomialTheoryUtilityExecutionPlan.EXPECTED_BYTE_LENGTH
                || !PolynomialTheoryUtilityExecutionPlan
                    .EXPECTED_CONTENT_HASH.equals(
                        PolynomialTheoryUtilityExecutionIdentity.sha256(bytes))) {
            throw new IllegalArgumentException("invalid execution artifact");
        }
    }

    public String schema() {
        return PolynomialTheoryUtilityExecutionPlan.SCHEMA;
    }

    public String studyId() {
        return PolynomialTheoryUtilityPreregistration.STUDY_ID;
    }

    public String evidenceStatus() {
        return PolynomialTheoryUtilityExecutionPlan.EVIDENCE_STATUS;
    }

    public String planSelectionTiming() {
        return PolynomialTheoryUtilityExecutionPlan.PLAN_SELECTION_TIMING;
    }

    public String qualificationExposure() {
        return PolynomialTheoryUtilityExecutionPlan.QUALIFICATION_EXPOSURE;
    }

    public String contentHash() {
        return PolynomialTheoryUtilityExecutionPlan.EXPECTED_CONTENT_HASH;
    }

    public long byteLength() {
        return PolynomialTheoryUtilityExecutionPlan.EXPECTED_BYTE_LENGTH;
    }
}
