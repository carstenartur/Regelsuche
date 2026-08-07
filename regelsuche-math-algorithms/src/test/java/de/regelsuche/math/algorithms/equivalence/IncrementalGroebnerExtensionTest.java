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

class IncrementalGroebnerExtensionTest {
    @Test
    void extendingIndependentIdealDoesNotReconsiderOldPairs() {
        MonomialOrder order = new GradedReverseLexOrder();
        Polynomial one = Polynomial.constant(Rational.ONE);
        Polynomial x = Polynomial.variable("x");
        Polynomial y = Polynomial.variable("y");
        Polynomial z = Polynomial.variable("z");
        List<Polynomial> parentGenerators = List.of(x.pow(2).subtract(one), y.pow(2).subtract(one));
        Polynomial additional = z.pow(2).subtract(one);
        BuchbergerAlgorithm algorithm = new BuchbergerAlgorithm();

        BuchbergerAlgorithm.BasisComputation parent = algorithm.computeBasis(parentGenerators, order, 100, 100);
        BuchbergerAlgorithm.BasisComputation incremental = algorithm.extendBasis(
            parent.basis(), List.of(additional), order, 100, 100);
        BuchbergerAlgorithm.BasisComputation cold = algorithm.computeBasis(
            List.of(parentGenerators.get(0), parentGenerators.get(1), additional), order, 100, 100);

        assertFalse(parent.budgetExhausted());
        assertFalse(incremental.budgetExhausted());
        assertFalse(cold.budgetExhausted());
        assertEquals(2, incremental.pairsConsidered());
        assertEquals(3, cold.pairsConsidered());
        assertEquals(0, incremental.pairsReduced());
        assertEquals(1, incremental.extensionGeneratorsConsidered());
        assertBuchbergerCriterion(incremental.basis(), order);
    }

    @Test
    void generatorAlreadyImpliedByCachedIdealIsEliminatedBeforePairCreation() {
        MonomialOrder order = new GradedReverseLexOrder();
        Polynomial x = Polynomial.variable("x");
        Polynomial y = Polynomial.variable("y");
        BuchbergerAlgorithm algorithm = new BuchbergerAlgorithm();
        BuchbergerAlgorithm.BasisComputation parent = algorithm.computeBasis(List.of(x), order, 100, 100);

        BuchbergerAlgorithm.BasisComputation incremental = algorithm.extendBasis(
            parent.basis(), List.of(x.multiply(y)), order, 100, 100);

        assertFalse(incremental.budgetExhausted());
        assertEquals(1, incremental.extensionGeneratorsConsidered());
        assertEquals(1, incremental.extensionGeneratorsReduced());
        assertEquals(1, incremental.extensionGeneratorsEliminated());
        assertEquals(0, incremental.pairsConsidered());
        assertEquals(parent.basis(), incremental.basis());
    }

    @Test
    void serviceUsesBestCachedSubsetThenCachesExtendedIdeal() {
        GroebnerBasisEquivalenceService service = service();

        assertTrue(service.reducesToZeroModuloIdeal("x^2 - 1", List.of("x^2 - 1", "y^2 - 1")));
        assertEquals("COLD", service.lastResult().payload().get("basisReuseMode"));
        assertEquals(1, integer(service.lastResult().payload(), "basisCacheSize"));

        assertTrue(service.reducesToZeroModuloIdeal(
            "z^2 - 1",
            List.of("z^2 - 1", "x^2 - 1", "y^2 - 1")
        ));
        Map<String, Object> incremental = service.lastResult().payload();
        assertEquals("INCREMENTAL_EXTENSION", incremental.get("basisReuseMode"));
        assertFalse((Boolean) incremental.get("basisCacheHit"));
        assertEquals(2, integer(incremental, "incrementalBaseGeneratorCount"));
        assertEquals(1, integer(incremental, "extensionGeneratorsConsidered"));
        assertEquals(2, integer(incremental, "incrementalCandidatePairUpperBound"));
        assertEquals(3, integer(incremental, "coldInitialPairUpperBound"));
        assertEquals("", incremental.get("incrementalReuseRejectedReason"));
        assertEquals(2, integer(incremental, "basisCacheSize"));

        assertTrue(service.reducesToZeroModuloIdeal(
            "z^2 - 1",
            List.of("x^2 - 1", "y^2 - 1", "z^2 - 1")
        ));
        assertEquals("EXACT_CACHE", service.lastResult().payload().get("basisReuseMode"));
        assertTrue((Boolean) service.lastResult().payload().get("basisCacheHit"));
        assertEquals(0, integer(service.lastResult().payload(), "incrementalBaseGeneratorCount"));
    }

    @Test
    void incrementalExtensionReportsPreviouslyPaidPreparationAsSavedWork() {
        GroebnerBasisEquivalenceService service = service();
        List<String> parentGenerators = List.of("x*y - 1", "y^2 - x");

        assertTrue(service.reducesToZeroModuloIdeal("x*y - 1", parentGenerators));
        int parentPreparationSteps = integer(service.lastResult().payload(), "basisPreparationSteps");
        assertTrue(parentPreparationSteps > 0);

        assertTrue(service.reducesToZeroModuloIdeal(
            "z - 1",
            List.of("x*y - 1", "y^2 - x", "z - 1")
        ));
        Map<String, Object> incremental = service.lastResult().payload();
        assertEquals("INCREMENTAL_EXTENSION", incremental.get("basisReuseMode"));
        assertTrue(integer(incremental, "basisPreparationStepsSaved") >= parentPreparationSteps);
    }

