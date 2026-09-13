package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityMeasuredExecution.MeasuredRun;
import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityProfileAdapter.NoFactorizationAdapter;
import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityProfileAdapter.RunDescriptor;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilityObservedProfileAdapterTest {
    @Test
    void existingProductiveAdaptersDeclareTheirExplicitResultRevision() {
        assertEquals(PolynomialTheoryUtilityCandidateResult.SCHEMA,
            new NoFactorizationAdapter().resultSchema());
        assertEquals(PolynomialTheoryUtilityCandidateResult.OBSERVED_SCHEMA,
            NoFactorizationAdapter.observed().resultSchema());
        assertEquals(PolynomialTheoryUtilityCandidateResult.SCHEMA,
            new PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter(value -> { }).resultSchema());
        assertEquals(PolynomialTheoryUtilityCandidateResult.OBSERVED_SCHEMA,
            PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter.observed(value -> { }).resultSchema());
    }

    @Test
    void disabledFactorizationRetainsEveryOccurrenceForTheObservedResultRevision() {
        var inputs = PolynomialTheoryUtilityExecutionInputs.freeze().inputs().stream()
            .filter(value -> value.profileId().equals("NO_FACTORIZATION")
                && value.checkpointId().equals("CP06_FULL")).toList();
        var first = inputs.getFirst();
        var formation = PolynomialTheoryUtilityCaseCorpus.load().cases();
        var results = new ArrayList<PolynomialTheoryUtilityCandidateResult>();
        var adapter = NoFactorizationAdapter.observed();
        try (var run = adapter.openRun(new RunDescriptor(first.runId(), first.profileId(),
                first.checkpointId(), first.adapterId(), inputs.size()))) {
            for (int index = 0; index < inputs.size(); index++) {
                results.add(run.execute(inputs.get(index), formation.get(index)));
            }
        }
        for (int index = 0; index < results.size(); index++) {
            var result = results.get(index);
            assertEquals("regelsuche.polynomial-theory-utility-candidate-result/v3", result.schema());
            assertNotNull(result.observations());
            assertEquals(PolynomialTheoryUtilityExecutionObservations.paths(formation.get(index)),
                result.observations().occurrences().stream().map(value -> value.path()).toList());
            assertEquals(PolynomialTheoryUtilityWorkBreakdown.zero(), result.work());
            assertEquals(List.of(), result.transitions());
            var measured = PolynomialTheoryUtilityMeasuredCandidate.withoutObservations(result);
            assertEquals("regelsuche.polynomial-theory-utility-candidate-measurements/v2",
                measured.measurements().schema());
        }
    }

    @Test
    void nativeObservedRunRetainsItsExactExecutionsThroughTheStrictMeasuredEntry() {
        var inputs = PolynomialTheoryUtilityExecutionInputs.freeze().inputs().stream()
            .filter(value -> value.profileId().equals("ON_DEMAND_VERIFIED_FACTORIZATION")
                && value.checkpointId().equals("CP06_FULL")).toList();
        var first = inputs.getFirst();
        var formation = PolynomialTheoryUtilityCaseCorpus.load().cases();
        var executions = new ArrayList<
            PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter.Execution>();
        var adapter = PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter.observed(executions::add);
        try (var run = (MeasuredRun) adapter.openRun(new RunDescriptor(first.runId(), first.profileId(),
                first.checkpointId(), first.adapterId(), inputs.size()))) {
            for (int index = 0; index < inputs.size(); index++) {
                var measured = run.executeObserved(inputs.get(index), formation.get(index));
                var execution = executions.get(index);
                assertSame(execution.measured(), measured);
                assertEquals("regelsuche.polynomial-theory-utility-candidate-result/v3", measured.result().schema());
                assertEquals("regelsuche.polynomial-theory-utility-candidate-measurements/v2",
                    measured.measurements().schema());
                assertEquals(execution.rawWork(),
                    measured.result().observations().rawWork().totalMechanicalWork());
                assertEquals(execution.projection().work(), measured.result().work());
            }
        }
        assertEquals(inputs.size(), executions.size());
    }
}
