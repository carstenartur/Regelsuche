package de.regelsuche.benchmark.polynomial;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

/** Target-blind run-scoped execution boundary for one frozen study profile. */
public interface PolynomialTheoryUtilityProfileAdapter {
    String profileId();

    String adapterId();

    Run openRun(RunDescriptor descriptor);

    interface Run extends AutoCloseable {
        CandidateResult execute(
            PolynomialTheoryUtilityExecutionInput input,
            PolynomialTheoryUtilityCaseCorpus.FormationCase formationCase
        );

        @Override
        void close();
    }

    record RunDescriptor(
        String runId,
        String profileId,
        String checkpointId,
        String adapterId,
        int expectedCaseCount
    ) {
        public RunDescriptor {
            runId = requireText(runId, "runId");
            profileId = requireText(profileId, "profileId");
            checkpointId = requireText(checkpointId, "checkpointId");
            adapterId = requireText(adapterId, "adapterId");
            if (expectedCaseCount < 1) {
                throw new IllegalArgumentException(
                    "expectedCaseCount must be positive"
                );
            }
        }
    }

    /** One immutable terminal result for an exact frozen execution input. */
    record CandidateResult(
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

        public CandidateResult {
            resultId = requireHash(resultId, "resultId");
            input = Objects.requireNonNull(input, "input");
            terminalStatus = Objects.requireNonNull(
                terminalStatus,
                "terminalStatus"
            );
            detailCode = requireText(detailCode, "detailCode");
            verifierOutcome = requireText(
                verifierOutcome,
                "verifierOutcome"
            );
            transitionEvidenceHash = requireText(
                transitionEvidenceHash,
                "transitionEvidenceHash"
            );
            requireWorkWithinAuthority(
                input,
                primitiveWorkConsumed,
                mechanicalWorkConsumed,
                factorizationWorkConsumed,
                generatedTransitions
            );
            requireEvidence(
                terminalStatus,
                generatedTransitions,
                verifierOutcome,
                transitionEvidenceHash
            );
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

        public static CandidateResult noTransition(
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

        public static CandidateResult create(
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
            return new CandidateResult(
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

        public void validateAgainst(
            PolynomialTheoryUtilityExecutionInput expected
        ) {
            if (!input.equals(Objects.requireNonNull(expected, "expected"))) {
                throw new IllegalArgumentException(
                    "candidate result refers to another frozen execution input"
                );
            }
        }

        private static void requireWorkWithinAuthority(
            PolynomialTheoryUtilityExecutionInput input,
            long primitive,
            long mechanical,
            long factorization,
            int transitions
        ) {
            if (primitive < 0
                    || mechanical < 0
                    || factorization < 0
                    || transitions < 0
                    || primitive > input.admittedPrimitiveWork()
                    || mechanical > input.totalMechanicalWork()
                    || factorization > input.factorizationWork()
                    || factorization > mechanical) {
                throw new IllegalArgumentException(
                    "candidate result work differs from frozen authority"
                );
            }
        }

        private static void requireEvidence(
            TerminalStatus status,
            int transitions,
            String verifier,
            String evidence
        ) {
            boolean validated = status == TerminalStatus.VALIDATED_TRANSITION;
            if (validated) {
                if (transitions < 1
                        || !"VERIFIED".equals(verifier)
                        || !SHA_256.matcher(evidence).matches()) {
                    throw new IllegalArgumentException(
                        "validated transition lacks verifier-bound evidence"
                    );
                }
            } else if (transitions != 0
                    || !NO_TRANSITION_EVIDENCE.equals(evidence)) {
                throw new IllegalArgumentException(
                    "non-transition result retains transition evidence"
                );
            }
        }

        private static String identity(
            PolynomialTheoryUtilityExecutionInput input,
            TerminalStatus status,
            String detail,
            long primitive,
            long mechanical,
            long factorization,
            int transitions,
            String verifier,
            String evidence
        ) {
            StringBuilder material = new StringBuilder();
            append(material, SCHEMA);
            append(material, PolynomialTheoryUtilityPreregistration.STUDY_ID);
            append(material, Objects.requireNonNull(input, "input").inputId());
            append(material, Objects.requireNonNull(status, "status").name());
            append(material, requireText(detail, "detailCode"));
            append(material, Long.toString(primitive));
            append(material, Long.toString(mechanical));
            append(material, Long.toString(factorization));
            append(material, Integer.toString(transitions));
            append(material, requireText(verifier, "verifierOutcome"));
            append(material, requireText(evidence, "transitionEvidenceHash"));
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

        public enum TerminalStatus {
            VALIDATED_TRANSITION,
            NO_TRANSITION,
            UNSUPPORTED,
            BUDGET_INCONCLUSIVE,
            TECHNICAL_FAILURE
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
