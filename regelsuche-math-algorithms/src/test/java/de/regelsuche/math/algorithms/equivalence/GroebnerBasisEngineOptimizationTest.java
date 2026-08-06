package de.regelsuche.math.algorithms.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.math.algorithms.registry.DefaultMathematicalAlgorithmRegistry;
import de.regelsuche.validation.MathematicalAlgorithmRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class GroebnerBasisEngineOptimizationTest {
    @Test
    void productCriterionAvoidsCoprimeCriticalPairsWithoutConsumingBudget() {
        Polynomial x = Polynomial.variable("x");
        Polynomial y = Polynomial.variable("y");
        Polynomial z = Polynomial.variable("z");
        Polynomial one = Polynomial.constant(Rational.ONE);

        BuchbergerAlgorithm.BasisComputation result = new BuchbergerAlgorithm().computeBasis(
            List.of(x.pow(2).subtract(one), y.pow(2).subtract(one), z.pow(2).subtract(one)),
            new GradedReverseLexOrder(),
            0,
            0
        );

        assertFalse(result.budgetExhausted());
        assertEquals(3, result.pairsConsidered());
        assertEquals(0, result.pairsReduced());
        assertEquals(3, result.pairsSkippedByProductCriterion());
        assertEquals(0, result.maxPendingPairs());
    }

    @Test
    void chainCriterionAvoidsPairWhoseSubchainsWereAlreadyResolved() {
        Polynomial x = Polynomial.variable("x");
        Polynomial y = Polynomial.variable("y");

        BuchbergerAlgorithm.BasisComputation result = new BuchbergerAlgorithm().computeBasis(
            List.of(x.pow(2).multiply(y), x.multiply(y), x.multiply(y.pow(2))),
            new GradedReverseLexOrder(),
            100,
            100
        );

        assertFalse(result.budgetExhausted());
        assertEquals(3, result.pairsConsidered());
        assertEquals(2, result.pairsReduced());
        assertEquals(1, result.pairsSkippedByChainCriterion());
    }

    @Test
    void sequentialInterreductionPreservesIdealWithEqualLeadingMonomials() {
        MonomialOrder order = new LexOrder();
        Polynomial x = Polynomial.variable("x");
        Polynomial y = Polynomial.variable("y");
        Polynomial z = Polynomial.variable("z");
        List<Polynomial> generators = List.of(x.add(y), x.add(z));

        GroebnerBasisEngine.EngineResult result = new GroebnerBasisEngine(order).normalFormModuloIdeal(
            generators.get(0).subtract(generators.get(1)),
            generators,
            200,
            200
        );

        assertFalse(result.budgetExhausted());
        assertEquals("COMPLETE", result.reducedBasisStatus());
        assertEquals(2, result.reducedBasis().size());
        assertGeneratorsReduceToZero(generators, result.reducedBasis(), order);
        assertInterreduced(result.reducedBasis(), order);
    }

    @Test
    void deterministicSmallIdealsSatisfyBuchbergerCriterion() {
        Random random = new Random(20260806L);
        for (MonomialOrder order : List.of(new GradedReverseLexOrder(), new LexOrder())) {
            for (int example = 0; example < 20; example++) {
                List<Polynomial> generators = new ArrayList<>();
                int generatorCount = 2 + random.nextInt(2);
                for (int i = 0; i < generatorCount; i++) {
                    generators.add(randomPolynomial(random));
                }

                BuchbergerAlgorithm.BasisComputation result = new BuchbergerAlgorithm().computeBasis(
                    generators,
                    order,
                    20_000,
                    20_000
                );

                assertFalse(result.budgetExhausted(), "example " + example + " with " + order.name());
                assertGeneratorsReduceToZero(generators, result.basis(), order);
                assertBuchbergerCriterion(result.basis(), order);
            }
        }
    }

    @Test
    void servicePayloadExposesPairReductionMetrics() {
        GroebnerBasisEquivalenceService service = new GroebnerBasisEquivalenceService(
            new DefaultMathematicalAlgorithmRegistry(
                Map.of(MathematicalAlgorithmRegistry.GROEBNER_BASIS, true),
                Map.of(MathematicalAlgorithmRegistry.GROEBNER_BASIS,
                    MathematicalAlgorithmRegistry.AlgorithmBudget.bounded(200, 200, 0, 0.0))
            )
        );

        assertTrue(service.reducesToZeroModuloIdeal(
            "x^2 - 1",
            List.of("x^2 - 1", "y^2 - 1", "z^2 - 1")
        ));

        Map<String, Object> payload = service.lastResult().payload();
        assertEquals("lcm-total-degree", payload.get("pairSelectionStrategy"));
        assertEquals(List.of("product", "chain"), payload.get("buchbergerCriteria"));
        assertEquals(3, payload.get("pairsConsidered"));
        assertEquals(0, payload.get("pairsReduced"));
        assertEquals(3, payload.get("pairsSkippedByProductCriterion"));
        assertEquals("COMPLETE", payload.get("reducedBasisStatus"));
    }

    private Polynomial randomPolynomial(Random random) {
        Map<Monomial, Rational> terms = new HashMap<>();
        int termCount = 1 + random.nextInt(4);
        for (int i = 0; i < termCount; i++) {
            int xExponent = random.nextInt(4);
            int yExponent = random.nextInt(4 - xExponent);
            int coefficient = random.nextInt(5) - 2;
            if (coefficient == 0) {
                coefficient = 1;
            }
            Map<String, Integer> powers = new HashMap<>();
            if (xExponent > 0) {
                powers.put("x", xExponent);
            }
            if (yExponent > 0) {
                powers.put("y", yExponent);
            }
            terms.merge(new Monomial(powers), Rational.of(coefficient), Rational::add);
        }

        Polynomial result = Polynomial.zero();
        for (Map.Entry<Monomial, Rational> entry : terms.entrySet()) {
            result = result.add(Polynomial.term(entry.getKey(), entry.getValue()));
        }
        return result.isZero() ? Polynomial.variable("x") : result;
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
                assertTrue(
                    reduction.remainder().isZero(),
                    basis.get(i).toCanonicalString(order) + " / " + basis.get(j).toCanonicalString(order)
                );
            }
        }
    }

    private void assertInterreduced(List<Polynomial> basis, MonomialOrder order) {
        for (int i = 0; i < basis.size(); i++) {
            Polynomial polynomial = basis.get(i);
            assertTrue(polynomial.leadingTerm(order).orElseThrow().coefficient().isOne());
            for (int j = 0; j < basis.size(); j++) {
                if (i == j) {
                    continue;
                }
                Monomial divisor = basis.get(j).leadingTerm(order).orElseThrow().monomial();
                for (Monomial monomial : polynomial.terms().keySet()) {
                    assertFalse(divisor.divides(monomial));
                }
            }
        }
    }
}
