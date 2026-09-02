package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityCandidateResult.TerminalStatus;
import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityMeasuredExecution.MeasuredRun;
import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityProfileAdapter.RunDescriptor;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilityOnDemandProfileMatrixTest {
    @Test
    void executesEveryFrozenOnDemandRowThroughItsRunBoundary() {
        List<PolynomialTheoryUtilityCaseCorpus.FormationCase> cases =
            PolynomialTheoryUtilityCaseCorpus.load().cases();
        List<PolynomialTheoryUtilityExecutionInput> inputs =
            PolynomialTheoryUtilityExecutionInputs.freeze().inputs().stream()
                .filter(value ->
                    PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter
                        .PROFILE_ID.equals(value.profileId())
                )
                .toList();
        var adapter =
            new PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter();
        var admissionPolicy =
            PolynomialTheoryUtilityOnDemandAdmissionPolicy.freeze();
        Set<String> runIds = new HashSet<>();
        Set<String> resultIds = new HashSet<>();
        EnumSet<TerminalStatus> terminalStatuses =
            EnumSet.noneOf(TerminalStatus.class);

        assertEquals(
            cases.size()
                * PolynomialTheoryUtilityExecutionPlan.CHECKPOINTS.size(),
            inputs.size()
        );

        for (int offset = 0; offset < inputs.size(); offset += cases.size()) {
            List<PolynomialTheoryUtilityExecutionInput> runInputs =
                inputs.subList(offset, offset + cases.size());
            var first = runInputs.getFirst();
            assertTrue(runIds.add(first.runId()));

            var descriptor = new RunDescriptor(
                first.runId(),
                first.profileId(),
                first.checkpointId(),
                first.adapterId(),
                cases.size()
            );
            try (var run = adapter.openRun(descriptor)) {
                assertTrue(run instanceof MeasuredRun);
                MeasuredRun measuredRun = (MeasuredRun) run;
                for (int index = 0; index < cases.size(); index++) {
                    var input = runInputs.get(index);
                    var formationCase = cases.get(index);
                    var occurrencePlan =
                        PolynomialTheoryUtilityOnDemandOccurrencePlan.create(
                            input,
                            formationCase
                        );
                    long admittedOccurrences = occurrencePlan.occurrences()
                        .stream()
                        .filter(admissionPolicy::admits)
                        .count();
                    assertTrue(
                        admittedOccurrences == 0L
                            || admittedOccurrences
                                == occurrencePlan.occurrences().size(),
                        () -> "frozen row has mixed occurrence admission: "
                            + input.caseId() + ':' + input.checkpointId()
                    );

                    var measured = measuredRun.executeMeasured(
                        input,
                        formationCase
                    );
                    var result = measured.result();
                    var measurements = measured.measurements();

                    result.validateAgainst(input, formationCase);
                    measurements.validateAgainst(result);
                    assertTrue(resultIds.add(result.resultId()));
                    assertTrue(
                        result.detailCode().endsWith(
                            ':' + admissionPolicy.policyId()
                        )
                    );
                    assertEquals(
                        result.transitions().size(),
                        measurements.transitionTraces().size()
                    );
                    if (result.terminalStatus()
                            == TerminalStatus.VALIDATED_TRANSITION) {
                        assertTrue(admissionPolicy.admits(occurrencePlan));
                        assertEquals(
                            occurrencePlan.occurrences().size(),
                            result.transitions().size(),
                            "validated repeated case must retain every occurrence"
                        );
                    }
                    assertTrue(measurements.cacheEvents().isEmpty());
                    assertEquals(0L, result.work().cacheLookupWork());
                    assertEquals(0L, result.work().cacheInsertionWork());
                    assertEquals(0L, result.work().cacheEvictionWork());
                    assertEquals(0L, result.work().cacheReplayWork());
                    assertTrue(
                        result.work().primitiveWork()
                            <= input.admittedPrimitiveWork()
                    );
                    assertTrue(
                        result.work().mechanicalWork()
                            <= input.totalMechanicalWork()
                    );
                    assertTrue(
                        result.work().factorizationWork()
                            <= input.factorizationWork()
                    );
                    terminalStatuses.add(result.terminalStatus());
                }
            }
        }

        assertEquals(
            PolynomialTheoryUtilityExecutionPlan.CHECKPOINTS.size(),
            runIds.size()
        );
        assertEquals(inputs.size(), resultIds.size());
        assertTrue(terminalStatuses.contains(
            TerminalStatus.VALIDATED_TRANSITION
        ));
        assertTrue(terminalStatuses.contains(TerminalStatus.NO_TRANSITION));
        assertTrue(terminalStatuses.contains(TerminalStatus.UNSUPPORTED));
        assertTrue(terminalStatuses.contains(
            TerminalStatus.BUDGET_INCONCLUSIVE
        ));
        assertFalse(terminalStatuses.contains(
            TerminalStatus.TECHNICAL_FAILURE
        ));
    }
}
