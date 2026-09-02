package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilityOnDemandOccurrencePlanTest {
    private static final List<PolynomialTheoryUtilityCaseCorpus.FormationCase>
        CASES = PolynomialTheoryUtilityCaseCorpus.load().cases();
    private static final List<PolynomialTheoryUtilityExecutionInput> INPUTS =
        PolynomialTheoryUtilityExecutionInputs.freeze().inputs();

    @Test
    void retainsTheRootAsOneIndependentOccurrence() {
        var plan = plan("z02-difference-of-squares", "CP06_FULL");

        assertEquals(List.of(List.of()), paths(plan));
        assertEquals(64, plan.occurrences().getFirst()
            .admittedPrimitiveWork());
        assertEquals(256, plan.occurrences().getFirst()
            .totalMechanicalWork());
        assertEquals(64, plan.occurrences().getFirst()
            .factorizationWork());
        assertEquals("root", plan.occurrences().getFirst().pathKey());
    }

    @Test
    void resolvesTheFrozenNestedRightOccurrence() {
        var plan = plan("nested-single-occurrence", "CP06_FULL");

        assertEquals(List.of(List.of(1)), paths(plan));
        assertEquals(1, plan.occurrences().getFirst().path().size());
    }

    @Test
    void partitionsTwoSiblingAuthoritiesWithoutOverlap() {
        var plan = plan("two-identical-occurrences", "CP06_FULL");

        assertEquals(List.of(List.of(0), List.of(1)), paths(plan));
        assertEquals(
            List.of(80, 80),
            plan.occurrences().stream()
                .map(value -> value.admittedPrimitiveWork())
                .toList()
        );
        assertEquals(
            List.of(320, 320),
            plan.occurrences().stream()
                .map(value -> value.totalMechanicalWork())
                .toList()
        );
        assertEquals(
            List.of(40, 40),
            plan.occurrences().stream()
                .map(value -> value.factorizationWork())
                .toList()
        );
    }

    @Test
    void distributesCheckpointRemaindersInFrozenPathOrder() {
        var plan = plan("four-identical-occurrences", "CP01_1_OF_12");

        assertEquals(
            List.of(
                List.of(0, 0),
                List.of(0, 1),
                List.of(1, 0),
                List.of(1, 1)
            ),
            paths(plan)
        );
        assertEquals(
            List.of(6, 6, 5, 5),
            plan.occurrences().stream()
                .map(value -> value.admittedPrimitiveWork())
                .toList()
        );
        assertEquals(
            List.of(22, 22, 21, 21),
            plan.occurrences().stream()
                .map(value -> value.totalMechanicalWork())
                .toList()
        );
        assertEquals(
            List.of(2, 2, 1, 1),
            plan.occurrences().stream()
                .map(value -> value.factorizationWork())
                .toList()
        );
    }

    @Test
    void bindsThePlanIdentityToTheCheckpointAuthorities() {
        var first = plan("four-identical-occurrences", "CP01_1_OF_12");
        var full = plan("four-identical-occurrences", "CP06_FULL");

        assertNotEquals(first.planId(), full.planId());
        assertEquals(
            first.factorizationWork(),
            first.occurrences().stream()
                .mapToInt(value -> value.factorizationWork())
                .sum()
        );
    }

    @Test
    void rejectsAnotherFrozenProfile() {
        var input = input(
            "z02-difference-of-squares",
            "CP06_FULL",
            "NO_FACTORIZATION"
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityOnDemandOccurrencePlan.create(
                input,
                formationCase("z02-difference-of-squares")
            )
        );
    }

    @Test
    void rejectsAFormationCaseFromAnotherRow() {
        var input = input(
            "z02-difference-of-squares",
            "CP06_FULL",
            PolynomialTheoryUtilityOnDemandOccurrencePlan.PROFILE_ID
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityOnDemandOccurrencePlan.create(
                input,
                formationCase("z03-cubic-unity")
            )
        );
    }

    @Test
    void rejectsWorkThatDoesNotMatchTheFrozenCheckpoint() {
        var input = input(
            "z02-difference-of-squares",
            "CP06_FULL",
            PolynomialTheoryUtilityOnDemandOccurrencePlan.PROFILE_ID
        );
        var altered = new PolynomialTheoryUtilityExecutionInput(
            input.inputId(),
            input.rowId(),
            input.runId(),
            input.caseId(),
            input.profileId(),
            input.checkpointId(),
            input.adapterId(),
            input.admittedPrimitiveWork() + 1,
            input.totalMechanicalWork() + 1,
            input.factorizationWork(),
            input.inputStatus()
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> PolynomialTheoryUtilityOnDemandOccurrencePlan.create(
                altered,
                formationCase("z02-difference-of-squares")
            )
        );
    }

    private static PolynomialTheoryUtilityOnDemandOccurrencePlan.Plan plan(
        String caseId,
        String checkpointId
    ) {
        return PolynomialTheoryUtilityOnDemandOccurrencePlan.create(
            input(
                caseId,
                checkpointId,
                PolynomialTheoryUtilityOnDemandOccurrencePlan.PROFILE_ID
            ),
            formationCase(caseId)
        );
    }

    private static PolynomialTheoryUtilityExecutionInput input(
        String caseId,
        String checkpointId,
        String profileId
    ) {
        return INPUTS.stream()
            .filter(value -> caseId.equals(value.caseId()))
            .filter(value -> checkpointId.equals(value.checkpointId()))
            .filter(value -> profileId.equals(value.profileId()))
            .findFirst()
            .orElseThrow();
    }

    private static PolynomialTheoryUtilityCaseCorpus.FormationCase
            formationCase(String caseId) {
        return CASES.stream()
            .filter(value -> caseId.equals(value.caseId()))
            .findFirst()
            .orElseThrow();
    }

    private static List<List<Integer>> paths(
        PolynomialTheoryUtilityOnDemandOccurrencePlan.Plan plan
    ) {
        return plan.occurrences().stream()
            .map(value -> value.path())
            .toList();
    }
}
