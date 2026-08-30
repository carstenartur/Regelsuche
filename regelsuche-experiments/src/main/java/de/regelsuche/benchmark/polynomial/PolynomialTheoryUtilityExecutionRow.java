package de.regelsuche.benchmark.polynomial;

import java.util.regex.Pattern;

/** One target-blind case/profile/checkpoint row before execution. */
public record PolynomialTheoryUtilityExecutionRow(
    String rowId,
    String runId,
    String caseId,
    String profileId,
    String checkpointId,
    int admittedPrimitiveWork,
    int totalMechanicalWork,
    int factorizationWork,
    String resultStatus
) {
    private static final Pattern SHA_256 =
        Pattern.compile("sha256:[0-9a-f]{64}");

    public PolynomialTheoryUtilityExecutionRow {
        if (rowId == null
                || !SHA_256.matcher(rowId).matches()
                || runId == null
                || !SHA_256.matcher(runId).matches()
                || caseId == null
                || caseId.isBlank()
                || profileId == null
                || profileId.isBlank()
                || checkpointId == null
                || checkpointId.isBlank()
                || admittedPrimitiveWork < 1
                || totalMechanicalWork < admittedPrimitiveWork
                || factorizationWork < 1
                || factorizationWork > admittedPrimitiveWork
                || !PolynomialTheoryUtilityExecutionPlan.RESULT_STATUS.equals(
                    resultStatus)) {
            throw new IllegalArgumentException("invalid execution row");
        }
    }
}
