package de.regelsuche.math.algorithms.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class GroebnerGeneratorPreprocessingTest {
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
    void deterministicPreprocessedBasesSatisfyBuchbergerCriterionAndContainInputIdeal() {
        Random random = new Random(2026080708L);
        for (MonomialOrder order : List.of(new GradedReverseLexOrder(), new LexOrder())) {
            for (int example = 0; example < 40; example++) {
                List<Polynomial> generators = new ArrayList<>();
                generators.add(randomPolynomial(random));
                generators.add(randomPolynomial(random));
                generators.add(randomPolynomial(random));
                Polynomial first = generators.get(0);
                generators.add(first.multiply(randomMonomial(random), Rational.ONE));

                BuchbergerAlgorithm.BasisComputation result = new BuchbergerAlgorithm().computeBasis(
                    generators, order, 20_000, 20_000);

                assertFalse(result.budgetExhausted(), "example " + example + " / " + order.name());
                assertGeneratorsReduceToZero(generators, result.basis(), order);
                assertBuchbergerCriterion(result.basis(), order);
            }
        }
    }

    private Polynomial randomPolynomial(Random random) {
        Polynomial result = Polynomial.zero();
        int termCount = 1 + random.nextInt(4);
        for (int i = 0; i < termCount; i++) {
            int coefficient = random.nextInt(5) - 2;
            if (coefficient == 0) {
                coefficient = 1;
            }
            result = result.add(Polynomial.term(randomMonomial(random), Rational.of(coefficient)));
        }
        return result.isZero() ? Polynomial.variable("x") : result;
    }

    private Monomial randomMonomial(Random random) {
        Map<String, Integer> powers = new HashMap<>();
        for (String variable : new String[]{"x", "y", "z"}) {
            int exponent = random.nextInt(3);
            if (exponent > 0) {
                powers.put(variable, exponent);
            }
        }
        return new Monomial(powers);
    }

    private void assertGeneratorsReduceToZero(
        List<Polynomial> generators,
        List<Polynomial> basis,
        MonomialOrder order
    ) {
        PolynomialReducer reducer = new PolynomialReducer();
        for (Polynomial generator : generators) {
            PolynomialReducer.ReductionResult reduction = reducer.reduce(generator, basis, order, 100_000);
            assertFalse(reduction.budgetExhausted());
            assertTrue(reduction.remainder().isZero(), generator.toCanonicalString(order));
        }
    }

    private void assertBuchbergerCriterion(List<Polynomial> basis, MonomialOrder order) {
        PolynomialReducer reducer = new PolynomialReducer();
        for (int i = 0; i < basis.size(); i++) {
            for (int j = i + 1; j < basis.size(); j++) {
                Polynomial sPolynomial = reducer.sPolynomial(basis.get(i), basis.get(j), order);
                PolynomialReducer.ReductionResult reduction = reducer.reduce(sPolynomial, basis, order, 100_000);
                assertFalse(reduction.budgetExhausted());
                assertTrue(reduction.remainder().isZero(),
                    basis.get(i).toCanonicalString(order) + " / " + basis.get(j).toCanonicalString(order));
            }
        }
    }
}
