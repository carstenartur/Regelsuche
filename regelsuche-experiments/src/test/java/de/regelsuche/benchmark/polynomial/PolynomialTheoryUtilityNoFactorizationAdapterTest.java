package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityProfileAdapter.CandidateResult;
import de.regelsuche.benchmark.polynomial
    .PolynomialTheoryUtilityProfileAdapter.NoFactorizationAdapter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilityNoFactorizationAdapterTest {
    @Test
    void executesAllSixFrozenRunsWithZeroWork() {
        var inputs = baselineInputs();
        var formation = PolynomialTheoryUtilityCaseCorpus.load().cases();
        var adapter = new NoFactorizationAdapter();
        List<CandidateResult> results = new ArrayList<>(inputs.size());

        for (var checkpoint : PolynomialTheoryUtilityExecutionPlan.CHECKPOINTS) {
            var runInputs = inputs.stream()
                .filter(value -> checkpoint.checkpointId().equals(
                    value.checkpointId()
                ))
                .toList();
            var first = runInputs.get(0);
            var run = adapter.openRun(descriptor(first));
            for (int index = 0; index < runInputs.size(); index++) {
                results.add(run.execute(runInputs.get(index), formation.get(index)));
            }
            run.close();
        }

        assertEquals(120, results.size());
        assertEquals(
            120L,
            results.stream().map(CandidateResult::resultId).distinct().count()
        );
        for (int index = 0; index < results.size(); index++) {
            var result = results.get(index);
            var input = inputs.get(index);
            result.validateAgainst(input);
            assertEquals(
                CandidateResult.TerminalStatus.NO_TRANSITION,
                result.terminalStatus()
            );
            assertEquals(NoFactorizationAdapter.DETAIL_CODE, result.detailCode());
            assertEquals(0L, result.primitiveWorkConsumed());
            assertEquals(0L, result.mechanicalWorkConsumed());
            assertEquals(0L, result.factorizationWorkConsumed());
            assertEquals(0, result.generatedTransitions());
            assertEquals("NOT_REQUESTED", result.verifierOutcome());
            assertEquals("NONE", result.transitionEvidenceHash());
        }
    }

    @Test
    void rejectsReorderedIncompleteAndClosedRuns() {
        var runInputs = baselineInputs().stream()
            .filter(value -> "CP01_1_OF_12".equals(value.checkpointId()))
            .toList();
        var formation = PolynomialTheoryUtilityCaseCorpus.load().cases();
        var adapter = new NoFactorizationAdapter();

        var reordered = adapter.openRun(descriptor(runInputs.get(0)));
        assertThrows(
            IllegalArgumentException.class,
            () -> reordered.execute(runInputs.get(1), formation.get(1))
        );

        var incomplete = adapter.openRun(descriptor(runInputs.get(0)));
        incomplete.execute(runInputs.get(0), formation.get(0));
        assertThrows(IllegalStateException.class, incomplete::close);

        var complete = adapter.openRun(descriptor(runInputs.get(0)));
        for (int index = 0; index < runInputs.size(); index++) {
            complete.execute(runInputs.get(index), formation.get(index));
        }
        complete.close();
        assertThrows(
            IllegalStateException.class,
            () -> complete.execute(runInputs.get(0), formation.get(0))
        );
        assertThrows(IllegalStateException.class, complete::close);
    }

    @Test
    void resultContractRejectsBudgetEvidenceAndRebinding() {
        var inputs = baselineInputs();
        var input = inputs.get(0);
        var valid = CandidateResult.noTransition(input, "BASELINE");

        assertThrows(
            IllegalArgumentException.class,
            () -> CandidateResult.create(
                input,
                CandidateResult.TerminalStatus.NO_TRANSITION,
                "EXCESSIVE_WORK",
                input.admittedPrimitiveWork() + 1L,
                0L,
                0L,
                0,
                "NOT_REQUESTED",
                "NONE"
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> CandidateResult.create(
                input,
                CandidateResult.TerminalStatus.VALIDATED_TRANSITION,
                "UNVERIFIED_TRANSITION",
                1L,
                1L,
                1L,
                1,
                "NOT_VERIFIED",
                "NONE"
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> valid.validateAgainst(inputs.get(1))
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

        var baseline = baselineInputs().get(0);
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
}
