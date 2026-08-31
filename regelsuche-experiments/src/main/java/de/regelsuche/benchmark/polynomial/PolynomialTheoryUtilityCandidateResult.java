package de.regelsuche.benchmark.polynomial;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** One immutable target-blind terminal result for a frozen execution input. */
public record PolynomialTheoryUtilityCandidateResult(
    String resultId,
    String inputId,
    String executionRowId,
    String runId,
    String caseId,
    String profileId,
    String checkpointId,
    String adapterId,
    int admittedPrimitiveWork,
    int totalMechanicalWork,
    int factorizationWork,
    TerminalStatus terminalStatus,
    String detailCode,
    long primitiveWorkConsumed,
    long mechanicalWorkConsumed,
    long factorizationWorkConsumed,
    int generatedTransitions,
    String verifierOutcome,
    String transitionEvidenceHash
) {
    public static final String SCHEMA =
        "regelsuche.polynomial-theory-utility-candidate-result/v1";
    public static final String NO_TRANSITION_EVIDENCE = "NONE";
    private static final Pattern SHA_256 =
        Pattern.compile("sha256:[0-9a-f]{64}");

    public PolynomialTheoryUtilityCandidateResult {
        requireHash(resultId, "resultId");
        requireHash(inputId, "inputId");
        requireHash(executionRowId, "executionRowId");
        requireHash(runId, "runId");
        caseId = requireText(caseId, "caseId");
        profileId = requireText(profileId, "profileId");
        checkpointId = requireText(checkpointId, "checkpointId");
        adapterId = requireText(adapterId, "adapterId");
        terminalStatus = Objects.requireNonNull(terminalStatus, "terminalStatus");
        detailCode = requireText(detailCode, "detailCode");
        verifierOutcome = requireText(verifierOutcome, "verifierOutcome");
        transitionEvidenceHash = requireText(
            transitionEvidenceHash,
            "transitionEvidenceHash"
        );
        requireBudgets(
            admittedPrimitiveWork,
            totalMechanicalWork,
            factorizationWork,
            primitiveWorkConsumed,
            mechanicalWorkConsumed,
            factorizationWorkConsumed
        );
        boolean transition = terminalStatus == TerminalStatus.VALIDATED_TRANSITION;
        if (transition) {
            if (generatedTransitions < 1
                    || !"VERIFIED".equals(verifierOutcome)
                    || !SHA_256.matcher(transitionEvidenceHash).matches()) {
                throw new IllegalArgumentException(
                    "validated transition lacks verifier-bound evidence"
                );
            }
        } else if (generatedTransitions != 0
                || !NO_TRANSITION_EVIDENCE.equals(transitionEvidenceHash)) {
            throw new IllegalArgumentException(
                "non-transition result retains transition evidence"
            );
        }
        String expected = identity(
            inputId,
            executionRowId,
            runId,
            caseId,
            profileId,
            checkpointId,
            adapterId,
            admittedPrimitiveWork,
            totalMechanicalWork,
            factorizationWork,
            terminalStatus,
            detailCode,
            primitiveWorkConsumed,
            mechanicalWorkConsumed,
            factorizationWorkConsumed,
            generatedTransitions,
            verifierOutcome,
            transitionEvidenceHash
        );
        if (!resultId.equals(expected)) {
            throw new IllegalArgumentException(
                "candidate result identity differs from its fields"
            );
        }
    }

    public static PolynomialTheoryUtilityCandidateResult noTransition(
        PolynomialTheoryUtilityExecutionInput input,
        String detailCode
    ) {
        return create(
            input,
            TerminalStatus.NO_TRANSITION,
            detailCode,
            0L,
            0L,
            0L,
            0,
            "NOT_REQUESTED",
            NO_TRANSITION_EVIDENCE
        );
    }

    public static PolynomialTheoryUtilityCandidateResult create(
        PolynomialTheoryUtilityExecutionInput input,
        TerminalStatus terminalStatus,
        String detailCode,
        long primitiveWorkConsumed,
        long mechanicalWorkConsumed,
        long factorizationWorkConsumed,
        int generatedTransitions,
        String verifierOutcome,
        String transitionEvidenceHash
    ) {
        Objects.requireNonNull(input, "input");
        String id = identity(
            input.inputId(),
            input.rowId(),
            input.runId(),
            input.caseId(),
            input.profileId(),
            input.checkpointId(),
            input.adapterId(),
            input.admittedPrimitiveWork(),
            input.totalMechanicalWork(),
            input.factorizationWork(),
            terminalStatus,
            detailCode,
            primitiveWorkConsumed,
            mechanicalWorkConsumed,
            factorizationWorkConsumed,
            generatedTransitions,
            verifierOutcome,
            transitionEvidenceHash
        );
        return new PolynomialTheoryUtilityCandidateResult(
            id,
            input.inputId(),
            input.rowId(),
            input.runId(),
            input.caseId(),
            input.profileId(),
            input.checkpointId(),
            input.adapterId(),
            input.admittedPrimitiveWork(),
            input.totalMechanicalWork(),
            input.factorizationWork(),
            terminalStatus,
            detailCode,
            primitiveWorkConsumed,
            mechanicalWorkConsumed,
            factorizationWorkConsumed,
            generatedTransitions,
            verifierOutcome,
            transitionEvidenceHash
        );
    }

    public void validateAgainst(PolynomialTheoryUtilityExecutionInput input) {
        Objects.requireNonNull(input, "input");
        if (!inputId.equals(input.inputId())
                || !executionRowId.equals(input.rowId())
                || !runId.equals(input.runId())
                || !caseId.equals(input.caseId())
                || !profileId.equals(input.profileId())
                || !checkpointId.equals(input.checkpointId())
                || !adapterId.equals(input.adapterId())
                || admittedPrimitiveWork != input.admittedPrimitiveWork()
                || totalMechanicalWork != input.totalMechanicalWork()
                || factorizationWork != input.factorizationWork()) {
            throw new IllegalArgumentException(
                "candidate result differs from its frozen execution input"
            );
        }
    }

    private static String identity(
        String inputId,
        String executionRowId,
        String runId,
        String caseId,
        String profileId,
        String checkpointId,
        String adapterId,
        int admittedPrimitiveWork,
        int totalMechanicalWork,
        int factorizationWork,
        TerminalStatus terminalStatus,
        String detailCode,
        long primitiveWorkConsumed,
        long mechanicalWorkConsumed,
        long factorizationWorkConsumed,
        int generatedTransitions,
        String verifierOutcome,
        String transitionEvidenceHash
    ) {
        StringBuilder material = new StringBuilder();
        for (String value : List.of(
                SCHEMA,
                PolynomialTheoryUtilityPreregistration.STUDY_ID,
                inputId,
                executionRowId,
                runId,
                caseId,
                profileId,
                checkpointId,
                adapterId,
                Integer.toString(admittedPrimitiveWork),
                Integer.toString(totalMechanicalWork),
                Integer.toString(factorizationWork),
                terminalStatus.name(),
                detailCode,
                Long.toString(primitiveWorkConsumed),
                Long.toString(mechanicalWorkConsumed),
                Long.toString(factorizationWorkConsumed),
                Integer.toString(generatedTransitions),
                verifierOutcome,
                transitionEvidenceHash)) {
            material.append(value.length()).append(':').append(value);
        }
        return PolynomialTheoryUtilityExecutionIdentity.sha256(
            material.toString().getBytes(StandardCharsets.UTF_8)
        );
    }

    private static void requireBudgets(
        int admittedPrimitiveWork,
        int totalMechanicalWork,
        int factorizationWork,
        long primitiveWorkConsumed,
        long mechanicalWorkConsumed,
        long factorizationWorkConsumed
    ) {
        if (factorizationWork < 1
                || admittedPrimitiveWork < factorizationWork
                || totalMechanicalWork < admittedPrimitiveWork
                || primitiveWorkConsumed < 0
                || mechanicalWorkConsumed < 0
                || factorizationWorkConsumed < 0
                || primitiveWorkConsumed > admittedPrimitiveWork
                || mechanicalWorkConsumed > totalMechanicalWork
                || factorizationWorkConsumed > factorizationWork
                || factorizationWorkConsumed > mechanicalWorkConsumed) {
            throw new IllegalArgumentException(
                "candidate result work differs from frozen authority"
            );
        }
    }

    private static void requireHash(String value, String name) {
        if (value == null || !SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " is not SHA-256");
        }
    }

    private static String requireText(String value, String name) {
        String text = Objects.requireNonNull(value, name);
        if (text.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return text;
    }

    public enum TerminalStatus {
        VALIDATED_TRANSITION,
        NO_TRANSITION,
        UNSUPPORTED,
        BUDGET_INCONCLUSIVE,
        TECHNICAL_FAILURE
    }
}
