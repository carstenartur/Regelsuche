package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityCandidateResult.TerminalStatus;
import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityMeasuredExecution.MeasuredRun;
import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityProfileAdapter.Run;
import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityProfileAdapter.RunDescriptor;
import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityTransitionOutcome.CacheDisposition;
import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityTransitionTrace.PrimitiveStep;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilityMeasuredExecutionTest {
    private static final String ON_DEMAND =
        "ON_DEMAND_VERIFIED_FACTORIZATION";
    private static final String TRANSITION_CASE =
        "z02-difference-of-squares";

    @Test
    void bindsOneZeroObservationMeasurementToEveryRunnerResult() {
        var batch = new PolynomialTheoryUtilityMeasuredExecution().execute(
            PolynomialTheoryUtilityExecutionInputs.freeze(),
            zeroAdapters()
        );

        assertEquals(
            PolynomialTheoryUtilityExecutionInputs.EXPECTED_INPUT_COUNT,
            batch.rowCount()
        );
        assertEquals(batch.results().size(), batch.measurements().size());
        assertTrue(batch.measurements().stream().allMatch(value ->
            value.generatedTransitionCount() == 0
                && value.factorizationRequestCount() == 0
                && value.cacheEvents().isEmpty()
        ));
    }

    @Test
    void retainsNonEmptyEvidenceFromTheExactMeasuredRun() {
        List<PolynomialTheoryUtilityProfileAdapter> adapters = zeroAdapters();
        replace(adapters, ON_DEMAND, new MeasuredOnDemandAdapter());

        var batch = new PolynomialTheoryUtilityMeasuredExecution().execute(
            PolynomialTheoryUtilityExecutionInputs.freeze(),
            adapters
        );

        long transitions = batch.measurements().stream()
            .mapToLong(
                PolynomialTheoryUtilityCandidateMeasurements
                    ::generatedTransitionCount
            )
            .sum();
        long requests = batch.measurements().stream()
            .mapToLong(
                PolynomialTheoryUtilityCandidateMeasurements
                    ::factorizationRequestCount
            )
            .sum();
        long tracedRules = batch.measurements().stream()
            .flatMap(value -> value.primitiveRuleIds().stream())
            .filter("verified-factorization-transition"::equals)
            .count();

        assertEquals(
            PolynomialTheoryUtilityExecutionPlan.CHECKPOINTS.size(),
            transitions
        );
        assertEquals(transitions, requests);
        assertEquals(transitions, tracedRules);
    }

    @Test
    void rejectsALegacyRunThatReturnsATransitionWithoutMeasurements() {
        List<PolynomialTheoryUtilityProfileAdapter> adapters = zeroAdapters();
        replace(adapters, ON_DEMAND, new LegacyOnDemandAdapter());

        assertThrows(
            IllegalArgumentException.class,
            () -> new PolynomialTheoryUtilityMeasuredExecution().execute(
                PolynomialTheoryUtilityExecutionInputs.freeze(),
                adapters
            )
        );
    }

    private static List<PolynomialTheoryUtilityProfileAdapter> zeroAdapters() {
        List<PolynomialTheoryUtilityProfileAdapter> adapters =
            new ArrayList<>();
        for (var profile : PolynomialTheoryUtilityExecutionPlan.PROFILES) {
            if (PolynomialTheoryUtilityProfileAdapter.NoFactorizationAdapter
                    .PROFILE_ID.equals(profile.profileId())) {
                adapters.add(
                    new PolynomialTheoryUtilityProfileAdapter
                        .NoFactorizationAdapter()
                );
            } else {
                adapters.add(new ZeroAdapter(
                    profile.profileId(),
                    profile.adapterId()
                ));
            }
        }
        return adapters;
    }

    private static void replace(
        List<PolynomialTheoryUtilityProfileAdapter> adapters,
        String profileId,
        PolynomialTheoryUtilityProfileAdapter replacement
    ) {
        for (int index = 0; index < adapters.size(); index++) {
            if (profileId.equals(adapters.get(index).profileId())) {
                adapters.set(index, replacement);
                return;
            }
        }
        throw new IllegalStateException("profile adapter not present");
    }

    private record ZeroAdapter(String profileId, String adapterId)
            implements PolynomialTheoryUtilityProfileAdapter {
        private ZeroAdapter {
            Objects.requireNonNull(profileId, "profileId");
            Objects.requireNonNull(adapterId, "adapterId");
        }

        @Override
        public Run openRun(RunDescriptor descriptor) {
            var sequence = new Sequence(descriptor);
            return new Run() {
                @Override
                public PolynomialTheoryUtilityCandidateResult execute(
                    PolynomialTheoryUtilityExecutionInput input,
                    PolynomialTheoryUtilityCaseCorpus.FormationCase
                        formationCase
                ) {
                    sequence.accept(input, formationCase);
                    return PolynomialTheoryUtilityCandidateResult.noTransition(
                        input,
                        formationCase,
                        "ZERO_OBSERVATION_TEST_ADAPTER"
                    );
                }

                @Override
                public void close() {
                    sequence.close();
                }
            };
        }
    }

    private static final class MeasuredOnDemandAdapter
            implements PolynomialTheoryUtilityProfileAdapter {
        @Override
        public String profileId() {
            return ON_DEMAND;
        }

        @Override
        public String adapterId() {
            return profile().adapterId();
        }

        @Override
        public Run openRun(RunDescriptor descriptor) {
            var sequence = new Sequence(descriptor);
            return new MeasuredRun() {
                @Override
                public PolynomialTheoryUtilityMeasuredCandidate
                        executeMeasured(
                            PolynomialTheoryUtilityExecutionInput input,
                            PolynomialTheoryUtilityCaseCorpus.FormationCase
                                formationCase
                        ) {
                    sequence.accept(input, formationCase);
                    return TRANSITION_CASE.equals(input.caseId())
                        ? measuredTransition(input, formationCase)
                        : PolynomialTheoryUtilityMeasuredCandidate
                            .withoutObservations(
                                PolynomialTheoryUtilityCandidateResult
                                    .noTransition(
                                        input,
                                        formationCase,
                                        "NO_TEST_TRANSITION"
                                    )
                            );
                }

                @Override
                public void close() {
                    sequence.close();
                }
            };
        }
    }

    private static final class LegacyOnDemandAdapter
            implements PolynomialTheoryUtilityProfileAdapter {
        @Override
        public String profileId() {
            return ON_DEMAND;
        }

        @Override
        public String adapterId() {
            return profile().adapterId();
        }

        @Override
        public Run openRun(RunDescriptor descriptor) {
            var sequence = new Sequence(descriptor);
            return new Run() {
                @Override
                public PolynomialTheoryUtilityCandidateResult execute(
                    PolynomialTheoryUtilityExecutionInput input,
                    PolynomialTheoryUtilityCaseCorpus.FormationCase
                        formationCase
                ) {
                    sequence.accept(input, formationCase);
                    return TRANSITION_CASE.equals(input.caseId())
                        ? measuredTransition(input, formationCase).result()
                        : PolynomialTheoryUtilityCandidateResult.noTransition(
                            input,
                            formationCase,
                            "NO_TEST_TRANSITION"
                        );
                }

                @Override
                public void close() {
                    sequence.close();
                }
            };
        }
    }

    private static PolynomialTheoryUtilityMeasuredCandidate measuredTransition(
        PolynomialTheoryUtilityExecutionInput input,
        PolynomialTheoryUtilityCaseCorpus.FormationCase formationCase
    ) {
        var profile = profile();
        var work = transitionWork();
        var transition = PolynomialTheoryUtilityTransitionOutcome.create(
            0,
            input.inputId(),
            List.of(),
            formationCase.sourceExpression(),
            "(x-1)*(x+1)",
            formationCase.sourceExpression(),
            "(x-1)*(x+1)",
            profile.transformationId(),
            profile.engineId(),
            hash("source:" + input.inputId()),
            hash("transition:" + input.inputId()),
            CacheDisposition.CACHE_DISABLED,
            "NONE",
            "NONE",
            "NONE",
            work
        );
        var result = PolynomialTheoryUtilityCandidateResult.create(
            input,
            formationCase,
            TerminalStatus.VALIDATED_TRANSITION,
            "MEASURED_TEST_TRANSITION",
            work,
            List.of(transition),
            "VERIFIED"
        );
        var trace = PolynomialTheoryUtilityTransitionTrace.create(
            transition,
            1,
            List.of(PrimitiveStep.create(
                transition,
                0,
                0,
                "verified-factorization-transition",
                hash("primitive:" + input.inputId())
            )),
            List.of()
        );
        String candidate = hash("candidate:" + input.inputId());
        var attempt = PolynomialTheoryUtilityFactorizationAttempt.create(
            0,
            input.inputId(),
            profile.engineId(),
            hash("request:" + input.inputId()),
            hash("request-evidence:" + input.inputId()),
            List.of(candidate),
            candidate,
            transition.transitionId(),
            "VERIFIED",
            hash("report:" + input.inputId())
        );
        return PolynomialTheoryUtilityMeasuredCandidate.create(
            result,
            List.of(trace),
            List.of(attempt),
            List.of()
        );
    }

    private static PolynomialTheoryUtilityExecutionProfile profile() {
        return PolynomialTheoryUtilityExecutionInputs.profile(ON_DEMAND);
    }

    private static PolynomialTheoryUtilityWorkBreakdown transitionWork() {
        return new PolynomialTheoryUtilityWorkBreakdown(
            1L,
            1L,
            1L,
            1L,
            1L,
            1L,
            1L,
            1L,
            1L,
            0L,
            0L,
            0L,
            0L,
            1L
        );
    }

    private static String hash(String value) {
        return PolynomialTheoryUtilityExecutionIdentity.sha256(
            value.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static final class Sequence {
        private final RunDescriptor descriptor;
        private final List<PolynomialTheoryUtilityExecutionInput> expected;
        private int next;
        private boolean closed;

        private Sequence(RunDescriptor descriptor) {
            this.descriptor = Objects.requireNonNull(
                descriptor,
                "descriptor"
            );
            expected = PolynomialTheoryUtilityExecutionInputs.freeze().inputs()
                .stream()
                .filter(value -> descriptor.runId().equals(value.runId()))
                .toList();
            if (expected.size() != descriptor.expectedCaseCount()) {
                throw new IllegalArgumentException(
                    "test run differs from the frozen input block"
                );
            }
        }

        private void accept(
            PolynomialTheoryUtilityExecutionInput input,
            PolynomialTheoryUtilityCaseCorpus.FormationCase formationCase
        ) {
            if (closed || next >= expected.size()) {
                throw new IllegalStateException(
                    "test run cannot accept another input"
                );
            }
            var frozen = expected.get(next);
            if (!frozen.equals(input)
                    || !input.profileId().equals(descriptor.profileId())
                    || !input.adapterId().equals(descriptor.adapterId())
                    || !input.caseId().equals(formationCase.caseId())) {
                throw new IllegalArgumentException(
                    "test run input differs from its frozen position"
                );
            }
            next++;
        }

        private void close() {
            if (closed || next != expected.size()) {
                throw new IllegalStateException(
                    "test run closed outside its complete input boundary"
                );
            }
            closed = true;
        }
    }
}
