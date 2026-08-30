package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilityTargetBlindRunnerTest {
    @Test
    void executesThirtyRunsAndTheRealBaseline() {
        Tracker tracker = new Tracker();
        var inputs = PolynomialTheoryUtilityExecutionInputs.freeze();
        var artifact = new PolynomialTheoryUtilityTargetBlindRunner().execute(
            inputs,
            registry(tracker, false)
        );

        assertEquals(600, artifact.rows().size());
        assertEquals(30, tracker.openedRuns);
        assertEquals(30, tracker.closedRuns);
        assertEquals(600, tracker.executions);
        assertTrue(tracker.runSizes.values().stream().allMatch(size ->
            size == 20));
        assertEquals(
            inputs.inputs().stream()
                .map(PolynomialTheoryUtilityExecutionInput::inputId)
                .toList(),
            artifact.rows().stream()
                .map(PolynomialTheoryUtilityCandidateFreeze.Row::inputId)
                .toList()
        );
        assertEquals(
            120L,
            artifact.rows().stream()
                .filter(row -> "NO_FACTORIZATION".equals(row.profileId()))
                .filter(row -> row.outcome().terminalStatus()
                    == PolynomialTheoryUtilityProfileAdapter.TerminalStatus
                        .NO_TRANSITION)
                .count()
        );
    }

    @Test
    void freezesTargetBlindEscapedContentAddressedRows() {
        var inputs = PolynomialTheoryUtilityExecutionInputs.freeze();
        List<PolynomialTheoryUtilityCandidateFreeze.Row> rows =
            new ArrayList<>(inputs.inputs().size());
        for (int index = 0; index < inputs.inputs().size(); index++) {
            String detail = index == 0
                ? "DETAIL_\"_\\_\n_\t"
                : "NO_TRANSITION";
            rows.add(PolynomialTheoryUtilityCandidateFreeze.row(
                inputs.inputs().get(index),
                PolynomialTheoryUtilityProfileAdapter.Outcome.noTransition(
                    detail
                )
            ));
        }

        var artifact = PolynomialTheoryUtilityCandidateFreeze.create(
            inputs,
            rows
        );

        assertEquals(600, artifact.rows().size());
        assertEquals(
            600L,
            artifact.rows().stream()
                .map(PolynomialTheoryUtilityCandidateFreeze.Row::candidateId)
                .distinct()
                .count()
        );
        assertTrue(artifact.canonicalJson().contains(
            "DETAIL_\\\"_\\\\_\\n_\\t"
        ));
        assertEquals(
            artifact.byteLength(),
            artifact.canonicalJson().getBytes(StandardCharsets.UTF_8).length
        );
        for (String forbidden : List.of(
                "\"requiredOutcome\":",
                "\"referenceExpression\":",
                "\"expectedClassifierOutcome\":",
                "\"selectedDecision\":")) {
            assertFalse(artifact.canonicalJson().contains(forbidden));
        }

        String changed = artifact.canonicalJson() + " ";
        assertThrows(
            IllegalArgumentException.class,
            () -> new PolynomialTheoryUtilityCandidateFreeze.Artifact(
                artifact.inputContentHash(),
                artifact.inputByteLength(),
                artifact.rows(),
                changed,
                PolynomialTheoryUtilityExecutionIdentity.sha256(
                    changed.getBytes(StandardCharsets.UTF_8)
                ),
                changed.getBytes(StandardCharsets.UTF_8).length
            )
        );
    }

    @Test
    void retainsRuntimeFailureButRejectsContractAndBudgetViolations() {
        Tracker tracker = new Tracker();
        var artifact = new PolynomialTheoryUtilityTargetBlindRunner().execute(
            PolynomialTheoryUtilityExecutionInputs.freeze(),
            registry(tracker, true)
        );
        assertEquals(
            1L,
            artifact.rows().stream()
                .filter(row -> row.outcome().terminalStatus()
                    == PolynomialTheoryUtilityProfileAdapter.TerminalStatus
                        .TECHNICAL_FAILURE)
                .count()
        );
        assertEquals(600, tracker.executions);
        assertEquals(30, tracker.closedRuns);

        List<PolynomialTheoryUtilityProfileAdapter> contractAdapters =
            adapters(new Tracker(), false);
        var profile = PolynomialTheoryUtilityExecutionPlan.PROFILES.get(1);
        contractAdapters.set(1, new StubAdapter(profile, true));
        assertThrows(
            IllegalArgumentException.class,
            () -> new PolynomialTheoryUtilityTargetBlindRunner().execute(
                PolynomialTheoryUtilityExecutionInputs.freeze(),
                new PolynomialTheoryUtilityAdapterRegistry(contractAdapters)
            )
        );

        var input = PolynomialTheoryUtilityExecutionInputs.freeze()
            .inputs().get(0);
        var excessive = outcome(
            PolynomialTheoryUtilityProfileAdapter.TerminalStatus.NO_TRANSITION,
            "EXCESSIVE_WORK",
            input.admittedPrimitiveWork() + 1L,
            0L,
            0L,
            0,
            0,
            0
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityCandidateFreeze.row(input, excessive)
        );
    }

    @Test
    void acceptsSeveralTransitionsAndCacheEventsWithinOneCase() {
        var input = PolynomialTheoryUtilityExecutionInputs.freeze().inputs()
            .stream()
            .filter(value -> "VERIFIED_DERIVED_MACRO_CACHE".equals(
                value.profileId()
            ))
            .filter(value -> "CP06_FULL".equals(value.checkpointId()))
            .filter(value -> value.admittedPrimitiveWork() >= 4)
            .filter(value -> value.totalMechanicalWork() >= 6)
            .findFirst()
            .orElseThrow();
        var multiple = outcome(
            PolynomialTheoryUtilityProfileAdapter.TerminalStatus.TRANSITION,
            "MULTIPLE_OCCURRENCES_TRANSFORMED",
            4L,
            1L,
            5L,
            4,
            3,
            1
        );

        var row = PolynomialTheoryUtilityCandidateFreeze.row(input, multiple);

        assertSame(multiple, row.outcome());
        assertTrue(row.outcome().generatedTransitions() > 1);
        assertTrue(row.outcome().cacheHits() > 1);
        assertTrue(row.outcome().cacheMisses() > 0);
        long expectedMechanical = row.outcome().sourceValidationWork()
            + row.outcome().factorizationWork()
            + row.outcome().renderReparseWork()
            + row.outcome().cacheLookupWork()
            + row.outcome().cacheReplayWork()
            + row.outcome().otherMechanicalWork();
        assertEquals(expectedMechanical, row.outcome().totalMechanicalWork());
    }

    @Test
    void registryRequiresEveryFrozenProfileAdapterPair() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new PolynomialTheoryUtilityAdapterRegistry(
                List.of(new PolynomialTheoryUtilityNoFactorizationAdapter())
            )
        );

        List<PolynomialTheoryUtilityProfileAdapter> duplicate =
            adapters(new Tracker(), false);
        duplicate.set(1, duplicate.get(0));
        assertThrows(
            IllegalArgumentException.class,
            () -> new PolynomialTheoryUtilityAdapterRegistry(duplicate)
        );

        List<PolynomialTheoryUtilityProfileAdapter> mismatched =
            adapters(new Tracker(), false);
        var onDemand = PolynomialTheoryUtilityExecutionPlan.PROFILES.get(1);
        mismatched.set(1, new MismatchedAdapter(onDemand.adapterId()));
        assertThrows(
            IllegalArgumentException.class,
            () -> new PolynomialTheoryUtilityAdapterRegistry(mismatched)
        );
    }

    private static PolynomialTheoryUtilityProfileAdapter.Outcome outcome(
        PolynomialTheoryUtilityProfileAdapter.TerminalStatus status,
        String detail,
        long primitiveWork,
        long factorizationWork,
        long otherMechanicalWork,
        int transitions,
        int cacheHits,
        int cacheMisses
    ) {
        boolean transition = status
            == PolynomialTheoryUtilityProfileAdapter.TerminalStatus.TRANSITION;
        return new PolynomialTheoryUtilityProfileAdapter.Outcome(
            status,
            detail,
            transition ? "f((x-1)*(x+1))" : "",
            transition
                ? PolynomialTheoryUtilityExecutionPlan.TRANSFORMATION_ID
                : "",
            transition ? "VERIFIED" : "NOT_REQUESTED",
            primitiveWork,
            0L,
            factorizationWork,
            0L,
            0L,
            0L,
            otherMechanicalWork,
            factorizationWork > 0 ? 1 : 0,
            factorizationWork > 0 ? 1 : 0,
            transitions,
            0,
            0,
            0,
            0,
            cacheHits,
            cacheMisses,
            0,
            0,
            List.of(),
            List.of()
        );
    }

    private static PolynomialTheoryUtilityAdapterRegistry registry(
        Tracker tracker,
        boolean failOnce
    ) {
        return new PolynomialTheoryUtilityAdapterRegistry(
            adapters(tracker, failOnce)
        );
    }

    private static List<PolynomialTheoryUtilityProfileAdapter> adapters(
        Tracker tracker,
        boolean failOnce
    ) {
        List<PolynomialTheoryUtilityProfileAdapter> result =
            new ArrayList<>();
        result.add(new TrackingAdapter(
            new PolynomialTheoryUtilityNoFactorizationAdapter(),
            tracker,
            false
        ));
        for (int index = 1;
                index < PolynomialTheoryUtilityExecutionPlan.PROFILES.size();
                index++) {
            var profile =
                PolynomialTheoryUtilityExecutionPlan.PROFILES.get(index);
            result.add(new TrackingAdapter(
                new StubAdapter(profile, false),
                tracker,
                failOnce && index == 1
            ));
        }
        return result;
    }

    private record StubAdapter(
        PolynomialTheoryUtilityExecutionProfile profile,
        boolean contractFailure
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
            return (input, formationCase) -> {
                if (contractFailure) {
                    throw new IllegalArgumentException("contract failure");
                }
                return Outcome.noTransition("TEST_STUB_NO_TRANSITION");
            };
        }
    }

    private record TrackingAdapter(
        PolynomialTheoryUtilityProfileAdapter delegate,
        Tracker tracker,
        boolean failOnce
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
                public Outcome execute(
                    PolynomialTheoryUtilityExecutionInput input,
                    PolynomialTheoryUtilityCaseCorpus.FormationCase studyCase
                ) {
                    tracker.executions++;
                    tracker.runSizes.merge(
                        descriptor.runId(),
                        1,
                        Integer::sum
                    );
                    if (failOnce && !tracker.failureIssued) {
                        tracker.failureIssued = true;
                        throw new IllegalStateException("runtime failure");
                    }
                    return run.execute(input, studyCase);
                }

                @Override
                public void close() {
                    if (!closed) {
                        closed = true;
                        tracker.closedRuns++;
                        run.close();
                    }
                }
            };
        }
    }

    private record MismatchedAdapter(String adapterId)
            implements PolynomialTheoryUtilityProfileAdapter {
        @Override
        public String profileId() {
            return "NO_FACTORIZATION";
        }

        @Override
        public Run openRun(RunDescriptor descriptor) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class Tracker {
        private int openedRuns;
        private int closedRuns;
        private int executions;
        private boolean failureIssued;
        private final Map<String, Integer> runSizes = new LinkedHashMap<>();
    }
}
