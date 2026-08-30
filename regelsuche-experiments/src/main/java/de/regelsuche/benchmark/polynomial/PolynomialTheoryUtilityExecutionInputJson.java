package de.regelsuche.benchmark.polynomial;

import java.util.List;

/** Canonical UTF-8/LF representation of target-blind execution inputs. */
final class PolynomialTheoryUtilityExecutionInputJson {
    private PolynomialTheoryUtilityExecutionInputJson() {
    }

    static String canonical(
        List<PolynomialTheoryUtilityExecutionInput> inputs
    ) {
        StringBuilder target = new StringBuilder(340_000);
        target.append("{\n");
        field(
            target,
            "schema",
            PolynomialTheoryUtilityExecutionInputs.SCHEMA
        );
        field(
            target,
            "studyId",
            PolynomialTheoryUtilityPreregistration.STUDY_ID
        );
        field(
            target,
            "evidenceStatus",
            PolynomialTheoryUtilityExecutionInputs.EVIDENCE_STATUS
        );
        field(
            target,
            "inputSelectionTiming",
            PolynomialTheoryUtilityExecutionInputs.INPUT_SELECTION_TIMING
        );
        field(
            target,
            "qualificationExposure",
            PolynomialTheoryUtilityExecutionInputs.QUALIFICATION_EXPOSURE
        );
        binding(
            target,
            "preregistrationBinding",
            PolynomialTheoryUtilityPreregistration.BYTE_LENGTH,
            PolynomialTheoryUtilityPreregistration.CONTENT_HASH
        );
        binding(
            target,
            "formationBinding",
            PolynomialTheoryUtilityCaseCorpus.FORMATION_BYTE_LENGTH,
            PolynomialTheoryUtilityCaseCorpus.FORMATION_CONTENT_HASH
        );
        target.append("  \"qualificationBinding\": {\"path\":\"")
            .append(PolynomialTheoryUtilityCaseCorpus.QUALIFICATION_FILE_NAME)
            .append("\",\"byteLength\":")
            .append(PolynomialTheoryUtilityCaseCorpus.QUALIFICATION_BYTE_LENGTH)
            .append(",\"contentHash\":\"")
            .append(
                PolynomialTheoryUtilityCaseCorpus
                    .QUALIFICATION_CONTENT_HASH
            )
            .append("\"},\n");
        binding(
            target,
            "planBinding",
            PolynomialTheoryUtilityExecutionPlan.EXPECTED_BYTE_LENGTH,
            PolynomialTheoryUtilityExecutionPlan.EXPECTED_CONTENT_HASH
        );
        number(target, "inputCount", inputs.size());
        executionBoundary(target);
        target.append("  \"inputs\": [\n");
        appendInputs(target, inputs);
        return target.append("  ]\n}\n").toString();
    }

    private static void executionBoundary(StringBuilder target) {
        target.append("  \"executionBoundary\": {\"rowOrder\":\"")
            .append(PolynomialTheoryUtilityExecutionPlan.ROW_ORDER)
            .append("\",\"runGrouping\":\"")
            .append(PolynomialTheoryUtilityExecutionPlan.RUN_GROUPING)
            .append("\",\"formationResolution\":\"")
            .append(
                PolynomialTheoryUtilityExecutionInputs
                    .FORMATION_RESOLUTION
            )
            .append("\",\"profilePolicySource\":\"")
            .append(
                PolynomialTheoryUtilityExecutionInputs
                    .PROFILE_POLICY_SOURCE
            )
            .append("\",\"resultVisibility\":\"")
            .append(PolynomialTheoryUtilityExecutionInputs.RESULT_VISIBILITY)
            .append("\",\"decisionAuthority\":\"")
            .append(PolynomialTheoryUtilityExecutionInputs.DECISION_AUTHORITY)
            .append("\",\"adapterOutputAuthority\":\"")
            .append(
                PolynomialTheoryUtilityExecutionInputs
                    .ADAPTER_OUTPUT_AUTHORITY
            )
            .append("\"},\n");
    }

    private static void appendInputs(
        StringBuilder target,
        List<PolynomialTheoryUtilityExecutionInput> inputs
    ) {
        for (int index = 0; index < inputs.size(); index++) {
            var value = inputs.get(index);
            target.append("    {\"inputId\":\"")
                .append(value.inputId())
                .append("\",\"rowId\":\"").append(value.rowId())
                .append("\",\"runId\":\"").append(value.runId())
                .append("\",\"caseId\":\"").append(value.caseId())
                .append("\",\"profileId\":\"").append(value.profileId())
                .append("\",\"checkpointId\":\"")
                .append(value.checkpointId())
                .append("\",\"adapterId\":\"").append(value.adapterId())
                .append("\",\"admittedPrimitiveWork\":")
                .append(value.admittedPrimitiveWork())
                .append(",\"totalMechanicalWork\":")
                .append(value.totalMechanicalWork())
                .append(",\"factorizationWork\":")
                .append(value.factorizationWork())
                .append(",\"inputStatus\":\"")
                .append(value.inputStatus()).append("\"}")
                .append(index + 1 < inputs.size() ? ",\n" : "\n");
        }
    }

    private static void field(
        StringBuilder target,
        String name,
        String value
    ) {
        target.append("  \"").append(name).append("\": \"")
            .append(value).append("\",\n");
    }

    private static void number(
        StringBuilder target,
        String name,
        long value
    ) {
        target.append("  \"").append(name).append("\": ")
            .append(value).append(",\n");
    }

    private static void binding(
        StringBuilder target,
        String name,
        long byteLength,
        String hash
    ) {
        target.append("  \"").append(name)
            .append("\": {\"byteLength\":").append(byteLength)
            .append(",\"contentHash\":\"").append(hash)
            .append("\"},\n");
    }
}
