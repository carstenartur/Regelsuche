package de.regelsuche.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DeterministicCounterexampleSearchServiceTest {
    private final DeterministicCounterexampleSearchService service = new DeterministicCounterexampleSearchService();

    @Test
    void existingNonZeroAssumptionIsRespectedDuringSampling() {
        CounterexampleSearchService.CounterexampleSearchResult result = service.search(
            new CounterexampleSearchService.HypothesisInput(
                "h",
                "(a * b) / b",
                "a",
                List.of("0 != b")
            ),
            CounterexampleSearchService.CounterexampleBudget.defaultBudget()
        );

        assertFalse(result.counterexample().isPresent());
        assertEquals(CounterexampleSearchService.Status.NO_COUNTEREXAMPLE_FOUND, result.status());
        assertTrue(result.inferredAssumptions().isEmpty(),
            "existing b != 0 assumption should filter b=0 samples instead of being re-inferred");
    }

    @Test
    void missingNonZeroAssumptionIsInferredWhenZeroDenominatorAppears() {
        CounterexampleSearchService.CounterexampleSearchResult result = service.search(
            new CounterexampleSearchService.HypothesisInput(
                "h",
                "(a * b) / b",
                "a",
                List.of()
            ),
            CounterexampleSearchService.CounterexampleBudget.defaultBudget()
        );

        assertTrue(result.inferredAssumptions().contains("b != 0"));
        assertEquals(CounterexampleSearchService.Status.NO_COUNTEREXAMPLE_FOUND, result.status());
    }

    @Test
    void budgetBoundariesChangeResultReproducibly() {
        CounterexampleSearchService.HypothesisInput hypothesis =
            new CounterexampleSearchService.HypothesisInput("h", "x", "x + 1", List.of());

        CounterexampleSearchService.CounterexampleSearchResult exhausted = service.search(
            hypothesis,
            new CounterexampleSearchService.CounterexampleBudget(0, false, false, 7L)
        );
        CounterexampleSearchService.CounterexampleSearchResult sampled = service.search(
            hypothesis,
            new CounterexampleSearchService.CounterexampleBudget(1, false, false, 7L)
        );

        assertFalse(exhausted.counterexample().isPresent());
        assertEquals(CounterexampleSearchService.Status.INCONCLUSIVE, exhausted.status());
        assertTrue(sampled.counterexample().isPresent());
        assertEquals(CounterexampleSearchService.Status.COUNTEREXAMPLE_FOUND, sampled.status());
    }

    @Test
    void sameSeedRepeatsAssignmentsAndDifferentSeedChangesThem() {
        CounterexampleSearchService.HypothesisInput hypothesis =
            new CounterexampleSearchService.HypothesisInput("h", "x", "x + 1", List.of());
        CounterexampleSearchService.CounterexampleBudget seed7 =
            new CounterexampleSearchService.CounterexampleBudget(2, false, false, 7L);
        CounterexampleSearchService.CounterexampleBudget seed8 =
            new CounterexampleSearchService.CounterexampleBudget(2, false, false, 8L);

        List<String> first = service.search(hypothesis, seed7).counterexample().orElseThrow().assignments();
        List<String> second = service.search(hypothesis, seed7).counterexample().orElseThrow().assignments();
        List<String> different = service.search(hypothesis, seed8).counterexample().orElseThrow().assignments();

        assertEquals(first, second);
        assertNotEquals(first, different);
    }

    @Test
    void matrixBudgetControlsNonCommutativeRefutation() {
        CounterexampleSearchService.HypothesisInput hypothesis =
            new CounterexampleSearchService.HypothesisInput("h", "A * B", "B * A", List.of());

        CounterexampleSearchService.CounterexampleSearchResult matrixDisabled = service.search(
            hypothesis,
            new CounterexampleSearchService.CounterexampleBudget(0, false, false, 1L)
        );
        CounterexampleSearchService.CounterexampleSearchResult matrixEnabled = service.search(
            hypothesis,
            new CounterexampleSearchService.CounterexampleBudget(0, false, true, 1L)
        );

        assertFalse(matrixDisabled.counterexample().isPresent());
        assertEquals(CounterexampleSearchService.Status.INCONCLUSIVE, matrixDisabled.status());
        assertTrue(matrixEnabled.counterexample().isPresent());
        assertEquals(CounterexampleSearchService.Status.COUNTEREXAMPLE_FOUND, matrixEnabled.status());
        assertEquals(List.of("matrix-non-commutative"), matrixEnabled.attemptedSources());
    }

    @Test
    void complexSamplingRefutesPrincipalSqrtIdentity() {
        CounterexampleSearchService.CounterexampleSearchResult result = service.search(
            new CounterexampleSearchService.HypothesisInput("h", "sqrt(x^2)", "x", List.of()),
            new CounterexampleSearchService.CounterexampleBudget(0, false, false, 1L, true)
        );

        assertTrue(result.counterexample().isPresent());
        assertEquals(CounterexampleSearchService.Status.COUNTEREXAMPLE_FOUND, result.status());
        assertEquals(List.of("complex-samples"), result.attemptedSources());
    }
}
