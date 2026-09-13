package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityCandidateResult.TerminalStatus;
import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityMeasuredExecution.MeasuredRun;
import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityProfileAdapter.NoFactorizationAdapter;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilityObservedMeasuredExecutionTest {
    private static final String OBSERVED_RESULT =
        "regelsuche.polynomial-theory-utility-candidate-result/v3";
    private static final String OBSERVED_MEASUREMENT =
        "regelsuche.polynomial-theory-utility-candidate-measurements/v2";

    @Test
    void rejectsAHistoricalAdapterAnywhereInTheInventoryBeforeOpeningRuns() {
        var counts = new Counts();
        var adapters = controls(counts, true, ignored -> true);
        var last = PolynomialTheoryUtilityExecutionPlan.PROFILES.getLast();
        adapters.set(adapters.size() - 1, new ControlAdapter(
            last.profileId(), last.adapterId(), PolynomialTheoryUtilityCandidateResult.SCHEMA,
            true, ignored -> true, counts));

        assertThrows(IllegalArgumentException.class, () -> executeObserved(adapters));
        assertEquals(0, counts.opened);
        assertEquals(0, counts.executed);
    }

    @Test
    void requiresAMeasuredRunBeforeExecutingItsFirstRow() {
        var counts = new Counts();
        var adapters = controls(counts, false, ignored -> true);

        assertThrows(IllegalArgumentException.class, () -> executeObserved(adapters));
        assertEquals(1, counts.opened);
        assertEquals(0, counts.executed);
        assertEquals(1, counts.closed);
    }

    @Test
    void rejectsAnObservedDeclarationThatActuallyProducesAHistoricalResult() {
        var counts = new Counts();
        var adapters = controls(counts, true, ignored -> false);

        assertThrows(IllegalArgumentException.class, () -> executeObserved(adapters));
        assertEquals(1, counts.executed);
        assertEquals(1, counts.closed);
    }

    @Test
    void stopsAtTheExactRowThatChangesResultRevision() {
        var counts = new Counts();
        var adapters = controls(counts, true, row -> row == 1);

        assertThrows(IllegalArgumentException.class, () -> executeObserved(adapters));
        assertEquals(2, counts.executed);
        assertEquals(1, counts.closed);
    }

    @Test
    void bindsObservedMeasurementsThroughTheExistingRunnerWithoutAStudyFreeze() {
        // One native witness executes inside its measured run. All remaining
        // non-baseline rows are explicit zero-work component controls, not a study.
        var counts = new Counts();
        var adapters = controls(counts, true, ignored -> true);
        var nativeInput = nativeInput();
        for (int index = 0; index < adapters.size(); index++) {
            var adapter = adapters.get(index);
            if (NoFactorizationAdapter.PROFILE_ID.equals(adapter.profileId())) {
                adapters.set(index, NoFactorizationAdapter.observed());
            } else if (nativeInput.profileId().equals(adapter.profileId())) {
                adapters.set(index, new ControlAdapter(adapter.profileId(), adapter.adapterId(),
                    OBSERVED_RESULT, true, ignored -> true, counts, nativeInput.inputId()));
            }
        }

        var batch = executeObserved(adapters);

        assertEquals(PolynomialTheoryUtilityExecutionInputs.EXPECTED_INPUT_COUNT, batch.rowCount());
        assertEquals("regelsuche.polynomial-theory-utility-candidate-measurement-batch/v2", batch.schema());
        for (int index = 0; index < batch.results().size(); index++) {
            var result = batch.results().get(index);
            var measurement = batch.measurements().get(index);
            assertEquals(OBSERVED_RESULT, result.schema());
            assertEquals(OBSERVED_MEASUREMENT, measurement.schema());
            assertEquals(result.resultId(), measurement.result().resultId());
            if (nativeInput.equals(result.input())) {
                assertTrue(result.work().mechanicalWork() > 0);
                assertEquals(result.work(), PolynomialTheoryUtilityCanonicalWorkProjection
                    .project(result.input(), result.observations().rawWork()).work());
            } else {
                assertEquals(PolynomialTheoryUtilityWorkBreakdown.zero(), result.work());
            }
            measurement.validateAgainst(result);
        }
        assertEquals(counts.opened, counts.closed);
    }

    @Test
    void measuredRunExplicitEntryRejectsHistoricalResults() {
        var input = PolynomialTheoryUtilityExecutionInputs.freeze().inputs().getFirst();
        var formation = PolynomialTheoryUtilityCaseCorpus.load().cases().getFirst();
        var historical = PolynomialTheoryUtilityMeasuredCandidate.withoutObservations(
            PolynomialTheoryUtilityCandidateResult.noTransition(input, formation, "HISTORICAL_CONTROL"));

        try (var run = fixedRun(historical)) {
            assertThrows(IllegalArgumentException.class, () -> executeObserved(run, input, formation));
        }
    }

    @Test
    void measuredRunExplicitEntryRetainsRealNativeWorkAndEvidence() {
        var input = nativeInput();
        var formation = PolynomialTheoryUtilityCaseCorpus.load().cases().stream()
            .filter(value -> value.caseId().equals(input.caseId())).findFirst().orElseThrow();
        var nativeExecution = PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter
            .executeObservedCase(input, formation);
        var measured = nativeExecution.measured();

        try (var run = fixedRun(measured)) {
            assertSame(measured, executeObserved(run, input, formation));
        }
        assertTrue(measured.result().work().mechanicalWork() > 0);
        assertEquals(nativeExecution.rawWork(),
            measured.result().observations().rawWork().totalMechanicalWork());
        assertEquals(OBSERVED_RESULT, measured.result().schema());
    }

    private static PolynomialTheoryUtilityCandidateMeasurementBatch executeObserved(
            List<PolynomialTheoryUtilityProfileAdapter> adapters) {
        return new PolynomialTheoryUtilityMeasuredExecution().executeObserved(
            PolynomialTheoryUtilityExecutionInputs.freeze(), adapters);
    }

    private static PolynomialTheoryUtilityMeasuredCandidate executeObserved(MeasuredRun run,
            PolynomialTheoryUtilityExecutionInput input,
            PolynomialTheoryUtilityCaseCorpus.FormationCase formation) {
        return run.executeObserved(input, formation);
    }

    private static MeasuredRun fixedRun(PolynomialTheoryUtilityMeasuredCandidate measured) {
        return new MeasuredRun() {
            @Override
            public PolynomialTheoryUtilityMeasuredCandidate executeMeasured(
                    PolynomialTheoryUtilityExecutionInput input,
                    PolynomialTheoryUtilityCaseCorpus.FormationCase formation) {
                return measured;
            }

            @Override
            public void close() { }
        };
    }

    private static List<PolynomialTheoryUtilityProfileAdapter> controls(Counts counts,
            boolean measured, IntPredicate observedRows) {
        var adapters = new ArrayList<PolynomialTheoryUtilityProfileAdapter>();
        for (var profile : PolynomialTheoryUtilityExecutionPlan.PROFILES) {
            adapters.add(new ControlAdapter(profile.profileId(), profile.adapterId(),
                OBSERVED_RESULT, measured, observedRows, counts));
        }
        return adapters;
    }

    private static PolynomialTheoryUtilityExecutionInput nativeInput() {
        return PolynomialTheoryUtilityExecutionInputs.freeze().inputs().stream()
            .filter(value -> value.profileId().equals("ON_DEMAND_VERIFIED_FACTORIZATION")
                && value.checkpointId().equals("CP06_FULL")
                && value.caseId().equals("z02-difference-of-squares"))
            .findFirst().orElseThrow();
    }

    private record ControlAdapter(String profileId, String adapterId, String resultSchema,
            boolean measured, IntPredicate observedRows, Counts counts, String nativeWitnessInputId)
            implements PolynomialTheoryUtilityProfileAdapter {
        private ControlAdapter(String profileId, String adapterId, String resultSchema,
                boolean measured, IntPredicate observedRows, Counts counts) {
            this(profileId, adapterId, resultSchema, measured, observedRows, counts, "NONE");
        }

        @Override
        public Run openRun(RunDescriptor descriptor) {
            counts.opened++;
            if (measured) {
                return new MeasuredRun() {
                    @Override
                    public PolynomialTheoryUtilityMeasuredCandidate executeMeasured(
                            PolynomialTheoryUtilityExecutionInput input,
                            PolynomialTheoryUtilityCaseCorpus.FormationCase formation) {
                        if (nativeWitnessInputId.equals(input.inputId())) {
                            counts.executed++;
                            return PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter
                                .executeObservedCase(input, formation).measured();
                        }
                        return PolynomialTheoryUtilityMeasuredCandidate.withoutObservations(
                            result(input, formation));
                    }

                    @Override
                    public void close() { counts.closed++; }
                };
            }
            return new Run() {
                @Override
                public PolynomialTheoryUtilityCandidateResult execute(
                        PolynomialTheoryUtilityExecutionInput input,
                        PolynomialTheoryUtilityCaseCorpus.FormationCase formation) {
                    return result(input, formation);
                }

                @Override
                public void close() { counts.closed++; }
            };
        }

        private PolynomialTheoryUtilityCandidateResult result(PolynomialTheoryUtilityExecutionInput input,
                PolynomialTheoryUtilityCaseCorpus.FormationCase formation) {
            counts.executed++;
            if (!observedRows.test(counts.executed)) {
                return PolynomialTheoryUtilityCandidateResult.noTransition(input, formation,
                    "ZERO_WORK_HISTORICAL_COMPONENT_CONTROL");
            }
            var occurrences = new ArrayList<PolynomialTheoryUtilityExecutionObservations.Occurrence>();
            for (var path : PolynomialTheoryUtilityExecutionObservations.paths(formation)) {
                occurrences.add(new PolynomialTheoryUtilityExecutionObservations.Occurrence(
                    occurrences.size(), path, TerminalStatus.NO_TRANSITION, "ZERO_WORK_COMPONENT_CONTROL",
                    "NONE", "NONE", 0, PolynomialWorkLedger.empty(), List.of(), List.of()));
            }
            return PolynomialTheoryUtilityCandidateResult.createObserved(input, formation,
                "ZERO_WORK_COMPONENT_CONTROL", List.of(), "NOT_REQUESTED",
                PolynomialTheoryUtilityExecutionObservations.create(PolynomialWorkLedger.empty(), occurrences));
        }
    }

    private static final class Counts {
        private int opened;
        private int executed;
        private int closed;
    }
}
