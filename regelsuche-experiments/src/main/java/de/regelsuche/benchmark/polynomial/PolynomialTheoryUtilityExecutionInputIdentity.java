package de.regelsuche.benchmark.polynomial;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Content-addresses target-blind polynomial utility execution inputs. */
final class PolynomialTheoryUtilityExecutionInputIdentity {
    private PolynomialTheoryUtilityExecutionInputIdentity() {
    }

    static String inputId(
        PolynomialTheoryUtilityExecutionRow row,
        PolynomialTheoryUtilityExecutionProfile profile
    ) {
        StringBuilder material = new StringBuilder();
        for (String value : List.of(
                PolynomialTheoryUtilityExecutionInputs.SCHEMA,
                PolynomialTheoryUtilityPreregistration.STUDY_ID,
                PolynomialTheoryUtilityPreregistration.CONTENT_HASH,
                PolynomialTheoryUtilityCaseCorpus.FORMATION_CONTENT_HASH,
                PolynomialTheoryUtilityCaseCorpus.QUALIFICATION_CONTENT_HASH,
                PolynomialTheoryUtilityExecutionPlan.EXPECTED_CONTENT_HASH,
                Long.toString(
                    PolynomialTheoryUtilityExecutionPlan
                        .EXPECTED_BYTE_LENGTH
                ),
                row.rowId(),
                row.runId(),
                row.caseId(),
                row.profileId(),
                row.checkpointId(),
                profile.adapterId(),
                Integer.toString(row.admittedPrimitiveWork()),
                Integer.toString(row.totalMechanicalWork()),
                Integer.toString(row.factorizationWork()),
                PolynomialTheoryUtilityExecutionInputs.INPUT_STATUS,
                PolynomialTheoryUtilityExecutionInputs
                    .INPUT_SELECTION_TIMING,
                PolynomialTheoryUtilityExecutionInputs
                    .QUALIFICATION_EXPOSURE,
                PolynomialTheoryUtilityExecutionInputs
                    .FORMATION_RESOLUTION,
                PolynomialTheoryUtilityExecutionInputs
                    .PROFILE_POLICY_SOURCE,
                PolynomialTheoryUtilityExecutionInputs.RESULT_VISIBILITY,
                PolynomialTheoryUtilityExecutionInputs.DECISION_AUTHORITY,
                PolynomialTheoryUtilityExecutionInputs
                    .ADAPTER_OUTPUT_AUTHORITY)) {
            append(material, value);
        }
        return sha256(material.toString().getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }
}
