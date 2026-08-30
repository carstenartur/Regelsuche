package de.regelsuche.benchmark.polynomial;

import java.util.Objects;
import java.util.regex.Pattern;

/** One target-blind input envelope for a frozen execution-plan row. */
public record PolynomialTheoryUtilityExecutionInput(
    String inputId,
    String rowId,
    String runId,
    String caseId,
    String profileId,
    String checkpointId,
    String adapterId,
    int admittedPrimitiveWork,
    int totalMechanicalWork,
    int factorizationWork,
    String inputStatus
) {
    private static final Pattern SHA256 = Pattern.compile(
        "sha256:[0-9a-f]{64}"
    );

    public PolynomialTheoryUtilityExecutionInput {
        requireSha256(inputId, "inputId");
        requireSha256(rowId, "rowId");
        requireSha256(runId, "runId");
        requireText(caseId, "caseId");
        requireText(profileId, "profileId");
        requireText(checkpointId, "checkpointId");
        requireText(adapterId, "adapterId");
        requireText(inputStatus, "inputStatus");

        if (!PolynomialTheoryUtilityCaseCorpus.ORDERED_CASE_IDS.contains(
                caseId)) {
            throw new IllegalArgumentException(
                "execution input refers to an unknown frozen case");
        }
        var profile = PolynomialTheoryUtilityExecutionInputs.profile(
            profileId);
        if (!profile.adapterId().equals(adapterId)) {
            throw new IllegalArgumentException(
                "execution input adapter differs from the frozen profile");
        }
        boolean checkpointKnown =
            PolynomialTheoryUtilityExecutionPlan.CHECKPOINTS.stream()
                .anyMatch(value ->
                    value.checkpointId().equals(checkpointId));
        if (!checkpointKnown) {
            throw new IllegalArgumentException(
                "execution input checkpoint is not frozen");
        }
        if (factorizationWork < 1
                || admittedPrimitiveWork < factorizationWork
                || totalMechanicalWork < admittedPrimitiveWork) {
            throw new IllegalArgumentException(
                "execution input work budgets are invalid");
        }
        if (!PolynomialTheoryUtilityExecutionInputs.INPUT_STATUS.equals(
                inputStatus)) {
            throw new IllegalArgumentException(
                "execution input must remain ready and not executed");
        }
    }

    private static void requireSha256(String value, String name) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " is not SHA-256");
        }
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
