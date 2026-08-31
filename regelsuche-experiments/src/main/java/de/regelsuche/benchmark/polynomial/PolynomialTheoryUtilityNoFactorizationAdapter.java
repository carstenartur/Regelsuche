package de.regelsuche.benchmark.polynomial;

import java.util.Objects;

/** Frozen control adapter that deliberately performs no factorization work. */
public final class PolynomialTheoryUtilityNoFactorizationAdapter
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
        var checkpoint = PolynomialTheoryUtilityExecutionPlan.CHECKPOINTS.stream()
            .filter(value -> value.checkpointId().equals(
                descriptor.checkpointId()
            ))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "no-factorization checkpoint is not frozen"
            ));
        var profile = PolynomialTheoryUtilityExecutionInputs.profile(PROFILE_ID);
        String expectedRunId =
            PolynomialTheoryUtilityExecutionIdentity.runId(profile, checkpoint);
        if (!PROFILE_ID.equals(descriptor.profileId())
                || !ADAPTER_ID.equals(descriptor.adapterId())
                || !expectedRunId.equals(descriptor.runId())
                || descriptor.expectedCaseCount()
                    != PolynomialTheoryUtilityCaseCorpus.ORDERED_CASE_IDS.size()) {
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
        public PolynomialTheoryUtilityCandidateResult execute(
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
                PolynomialTheoryUtilityCaseCorpus.ORDERED_CASE_IDS.get(nextCase);
            if (!descriptor.runId().equals(input.runId())
                    || !descriptor.profileId().equals(input.profileId())
                    || !descriptor.checkpointId().equals(input.checkpointId())
                    || !descriptor.adapterId().equals(input.adapterId())
                    || !expectedCaseId.equals(input.caseId())
                    || !input.caseId().equals(formationCase.caseId())) {
                throw new IllegalArgumentException(
                    "no-factorization input differs from its frozen position"
                );
            }
            nextCase++;
            return PolynomialTheoryUtilityCandidateResult.noTransition(
                input,
                DETAIL_CODE
            );
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
