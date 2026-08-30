package de.regelsuche.benchmark.polynomial;

import java.util.Objects;

/** Real baseline adapter: it deliberately performs no factorization work. */
public final class PolynomialTheoryUtilityNoFactorizationAdapter
        implements PolynomialTheoryUtilityProfileAdapter {
    public static final String ADAPTER_ID =
        "regelsuche.polynomial-theory-utility.no-factorization/v1";
    public static final String PROFILE_ID = "NO_FACTORIZATION";

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
        if (!PROFILE_ID.equals(descriptor.profileId())
                || !ADAPTER_ID.equals(descriptor.adapterId())) {
            throw new IllegalArgumentException(
                "no-factorization adapter received another profile"
            );
        }
        return new BaselineRun(descriptor);
    }

    private static final class BaselineRun implements Run {
        private final RunDescriptor descriptor;
        private int executions;
        private boolean closed;

        private BaselineRun(RunDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        @Override
        public Outcome execute(
            PolynomialTheoryUtilityExecutionInput input,
            PolynomialTheoryUtilityCaseCorpus.FormationCase formationCase
        ) {
            Objects.requireNonNull(input, "input");
            Objects.requireNonNull(formationCase, "formationCase");
            if (closed) {
                throw new IllegalArgumentException(
                    "no-factorization run is already closed"
                );
            }
            if (!descriptor.runId().equals(input.runId())
                    || !descriptor.profileId().equals(input.profileId())
                    || !descriptor.checkpointId().equals(
                        input.checkpointId()
                    )
                    || !descriptor.adapterId().equals(input.adapterId())
                    || !input.caseId().equals(formationCase.caseId())) {
                throw new IllegalArgumentException(
                    "no-factorization input differs from its frozen context"
                );
            }
            executions++;
            if (executions > descriptor.expectedCaseCount()) {
                throw new IllegalArgumentException(
                    "no-factorization run received too many cases"
                );
            }
            return Outcome.noTransition(
                "PROFILE_FORBIDS_FACTORIZATION"
            );
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
