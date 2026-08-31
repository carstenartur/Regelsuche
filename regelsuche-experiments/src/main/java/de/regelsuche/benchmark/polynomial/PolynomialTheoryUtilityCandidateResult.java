package de.regelsuche.benchmark.polynomial;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

/** One immutable target-blind terminal result for a frozen execution input. */
public final class PolynomialTheoryUtilityCandidateResult {
    public static final String SCHEMA =
        "regelsuche.polynomial-theory-utility-candidate-result/v1";
    public static final String NO_TRANSITION_EVIDENCE = "NONE";
    private static final Pattern SHA_256 =
        Pattern.compile("sha256:[0-9a-f]{64}");

    private final String resultId;
    private final String inputId;
    private final TerminalStatus terminalStatus;
    private final String detailCode;
    private final long primitiveWorkConsumed;
    private final long mechanicalWorkConsumed;
    private final long factorizationWorkConsumed;
    private final int generatedTransitions;
    private final String verifierOutcome;
    private final String transitionEvidenceHash;

    private PolynomialTheoryUtilityCandidateResult(
        String resultId,
        String inputId,
        TerminalStatus terminalStatus,
        String detailCode,
        long primitiveWorkConsumed,
        long mechanicalWorkConsumed,
        long factorizationWorkConsumed,
        int generatedTransitions,
        String verifierOutcome,
        String transitionEvidenceHash
    ) {
        this.resultId = requireHash(resultId, "resultId");
        this.inputId = requireHash(inputId, "inputId");
        this.terminalStatus = Objects.requireNonNull(
            terminalStatus,
            "terminalStatus"
        );
        this.detailCode = requireText(detailCode, "detailCode");
        this.primitiveWorkConsumed = primitiveWorkConsumed;
        this.mechanicalWorkConsumed = mechanicalWorkConsumed;
        this.factorizationWorkConsumed = factorizationWorkConsumed;
        this.generatedTransitions = generatedTransitions;
        this.verifierOutcome = requireText(verifierOutcome, "verifierOutcome");
        this.transitionEvidenceHash = requireText(
            transitionEvidenceHash,
            "transitionEvidenceHash"
        );
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
        validate(
            input,
            terminalStatus,
            primitiveWorkConsumed,
            mechanicalWorkConsumed,
            factorizationWorkConsumed,
            generatedTransitions,
            verifierOutcome,
            transitionEvidenceHash
        );
        String id = identity(
            input.inputId(),
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
        if (!inputId.equals(input.inputId())) {
            throw new IllegalArgumentException(
                "candidate result refers to another frozen execution input"
            );
        }
        validate(
            input,
            terminalStatus,
            primitiveWorkConsumed,
            mechanicalWorkConsumed,
            factorizationWorkConsumed,
            generatedTransitions,
            verifierOutcome,
            transitionEvidenceHash
        );
        if (!resultId.equals(identity(
                inputId,
                terminalStatus,
                detailCode,
                primitiveWorkConsumed,
                mechanicalWorkConsumed,
                factorizationWorkConsumed,
                generatedTransitions,
                verifierOutcome,
                transitionEvidenceHash))) {
            throw new IllegalArgumentException(
                "candidate result identity differs from its fields"
            );
        }
    }

    private static void validate(
        PolynomialTheoryUtilityExecutionInput input,
        TerminalStatus terminalStatus,
        long primitiveWorkConsumed,
        long mechanicalWorkConsumed,
        long factorizationWorkConsumed,
        int generatedTransitions,
        String verifierOutcome,
        String transitionEvidenceHash
    ) {
        Objects.requireNonNull(terminalStatus, "terminalStatus");
        String verifier = requireText(verifierOutcome, "verifierOutcome");
        String evidence = requireText(
            transitionEvidenceHash,
            "transitionEvidenceHash"
        );
        if (primitiveWorkConsumed < 0
                || mechanicalWorkConsumed < 0
                || factorizationWorkConsumed < 0
                || generatedTransitions < 0
                || primitiveWorkConsumed > input.admittedPrimitiveWork()
                || mechanicalWorkConsumed > input.totalMechanicalWork()
                || factorizationWorkConsumed > input.factorizationWork()
                || factorizationWorkConsumed > mechanicalWorkConsumed) {
            throw new IllegalArgumentException(
                "candidate result work differs from frozen authority"
            );
        }
        boolean transition =
            terminalStatus == TerminalStatus.VALIDATED_TRANSITION;
        if (transition) {
            if (generatedTransitions < 1
                    || !"VERIFIED".equals(verifier)
                    || !SHA_256.matcher(evidence).matches()) {
                throw new IllegalArgumentException(
                    "validated transition lacks verifier-bound evidence"
                );
            }
        } else if (generatedTransitions != 0
                || !NO_TRANSITION_EVIDENCE.equals(evidence)) {
            throw new IllegalArgumentException(
                "non-transition result retains transition evidence"
            );
        }
    }

    private static String identity(
        String inputId,
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
        append(material, SCHEMA);
        append(material, PolynomialTheoryUtilityPreregistration.STUDY_ID);
        append(material, requireHash(inputId, "inputId"));
        append(material, Objects.requireNonNull(terminalStatus).name());
        append(material, requireText(detailCode, "detailCode"));
        append(material, Long.toString(primitiveWorkConsumed));
        append(material, Long.toString(mechanicalWorkConsumed));
        append(material, Long.toString(factorizationWorkConsumed));
        append(material, Integer.toString(generatedTransitions));
        append(material, requireText(verifierOutcome, "verifierOutcome"));
        append(material, requireText(
            transitionEvidenceHash,
            "transitionEvidenceHash"
        ));
        return PolynomialTheoryUtilityExecutionIdentity.sha256(
            material.toString().getBytes(StandardCharsets.UTF_8)
        );
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }

    private static String requireHash(String value, String name) {
        if (value == null || !SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " is not SHA-256");
        }
        return value;
    }

    private static String requireText(String value, String name) {
        String text = Objects.requireNonNull(value, name);
        if (text.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return text;
    }

    public String resultId() {
        return resultId;
    }

    public String inputId() {
        return inputId;
    }

    public TerminalStatus terminalStatus() {
        return terminalStatus;
    }

    public String detailCode() {
        return detailCode;
    }

    public long primitiveWorkConsumed() {
        return primitiveWorkConsumed;
    }

    public long mechanicalWorkConsumed() {
        return mechanicalWorkConsumed;
    }

    public long factorizationWorkConsumed() {
        return factorizationWorkConsumed;
    }

    public int generatedTransitions() {
        return generatedTransitions;
    }

    public String verifierOutcome() {
        return verifierOutcome;
    }

    public String transitionEvidenceHash() {
        return transitionEvidenceHash;
    }

    public enum TerminalStatus {
        VALIDATED_TRANSITION,
        NO_TRANSITION,
        UNSUPPORTED,
        BUDGET_INCONCLUSIVE,
        TECHNICAL_FAILURE
    }
}
