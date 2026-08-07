package de.regelsuche.math.algorithms.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class GroebnerGeneratorPreprocessingTest {
    private static final int TEST_STEP_BUDGET = 5_000;
    private static final int TEST_PAIR_BUDGET = 5_000;

    @Test
    void redundantGeneratorsDisappearBeforeAnyCriticalPairsAreCreated() {
        Polynomial x = Polynomial.variable("x");
        Polynomial y = Polynomial.variable("y");
        Polynomial z = Polynomial.variable("z");

        BuchbergerAlgorithm.BasisComputation result = new BuchbergerAlgorithm().computeBasis(
            List.of(x.multiply(y), x, x.multiply(z), x),
            new GradedReverseLexOrder(),
            100,
            100
        );

        assertFalse(result.budgetExhausted());
        assertEquals(List.of(x), result.basis());
        assertEquals(4, result.initialGeneratorsConsidered());
        assertEquals(2, result.initialGeneratorsReduced());
        assertEquals(3, result.initialGeneratorsEliminated());
        assertEquals(0, result.pairsConsidered());
        assertEquals(0, result.pairsReduced());
    }

    @Test
    void reducibleGeneratorIsReplacedByItsIdealEquivalentRemainder() {
        Polynomial x = Polynomial.variable("x");
        Polynomial y = Polynomial.variable("y");
        MonomialOrder order = new LexOrder();

        BuchbergerAlgorithm.BasisComputation result = new BuchbergerAlgorithm().computeBasis(
            List.of(x.add(y), x),
            order,
            100,
            100
        );

        assertFalse(result.budgetExhausted());
        assertEquals(2, result.initialGeneratorsConsidered());
        assertEquals(1, result.initialGeneratorsReduced());
        assertEquals(0, result.initialGeneratorsEliminated());
        assertGeneratorsReduceToZero(List.of(x.add(y), x), result.basis(), order);
        assertBuchbergerCriterion(result.basis(), order);
        assertTrue(result.basis().contains(x));
        assertTrue(result.basis().contains(y));
    }

    @Test
    void preprocessingOrderIsIndependentOfGeneratorInputOrder() {
        Polynomial x = Polynomial.variable("x");
        Polynomial y = Polynomial.variable("y");
        Polynomial z = Polynomial.variable("z");
        BuchbergerAlgorithm algorithm = new BuchbergerAlgorithm();
        MonomialOrder order = new GradedReverseLexOrder();

        BuchbergerAlgorithm.BasisComputation first = algorithm.computeBasis(
            List.of(x.multiply(y), x, x.multiply(z)), order, 100, 100);
        BuchbergerAlgorithm.BasisComputation second = algorithm.computeBasis(
            List.of(x.multiply(z), x.multiply(y), x), order, 100, 100);

        assertEquals(first.basis(), second.basis());
        assertEquals(first.steps(), second.steps());
        assertEquals(first.pairsConsidered(), second.pairsConsidered());
        assertEquals(first.initialGeneratorsEliminated(), second.initialGeneratorsEliminated());
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void boundedRepresentativeBasesSatisfyBuchbergerCriterionAndContainInputIdeal() {
        Polynomial x = Polynomial.variable("x");
        Polynomial y = Polynomial.variable("y");
        Polynomial z = Polynomial.variable("z");
        Polynomial one = Polynomial.constant(Rational.ONE);
        List<List<Polynomial>> systems = List.of(
            List.of(x.multiply(y), x, x.multiply(z), x),
            List.of(x.add(y), x),
            List.of(x.multiply(y).subtract(one), y.pow(2).subtract(x)),
            List.of(x.pow(2).subtract(y), x.multiply(y).subtract(one))
        );

        for (MonomialOrder order : List.of(new GradedReverseLexOrder(), new LexOrder())) {
            for (int example = 0; example < systems.size(); example++) {
                List<Polynomial> generators = systems.get(example);
                BuchbergerAlgorithm.BasisComputation result = new BuchbergerAlgorithm().computeBasis(
                    generators,
                    order,
                    TEST_STEP_BUDGET,
                    TEST_PAIR_BUDGET
                );

                assertFalse(result.budgetExhausted(), "example " + example + " / " + order.name());
                assertGeneratorsReduceToZero(generators, result.basis(), order);
                assertBuchbergerCriterion(result.basis(), order);
            }
        }
    }

    private void assertGeneratorsReduceToZero(
        List<Polynomial> generators,
        List<Polynomial> basis,
        MonomialOrder order
    ) {
        PolynomialReducer reducer = new PolynomialReducer();
        for (Polynomial generator : generators) {
            PolynomialReducer.ReductionResult reduction = reducer.reduce(
                generator,
                basis,
                order,
                TEST_STEP_BUDGET
            );
            assertFalse(reduction.budgetExhausted());
            assertTrue(reduction.remainder().isZero(), generator.toCanonicalString(order));
        }
    }

    private void assertBuchbergerCriterion(List<Polynomial> basis, MonomialOrder order) {
        PolynomialReducer reducer = new PolynomialReducer();
        for (int i = 0; i < basis.size(); i++) {
            for (int j = i + 1; j < basis.size(); j++) {
                Polynomial sPolynomial = reducer.sPolynomial(basis.get(i), basis.get(j), order);
                PolynomialReducer.ReductionResult reduction = reducer.reduce(
                    sPolynomial,
                    basis,
                    order,
                    TEST_STEP_BUDGET
                );
                assertFalse(reduction.budgetExhausted());
                assertTrue(
                    reduction.remainder().isZero(),
                    basis.get(i).toCanonicalString(order) + " / " + basis.get(j).toCanonicalString(order)
                );
            }
        }
    }
}
