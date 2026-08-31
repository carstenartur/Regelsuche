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
            verifierOutcome = requireText(verifierOutcome, "verifierOutcome");
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

        static CandidateResult noTransition(
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

        static CandidateResult create(
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

        void validateAgainst(PolynomialTheoryUtilityExecutionInput expected) {
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

    /** Frozen control adapter that deliberately performs no factorization. */
    final class NoFactorizationAdapter
            implements PolynomialTheoryUtilityProfileAdapter {
        public static final String PROFILE_ID = "NO_FACTORIZATION";
        public static final String ADAPTER_ID =
            "regelsuche.polynomial-theory-utility.no-factorization/v1";
        public static final String DETAIL_CODE =
            "FACTORIZATION_DISABLED_BY_FROZEN_PROFILE";

        @Override
        public String profileId() {
            return PROFILE_ID;
        }

        @Override
        public String adapterId() {
            return ADAPTER_ID;
        }

        @Override
        public Run openRun(RunDescriptor descriptor) {
            Objects.requireNonNull(descriptor, "descriptor");
            var checkpoint =
                PolynomialTheoryUtilityExecutionPlan.CHECKPOINTS.stream()
                    .filter(value -> value.checkpointId().equals(
                        descriptor.checkpointId()
                    ))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                        "no-factorization checkpoint is not frozen"
                    ));
            var profile =
                PolynomialTheoryUtilityExecutionInputs.profile(PROFILE_ID);
            String expectedRunId =
                PolynomialTheoryUtilityExecutionIdentity.runId(
                    profile,
                    checkpoint
                );
            if (!PROFILE_ID.equals(descriptor.profileId())
                    || !ADAPTER_ID.equals(descriptor.adapterId())
                    || !expectedRunId.equals(descriptor.runId())
                    || descriptor.expectedCaseCount()
                        != PolynomialTheoryUtilityCaseCorpus
                            .ORDERED_CASE_IDS.size()) {
                throw new IllegalArgumentException(
                    "no-factorization run differs from the frozen profile"
                );
            }
            return new BaselineRun(descriptor);
        }

        private static final class BaselineRun implements Run {
            private final RunDescriptor descriptor;
            private int nextCase;
            private boolean closed;

            private BaselineRun(RunDescriptor descriptor) {
                this.descriptor = descriptor;
            }

            @Override
            public CandidateResult execute(
                PolynomialTheoryUtilityExecutionInput input,
                PolynomialTheoryUtilityCaseCorpus.FormationCase formationCase
            ) {
                Objects.requireNonNull(input, "input");
                Objects.requireNonNull(formationCase, "formationCase");
                if (closed || nextCase >= descriptor.expectedCaseCount()) {
                    throw new IllegalStateException(
                        "no-factorization run cannot accept another case"
                    );
                }
                String expectedCaseId =
                    PolynomialTheoryUtilityCaseCorpus.ORDERED_CASE_IDS.get(
                        nextCase
                    );
                if (!descriptor.runId().equals(input.runId())
                        || !descriptor.profileId().equals(input.profileId())
                        || !descriptor.checkpointId().equals(
                            input.checkpointId()
                        )
                        || !descriptor.adapterId().equals(input.adapterId())
                        || !expectedCaseId.equals(input.caseId())
                        || !input.caseId().equals(formationCase.caseId())) {
                    throw new IllegalArgumentException(
                        "no-factorization input differs from its frozen position"
                    );
                }
                nextCase++;
                return CandidateResult.noTransition(input, DETAIL_CODE);
            }

            @Override
            public void close() {
                if (closed) {
                    throw new IllegalStateException(
                        "no-factorization run is already closed"
                    );
                }
                closed = true;
                if (nextCase != descriptor.expectedCaseCount()) {
                    throw new IllegalStateException(
                        "no-factorization run closed before all frozen cases"
                    );
                }
            }
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
