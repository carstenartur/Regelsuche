package de.regelsuche.math.algorithms.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.math.algorithms.registry.DefaultMathematicalAlgorithmRegistry;
import de.regelsuche.validation.MathematicalAlgorithmRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class GroebnerBasisEngineOptimizationTest {
    private static final int TEST_STEP_BUDGET = 5_000;
    private static final int TEST_PAIR_BUDGET = 5_000;

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
        Polynomial z = Polynomial.variable("z");

        // None of these generators reduces through another one. The two degree-four
        // LCM pairs are therefore resolved first, allowing the degree-five endpoint
        // pair to be rejected specifically by the chain criterion.
        BuchbergerAlgorithm.BasisComputation result = new BuchbergerAlgorithm().computeBasis(
            List.of(
                x.pow(2).multiply(z),
                x.multiply(y).multiply(z),
                y.pow(2).multiply(z)
            ),
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
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void boundedSmallIdealsSatisfyBuchbergerCriterion() {
        Polynomial x = Polynomial.variable("x");
        Polynomial y = Polynomial.variable("y");
        Polynomial z = Polynomial.variable("z");
        Polynomial one = Polynomial.constant(Rational.ONE);
        List<List<Polynomial>> systems = List.of(
            List.of(x.pow(2).subtract(y), x.multiply(y).subtract(one)),
            List.of(x.add(y), x),
            List.of(
                x.pow(2).multiply(z),
                x.multiply(y).multiply(z),
                y.pow(2).multiply(z)
            ),
            List.of(x.pow(2).subtract(one), y.pow(2).subtract(one), z.pow(2).subtract(one))
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
