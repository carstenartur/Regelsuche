package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityProfileAdapter.AdapterRegistry;
import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityProfileAdapter.NoFactorizationAdapter;
import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityProfileAdapter.TargetBlindRunner;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilityNoFactorizationAdapterTest {
    @Test
    void executesAllSixFrozenRunsWithZeroWork() {
        var inputs = baselineInputs();
        var formation = PolynomialTheoryUtilityCaseCorpus.load().cases();
        var adapter = new NoFactorizationAdapter();
        List<PolynomialTheoryUtilityCandidateResult> results =
            new ArrayList<>(inputs.size());

        for (var checkpoint : PolynomialTheoryUtilityExecutionPlan.CHECKPOINTS) {
            var runInputs = inputs.stream()
                .filter(value -> checkpoint.checkpointId().equals(
                    value.checkpointId()
                ))
                .toList();
            var first = runInputs.getFirst();
            var run = adapter.openRun(descriptor(first));
            for (int index = 0; index < runInputs.size(); index++) {
                results.add(run.execute(runInputs.get(index), formation.get(index)));
            }
            run.close();
        }

        assertEquals(120, results.size());
        assertEquals(
            120L,
            results.stream()
                .map(PolynomialTheoryUtilityCandidateResult::resultId)
                .distinct()
                .count()
        );
        for (int index = 0; index < results.size(); index++) {
            var result = results.get(index);
            var input = inputs.get(index);
            var formationCase = formation.get(index % formation.size());
            result.validateAgainst(input, formationCase);
            assertEquals(
                PolynomialTheoryUtilityCandidateResult.TerminalStatus
                    .NO_TRANSITION,
                result.terminalStatus()
            );
            assertEquals(NoFactorizationAdapter.DETAIL_CODE, result.detailCode());
            assertEquals(0L, result.work().primitiveWork());
            assertEquals(0L, result.work().mechanicalWork());
            assertEquals(0L, result.work().factorizationWork());
            assertEquals(0, result.generatedTransitions());
            assertEquals("NOT_REQUESTED", result.verifierOutcome());
            assertEquals("NONE", result.transitionEvidenceHash());
            assertEquals(formationCase.sourceExpression(), result.sourceRootExpression());
        }
    }

    @Test
    void rejectsReorderedIncompleteAndClosedRuns() {
        var runInputs = baselineInputs().stream()
            .filter(value -> "CP01_1_OF_12".equals(value.checkpointId()))
            .toList();
        var formation = PolynomialTheoryUtilityCaseCorpus.load().cases();
        var adapter = new NoFactorizationAdapter();

        var reordered = adapter.openRun(descriptor(runInputs.getFirst()));
        assertThrows(
            IllegalArgumentException.class,
            () -> reordered.execute(runInputs.get(1), formation.get(1))
        );

        var incomplete = adapter.openRun(descriptor(runInputs.getFirst()));
        incomplete.execute(runInputs.getFirst(), formation.getFirst());
        assertThrows(IllegalStateException.class, incomplete::close);

        var complete = adapter.openRun(descriptor(runInputs.getFirst()));
        for (int index = 0; index < runInputs.size(); index++) {
            complete.execute(runInputs.get(index), formation.get(index));
        }
        complete.close();
        assertThrows(
            IllegalStateException.class,
            () -> complete.execute(runInputs.getFirst(), formation.getFirst())
        );
        assertThrows(IllegalStateException.class, complete::close);
    }

    @Test
    void rejectsCounterfeitInputEnvelopeWithMatchingVisibleIds() {
        var runInputs = baselineInputs().stream()
            .filter(value -> "CP01_1_OF_12".equals(value.checkpointId()))
            .toList();
        var formation = PolynomialTheoryUtilityCaseCorpus.load().cases();
        var input = runInputs.getFirst();
        var counterfeit = new PolynomialTheoryUtilityExecutionInput(
            input.inputId(),
            "sha256:" + "f".repeat(64),
            input.runId(),
            input.caseId(),
            input.profileId(),
            input.checkpointId(),
            input.adapterId(),
            input.admittedPrimitiveWork(),
            input.totalMechanicalWork(),
            input.factorizationWork(),
            input.inputStatus()
        );
        assertNotEquals(input, counterfeit);

        var run = new NoFactorizationAdapter().openRun(descriptor(input));
        assertThrows(
            IllegalArgumentException.class,
            () -> run.execute(counterfeit, formation.getFirst())
        );
        for (int index = 0; index < runInputs.size(); index++) {
            run.execute(runInputs.get(index), formation.get(index));
        }
        run.close();
    }

    @Test
    void resultContractRejectsBudgetEvidenceAndRebinding() {
        var inputs = baselineInputs();
        var formation = PolynomialTheoryUtilityCaseCorpus.load().cases();
        var input = inputs.getFirst();
        var formationCase = formation.getFirst();
        var valid = PolynomialTheoryUtilityCandidateResult.noTransition(
            input,
            formationCase,
            "BASELINE"
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityCandidateResult.create(
                input,
                formationCase,
                PolynomialTheoryUtilityCandidateResult.TerminalStatus
                    .NO_TRANSITION,
                "EXCESSIVE_WORK",
                work(input.admittedPrimitiveWork() + 1L),
                List.of(),
                "NOT_REQUESTED"
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityCandidateResult.create(
                input,
                formationCase,
                PolynomialTheoryUtilityCandidateResult.TerminalStatus
                    .NO_TRANSITION,
                "INVALID_VERIFIER",
                PolynomialTheoryUtilityWorkBreakdown.zero(),
                List.of(),
                "VERIFIED"
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> valid.validateAgainst(inputs.get(1), formation.get(1))
        );
        assertNotEquals(input.inputId(), inputs.get(1).inputId());
    }

    @Test
    void rejectsAnotherProfileAndInventedRunIdentity() {
        var allInputs = PolynomialTheoryUtilityExecutionInputs.freeze().inputs();
        var foreign = allInputs.stream()
            .filter(value -> !"NO_FACTORIZATION".equals(value.profileId()))
            .findFirst()
            .orElseThrow();
        var adapter = new NoFactorizationAdapter();
        assertThrows(
            IllegalArgumentException.class,
            () -> adapter.openRun(descriptor(foreign))
        );

        var baseline = baselineInputs().getFirst();
        var invented = new PolynomialTheoryUtilityProfileAdapter.RunDescriptor(
            "sha256:" + "0".repeat(64),
            baseline.profileId(),
            baseline.checkpointId(),
            baseline.adapterId(),
            PolynomialTheoryUtilityCaseCorpus.ORDERED_CASE_IDS.size()
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> adapter.openRun(invented)
        );
    }

    @Test
    void targetBlindRunnerExecutesThirtyBoundRuns() {
        var tracker = new Tracker();
        var inputs = PolynomialTheoryUtilityExecutionInputs.freeze();
        var formation = PolynomialTheoryUtilityCaseCorpus.load().cases();
        var batch = new TargetBlindRunner().execute(
            inputs,
            registry(tracker, Mode.NORMAL, null)
        );

        assertEquals(
            "regelsuche.polynomial-theory-utility-candidate-batch/v2",
            batch.schema()
        );
        assertEquals(
            "TARGET_BLIND_RESULTS_COLLECTED_NOT_FROZEN",
            batch.evidenceStatus()
        );
        assertEquals(inputs.contentHash(), batch.inputContentHash());
        assertEquals(inputs.byteLength(), batch.inputByteLength());
        assertEquals(600, batch.results().size());
        assertEquals(30, tracker.openedRuns);
        assertEquals(30, tracker.closedRuns);
        assertEquals(600, tracker.executions);
        assertTrue(tracker.runSizes.values().stream().allMatch(size ->
            size == 20));
        assertEquals(
            inputs.inputs(),
            batch.results().stream()
                .map(PolynomialTheoryUtilityCandidateResult::input)
                .toList()
        );
        assertTrue(batch.results().stream().allMatch(result ->
            PolynomialTheoryUtilityCandidateResult.SCHEMA.equals(
                result.schema()
            )));
        for (int index = 0; index < batch.results().size(); index++) {
            assertEquals(
                formation.get(index % formation.size()).sourceExpression(),
                batch.results().get(index).sourceRootExpression()
            );
        }
        assertEquals(
            120L,
            batch.results().stream()
                .filter(result -> result.terminalStatus()
                    == PolynomialTheoryUtilityCandidateResult.TerminalStatus
                        .NO_TRANSITION)
                .count()
        );
        assertEquals(
            480L,
            batch.results().stream()
                .filter(result -> result.terminalStatus()
                    == PolynomialTheoryUtilityCandidateResult.TerminalStatus
                        .UNSUPPORTED)
                .count()
        );
        for (var profile : PolynomialTheoryUtilityExecutionPlan.PROFILES) {
            assertEquals(
                120L,
                batch.results().stream()
                    .filter(result -> profile.profileId().equals(
                        result.input().profileId()
                    ))
                    .count()
            );
        }
        assertThrows(
            UnsupportedOperationException.class,
            () -> batch.results().clear()
        );
    }

    @Test
    void targetBlindRegistryRejectsInvalidInventoryAndUsesFrozenOrder() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new AdapterRegistry(List.of(new NoFactorizationAdapter()))
        );

        var duplicate = adapters(new Tracker(), Mode.NORMAL, null);
        duplicate.set(1, duplicate.getFirst());
        assertThrows(
            IllegalArgumentException.class,
            () -> new AdapterRegistry(duplicate)
        );

        var mismatched = adapters(new Tracker(), Mode.NORMAL, null);
        var onDemand = PolynomialTheoryUtilityExecutionPlan.PROFILES.get(1);
        mismatched.set(1, new PolynomialTheoryUtilityProfileAdapter() {
            @Override
            public String profileId() {
                return NoFactorizationAdapter.PROFILE_ID;
            }

            @Override
            public String adapterId() {
                return onDemand.adapterId();
            }

            @Override
            public Run openRun(RunDescriptor descriptor) {
                throw new AssertionError();
            }
        });
        assertThrows(
            IllegalArgumentException.class,
            () -> new AdapterRegistry(mismatched)
        );

        var reversed = adapters(new Tracker(), Mode.NORMAL, null);
        Collections.reverse(reversed);
        var registry = new AdapterRegistry(reversed);
        assertEquals(
            PolynomialTheoryUtilityExecutionPlan.PROFILES.stream()
                .map(PolynomialTheoryUtilityExecutionProfile::profileId)
                .toList(),
            registry.profileIds()
        );
    }

    @Test
    void targetBlindExecutionFailureClosesRunAndSuppressesCloseFailure() {
        var tracker = new Tracker();
        var exception = assertThrows(
            IllegalStateException.class,
            () -> new TargetBlindRunner().execute(
                PolynomialTheoryUtilityExecutionInputs.freeze(),
                registry(tracker, Mode.THROW_AND_CLOSE, null)
            )
        );

        assertEquals("deliberate execution failure", exception.getMessage());
        assertEquals(1, exception.getSuppressed().length);
        assertEquals(
            "deliberate close failure",
            exception.getSuppressed()[0].getMessage()
        );
        assertEquals(7, tracker.openedRuns);
        assertEquals(7, tracker.closedRuns);
        assertEquals(121, tracker.executions);
    }

    @Test
    void targetBlindRunnerRejectsNullAndReboundResults() {
        var nullTracker = new Tracker();
        assertThrows(
            NullPointerException.class,
            () -> new TargetBlindRunner().execute(
                PolynomialTheoryUtilityExecutionInputs.freeze(),
                registry(nullTracker, Mode.NULL_RESULT, null)
            )
        );
        assertEquals(7, nullTracker.openedRuns);
        assertEquals(7, nullTracker.closedRuns);
        assertEquals(121, nullTracker.executions);

        var inputs = PolynomialTheoryUtilityExecutionInputs.freeze();
        var foreign = inputs.inputs().getFirst();
        var reboundTracker = new Tracker();
        assertThrows(
            IllegalArgumentException.class,
            () -> new TargetBlindRunner().execute(
                inputs,
                registry(reboundTracker, Mode.REBOUND_RESULT, foreign)
            )
        );
        assertEquals(7, reboundTracker.openedRuns);
        assertEquals(7, reboundTracker.closedRuns);
        assertEquals(121, reboundTracker.executions);
    }

    private static AdapterRegistry registry(
        Tracker tracker,
        Mode mode,
        PolynomialTheoryUtilityExecutionInput foreign
    ) {
        return new AdapterRegistry(adapters(tracker, mode, foreign));
    }

    private static List<PolynomialTheoryUtilityProfileAdapter> adapters(
        Tracker tracker,
        Mode mode,
        PolynomialTheoryUtilityExecutionInput foreign
    ) {
        List<PolynomialTheoryUtilityProfileAdapter> values = new ArrayList<>();
        values.add(new TrackingAdapter(new NoFactorizationAdapter(), tracker));
        for (int index = 1;
                index < PolynomialTheoryUtilityExecutionPlan.PROFILES.size();
                index++) {
            var profile =
                PolynomialTheoryUtilityExecutionPlan.PROFILES.get(index);
            values.add(new TrackingAdapter(
                new StubAdapter(
                    profile,
                    index == 1 ? mode : Mode.NORMAL,
                    foreign
                ),
                tracker
            ));
        }
        return values;
    }

    private static List<PolynomialTheoryUtilityExecutionInput> baselineInputs() {
        var values = PolynomialTheoryUtilityExecutionInputs.freeze().inputs()
            .stream()
            .filter(value -> "NO_FACTORIZATION".equals(value.profileId()))
            .toList();
        assertEquals(120, values.size());
        assertTrue(values.stream().allMatch(value ->
            NoFactorizationAdapter.ADAPTER_ID.equals(value.adapterId())));
        return values;
    }

    private static PolynomialTheoryUtilityProfileAdapter.RunDescriptor descriptor(
        PolynomialTheoryUtilityExecutionInput input
    ) {
        return new PolynomialTheoryUtilityProfileAdapter.RunDescriptor(
            input.runId(),
            input.profileId(),
            input.checkpointId(),
            input.adapterId(),
            PolynomialTheoryUtilityCaseCorpus.ORDERED_CASE_IDS.size()
        );
    }

    private static PolynomialTheoryUtilityWorkBreakdown work(long primitive) {
        return new PolynomialTheoryUtilityWorkBreakdown(
            primitive,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L,
            0L
        );
    }

    private record StubAdapter(
        PolynomialTheoryUtilityExecutionProfile profile,
        Mode mode,
        PolynomialTheoryUtilityExecutionInput foreign
    ) implements PolynomialTheoryUtilityProfileAdapter {
        @Override
        public String profileId() {
            return profile.profileId();
        }

        @Override
        public String adapterId() {
            return profile.adapterId();
        }

        @Override
        public Run openRun(RunDescriptor descriptor) {
            var checkpoint =
                PolynomialTheoryUtilityExecutionPlan.CHECKPOINTS.stream()
                    .filter(value -> value.checkpointId().equals(
                        descriptor.checkpointId()
                    ))
                    .findFirst()
                    .orElseThrow();
            String expectedRunId =
                PolynomialTheoryUtilityExecutionIdentity.runId(
                    profile,
                    checkpoint
                );
            if (!profileId().equals(descriptor.profileId())
                    || !adapterId().equals(descriptor.adapterId())
                    || !expectedRunId.equals(descriptor.runId())
                    || descriptor.expectedCaseCount()
                        != PolynomialTheoryUtilityCaseCorpus
                            .ORDERED_CASE_IDS.size()) {
                throw new IllegalArgumentException("stub run mismatch");
            }
            return new Run() {
                private int nextCase;

                @Override
                public PolynomialTheoryUtilityCandidateResult execute(
                    PolynomialTheoryUtilityExecutionInput input,
                    PolynomialTheoryUtilityCaseCorpus.FormationCase studyCase
                ) {
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
                            || !input.caseId().equals(studyCase.caseId())) {
                        throw new IllegalArgumentException("stub case mismatch");
                    }
                    nextCase++;
                    return switch (mode) {
                        case THROW_AND_CLOSE -> throw new IllegalStateException(
                            "deliberate execution failure"
                        );
                        case NULL_RESULT -> null;
                        case REBOUND_RESULT -> unsupported(foreign, studyCase);
                        case NORMAL -> unsupported(input, studyCase);
                    };
                }

                @Override
                public void close() {
                    if (mode == Mode.THROW_AND_CLOSE) {
                        throw new IllegalStateException(
                            "deliberate close failure"
                        );
                    }
                    if (mode == Mode.NORMAL
                            && nextCase != descriptor.expectedCaseCount()) {
                        throw new IllegalStateException(
                            "stub run closed before every case"
                        );
                    }
                }
            };
        }

        private static PolynomialTheoryUtilityCandidateResult unsupported(
            PolynomialTheoryUtilityExecutionInput input,
            PolynomialTheoryUtilityCaseCorpus.FormationCase formationCase
        ) {
            return PolynomialTheoryUtilityCandidateResult.create(
                input,
                formationCase,
                PolynomialTheoryUtilityCandidateResult.TerminalStatus
                    .UNSUPPORTED,
                "TEST_STUB_UNSUPPORTED",
                PolynomialTheoryUtilityWorkBreakdown.zero(),
                List.of(),
                "NOT_REQUESTED"
            );
        }
    }

    private record TrackingAdapter(
        PolynomialTheoryUtilityProfileAdapter delegate,
        Tracker tracker
    ) implements PolynomialTheoryUtilityProfileAdapter {
        @Override
        public String profileId() {
            return delegate.profileId();
        }

        @Override
        public String adapterId() {
            return delegate.adapterId();
        }

        @Override
        public Run openRun(RunDescriptor descriptor) {
            Run run = delegate.openRun(descriptor);
            tracker.openedRuns++;
            return new Run() {
                private boolean closed;

                @Override
                public PolynomialTheoryUtilityCandidateResult execute(
                    PolynomialTheoryUtilityExecutionInput input,
                    PolynomialTheoryUtilityCaseCorpus.FormationCase studyCase
                ) {
                    tracker.executions++;
                    tracker.runSizes.merge(
                        descriptor.runId(),
                        1,
                        Integer::sum
                    );
                    return run.execute(input, studyCase);
                }

                @Override
                public void close() {
                    if (!closed) {
                        closed = true;
                        try {
                            run.close();
                        } finally {
                            tracker.closedRuns++;
                        }
                    }
                }
            };
        }
    }

    private enum Mode {
        NORMAL,
        THROW_AND_CLOSE,
        NULL_RESULT,
        REBOUND_RESULT
    }

    private static final class Tracker {
        private int openedRuns;
        private int closedRuns;
        private int executions;
        private final Map<String, Integer> runSizes = new LinkedHashMap<>();
    }
}