    @Test
    void serviceRejectsIncrementalReuseWhenCachedBasisWouldCreateMoreInitialPairs() {
        GroebnerBasisEquivalenceService service = service();
        List<String> parentGenerators = List.of("x^3 - y", "x^2*y - y^2 + 1");

        assertTrue(service.reducesToZeroModuloIdeal("x^3 - y", parentGenerators));
        assertTrue(((List<?>) service.lastResult().payload().get("basis")).size() > parentGenerators.size());

        assertTrue(service.reducesToZeroModuloIdeal(
            "z - 1",
            List.of("x^3 - y", "x^2*y - y^2 + 1", "z - 1")
        ));
        Map<String, Object> cold = service.lastResult().payload();
        assertEquals("COLD", cold.get("basisReuseMode"));
        assertEquals("incremental-pair-upper-bound", cold.get("incrementalReuseRejectedReason"));
        assertTrue(integer(cold, "incrementalCandidatePairUpperBound")
            > integer(cold, "coldInitialPairUpperBound"));
    }

    @Test
    void deterministicIncrementalBasesRemainGroebnerBasesAndContainAllGenerators() {
        Random random = new Random(2026080704L);
        for (MonomialOrder order : List.of(new GradedReverseLexOrder(), new LexOrder())) {
            for (int example = 0; example < 30; example++) {
                List<Polynomial> parentGenerators = List.of(randomPolynomial(random), randomPolynomial(random));
                Polynomial additional = randomPolynomial(random);
                BuchbergerAlgorithm algorithm = new BuchbergerAlgorithm();
                BuchbergerAlgorithm.BasisComputation parent = algorithm.computeBasis(
                    parentGenerators, order, 20_000, 20_000);
                assertFalse(parent.budgetExhausted(), "parent " + example + " / " + order.name());

                BuchbergerAlgorithm.BasisComputation incremental = algorithm.extendBasis(
                    parent.basis(), List.of(additional), order, 20_000, 20_000);
                BuchbergerAlgorithm.BasisComputation cold = algorithm.computeBasis(
                    List.of(parentGenerators.get(0), parentGenerators.get(1), additional),
                    order,
                    20_000,
                    20_000
                );
                assertFalse(incremental.budgetExhausted(), "incremental " + example + " / " + order.name());
                assertFalse(cold.budgetExhausted(), "cold " + example + " / " + order.name());

                List<Polynomial> allGenerators = new ArrayList<>(parentGenerators);
                allGenerators.add(additional);
                assertGeneratorsReduceToZero(allGenerators, incremental.basis(), order);
                assertBuchbergerCriterion(incremental.basis(), order);

                Polynomial query = randomPolynomial(random);
                assertEquals(
                    reduce(query, cold.basis(), order).toCanonicalString(order),
                    reduce(query, incremental.basis(), order).toCanonicalString(order),
                    "normal form " + example + " / " + order.name()
                );
            }
        }
    }

    private GroebnerBasisEquivalenceService service() {
        MathematicalAlgorithmRegistry registry = new DefaultMathematicalAlgorithmRegistry(
            Map.of(MathematicalAlgorithmRegistry.GROEBNER_BASIS, true),
            Map.of(
                MathematicalAlgorithmRegistry.GROEBNER_BASIS,
                MathematicalAlgorithmRegistry.AlgorithmBudget.bounded(5_000, 5_000, 0, 0.0)
            )
        );
        return new GroebnerBasisEquivalenceService(registry, false, new GradedReverseLexOrder(), 16);
    }

    private Polynomial randomPolynomial(Random random) {
        Map<Monomial, Rational> terms = new HashMap<>();
        int termCount = 1 + random.nextInt(3);
        for (int i = 0; i < termCount; i++) {
            int xExponent = random.nextInt(3);
            int yExponent = random.nextInt(3);
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
        Polynomial polynomial = Polynomial.zero();
        for (Map.Entry<Monomial, Rational> entry : terms.entrySet()) {
            polynomial = polynomial.add(Polynomial.term(entry.getKey(), entry.getValue()));
        }
        return polynomial.isZero() ? Polynomial.variable("x") : polynomial;
    }

    private void assertGeneratorsReduceToZero(
        List<Polynomial> generators,
        List<Polynomial> basis,
        MonomialOrder order
    ) {
        for (Polynomial generator : generators) {
            assertTrue(reduce(generator, basis, order).isZero(), generator.toCanonicalString(order));
        }
    }

    private void assertBuchbergerCriterion(List<Polynomial> basis, MonomialOrder order) {
        PolynomialReducer reducer = new PolynomialReducer();
        for (int i = 0; i < basis.size(); i++) {
            for (int j = i + 1; j < basis.size(); j++) {
                Polynomial sPolynomial = reducer.sPolynomial(basis.get(i), basis.get(j), order);
                assertTrue(reduce(sPolynomial, basis, order).isZero(),
                    basis.get(i).toCanonicalString(order) + " / " + basis.get(j).toCanonicalString(order));
            }
        }
    }

    private Polynomial reduce(Polynomial polynomial, List<Polynomial> basis, MonomialOrder order) {
        PolynomialReducer.ReductionResult reduction = new PolynomialReducer().reduce(polynomial, basis, order, 100_000);
        assertFalse(reduction.budgetExhausted());
        return reduction.remainder();
    }

    private int integer(Map<String, Object> payload, String key) {
        return ((Number) payload.get(key)).intValue();
    }
}
