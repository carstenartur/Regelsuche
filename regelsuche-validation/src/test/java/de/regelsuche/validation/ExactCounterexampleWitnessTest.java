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

    @Test void meterChargesOnlyAssignmentsAdmittedToTheExactPairEvaluation() {
        var sources = new java.util.ArrayList<String>();
        var measured = new DeterministicCounterexampleSearchService(sources::add);
        var budget = new CounterexampleSearchService.CounterexampleBudget(0, true, false, 1L);
        measured.search(new CounterexampleSearchService.HypothesisInput("affine", "x", "1", List.of("x-1 >= 0")), budget);
        assertEquals(List.of("numeric-boundary-values", "numeric-boundary-values"), sources);
        sources.clear();
        measured.search(new CounterexampleSearchService.HypothesisInput("unbound", "3", "2", List.of("x > 0")), budget);
        assertTrue(sources.isEmpty());
        measured.search(new CounterexampleSearchService.HypothesisInput("ground", "2", "1", List.of()), budget);
        assertEquals(List.of("numeric-boundary-values"), sources);
    }

    @Test void unavailableExactArithmeticProducesNoRoundedWitness() {
        var unavailable = finder.search(new CounterexampleSearchService.HypothesisInput(
            "high power", "2^100", "0", List.of()), CounterexampleSearchService.CounterexampleBudget.defaultBudget());
        assertEquals(CounterexampleSearchService.Status.INCONCLUSIVE, unavailable.status());
        assertTrue(unavailable.counterexample().isEmpty());
    }

    @Test void samplesWithDifferentDomainsCannotRefuteAnExactScalarIdentity() {
        var complex = new CounterexampleSearchService.CounterexampleBudget(0, true, false, 1L, true);
        var exactIdentity = finder.search(new CounterexampleSearchService.HypothesisInput(
            "exact cancellation", "(9007199254740992+x)-9007199254740992", "x", List.of()), complex);
        assertTrue(exactIdentity.counterexample().isEmpty());

        var matrices = new CounterexampleSearchService.CounterexampleBudget(0, false, true, 1L);
        var guarded = finder.search(new CounterexampleSearchService.HypothesisInput(
            "zero matrix", "A*B", "B*A", List.of("A=0")), matrices);
        assertEquals(CounterexampleSearchService.Status.INCONCLUSIVE, guarded.status());
    }

    @Test void complexPowersHandleTheMinimumIntExponent() {
        var complex = new CounterexampleSearchService.CounterexampleBudget(0, false, false, 1L, true);
        var outcome = finder.search(new CounterexampleSearchService.HypothesisInput(
            "unequal modulus", "abs(x^(-2147483648))", "1", List.of()), complex);
        assertEquals(CounterexampleSearchService.Status.COUNTEREXAMPLE_FOUND, outcome.status());
    }

    @Test void approximateAnalyticSamplesMustSatisfyEveryDeclaredPremise() {
        var guarded = finder.search(new CounterexampleSearchService.HypothesisInput(
            "fixed real value", "abs(x)", "0", List.of("x=0")),
            CounterexampleSearchService.CounterexampleBudget.defaultBudget());
        assertTrue(guarded.counterexample().isEmpty());
    }

    @Test void matrixSamplingDoesNotAuthorizeDifferencesFromRounding() {
        String nine = "(A*B)*".repeat(8) + "(A*B)";
        String ten = nine + "*(A*B)";
        var matrices = new CounterexampleSearchService.CounterexampleBudget(0, false, true, 1L);
        var associative = finder.search(new CounterexampleSearchService.HypothesisInput(
            "matrix associativity", nine + "*" + ten, "(" + nine + ")*(" + ten + ")", List.of()), matrices);
        assertTrue(associative.counterexample().isEmpty());
    }
}
