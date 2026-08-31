package de.regelsuche.benchmark.polynomial;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

/** One immutable target-blind terminal result for a frozen execution input. */
public record PolynomialTheoryUtilityCandidateResult(
    String resultId,
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
    public static final String SCHEMA =
        "regelsuche.polynomial-theory-utility-candidate-result/v1";
    public static final String NO_TRANSITION_EVIDENCE = "NONE";
    private static final Pattern SHA_256 =
        Pattern.compile("sha256:[0-9a-f]{64}");

    public PolynomialTheoryUtilityCandidateResult {
        resultId = requireHash(resultId, "resultId");
        input = Objects.requireNonNull(input, "input");
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
        if (!resultId.equals(identity(
                input,
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
        return new PolynomialTheoryUtilityCandidateResult(
            identity(
                input,
                terminalStatus,
                detailCode,
                primitiveWorkConsumed,
                mechanicalWorkConsumed,
                factorizationWorkConsumed,
                generatedTransitions,
                verifierOutcome,
                transitionEvidenceHash
            ),
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
    }

    public void validateAgainst(PolynomialTheoryUtilityExecutionInput expected) {
        if (!input.equals(Objects.requireNonNull(expected, "expected"))) {
            throw new IllegalArgumentException(
                "candidate result refers to another frozen execution input"
            );
        }
    }

    private static String identity(
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
        StringBuilder material = new StringBuilder();
        append(material, SCHEMA);
        append(material, PolynomialTheoryUtilityPreregistration.STUDY_ID);
        append(material, Objects.requireNonNull(input, "input").inputId());
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

    public enum TerminalStatus {
        VALIDATED_TRANSITION,
        NO_TRANSITION,
        UNSUPPORTED,
        BUDGET_INCONCLUSIVE,
        TECHNICAL_FAILURE
    }
}
