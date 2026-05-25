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
        assertTrue(sampled.counterexample().isPresent());
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
}
