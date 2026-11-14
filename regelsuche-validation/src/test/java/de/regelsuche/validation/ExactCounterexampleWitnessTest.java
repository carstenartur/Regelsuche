package de.regelsuche.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ExactCounterexampleWitnessTest {
    private final DeterministicCounterexampleSearchService finder = new DeterministicCounterexampleSearchService();

    @Test void cancellationAndNeighboringIntegersAreComparedExactly() {
        assertTrue(finder.search("9007199254740992+1-9007199254740992", "1").isEmpty());
        var difference = finder.search("9007199254740992", "9007199254740993").orElseThrow();
        assertEquals("9007199254740992", difference.leftValue());
        assertEquals("9007199254740993", difference.rightValue());
        assertEquals("1/300000000", finder.search("1/300000000", "0").orElseThrow().leftValue());
    }

    @Test void assignmentsAreReplayedWithExactRationalArithmetic() {
        assertTrue(finder.search("(9007199254740992+x)-9007199254740992", "x").isEmpty());
        var witness = finder.search("(9007199254740992+x)-9007199254740992", "x+1/300000000").orElseThrow();
        assertEquals(List.of("x=0"), witness.assignments());
        assertEquals("0", witness.leftValue());
        assertEquals("1/300000000", witness.rightValue());
    }

    @Test void exactWitnessSatisfiesTheSuppliedPremises() {
        var budget = new CounterexampleSearchService.CounterexampleBudget(0, true, false, 1L);
        var guarded = finder.search(new CounterexampleSearchService.HypothesisInput(
            "affine premise", "x", "1", List.of("x-1 >= 0")), budget);
        assertEquals(List.of("x=2"), guarded.counterexample().orElseThrow().assignments());
        var unknown = finder.search(new CounterexampleSearchService.HypothesisInput(
            "unbound premise", "x", "1", List.of("y > 0")), budget);
        assertEquals(CounterexampleSearchService.Status.INCONCLUSIVE, unknown.status());
    }

    @Test void samplesParserOwnedIdentifierOccurrencesAndRejectsOutOfLanguageInput() {
        var sample = finder.search("((α_2)) + 1/300000000", "α_2").orElseThrow();
        assertEquals(List.of("α_2=0"), sample.assignments());
        assertEquals("1/300000000", sample.leftValue());
        assertEquals("0", sample.rightValue());
        for (String invalid : List.of("_z", "1e3")) {
            var result = finder.search(new CounterexampleSearchService.HypothesisInput(
                "invalid source", invalid, "0", List.of()), CounterexampleSearchService.CounterexampleBudget.defaultBudget());
            assertEquals(CounterexampleSearchService.Status.INCONCLUSIVE, result.status());
        }
    }

    @Test void unavailableExactArithmeticProducesNoRoundedWitness() {
        var unavailable = finder.search(new CounterexampleSearchService.HypothesisInput(
            "high power", "2^100", "0", List.of()), CounterexampleSearchService.CounterexampleBudget.defaultBudget());
        assertEquals(CounterexampleSearchService.Status.INCONCLUSIVE, unavailable.status());
        assertTrue(unavailable.counterexample().isEmpty());
    }
}
