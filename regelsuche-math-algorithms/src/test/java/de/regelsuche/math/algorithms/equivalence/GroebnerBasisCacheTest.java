package de.regelsuche.math.algorithms.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.math.algorithms.registry.DefaultMathematicalAlgorithmRegistry;
import de.regelsuche.validation.MathematicalAlgorithmRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GroebnerBasisCacheTest {
    @Test
    void repeatedIdealQueryReusesCompletedBasisPreparedReducersAndInterreduction() {
        GroebnerBasisEquivalenceService service = service(5_000, 5_000, 128);
        List<String> generators = List.of("x*y - 1", "y^2 - x");

        assertTrue(service.reducesToZeroModuloIdeal("x*y - 1", generators));
        Map<String, Object> first = service.lastResult().payload();
        int preparationSteps = integer(first, "basisPreparationSteps");
        int interreductionSteps = integer(first, "interreductionSteps");
        int firstSteps = integer(first, "steps");

        assertFalse((Boolean) first.get("basisCacheHit"));
        assertFalse((Boolean) first.get("reducedBasisCacheHit"));
        assertTrue(preparationSteps > 0);
        assertEquals(0, integer(first, "basisPreparationStepsSaved"));
        assertEquals(0, integer(first, "reducedBasisStepsSaved"));
        assertEquals(2, integer(first, "initialGeneratorsConsidered"));
        assertTrue(integer(first, "initialGeneratorsReduced") >= 0);
        assertTrue(integer(first, "initialGeneratorsEliminated") >= 0);
        assertEquals(1, integer(first, "basisCacheSize"));

        assertTrue(service.reducesToZeroModuloIdeal("x*y - 1", generators));
        Map<String, Object> second = service.lastResult().payload();

        assertTrue((Boolean) second.get("basisCacheHit"));
        assertTrue((Boolean) second.get("reducedBasisCacheHit"));
        assertEquals(0, integer(second, "basisPreparationSteps"));
        assertEquals(preparationSteps, integer(second, "basisPreparationStepsSaved"));
        assertEquals(integer(first, "queryReductionSteps"), integer(second, "queryReductionSteps"));
        assertEquals(0, integer(second, "interreductionSteps"));
        assertEquals(interreductionSteps, integer(second, "reducedBasisStepsSaved"));
        assertEquals(2, integer(second, "initialGeneratorsConsidered"));
        assertEquals(
            firstSteps - preparationSteps - interreductionSteps,
            integer(second, "steps")
        );
    }

    @Test
    void generatorOrderDoesNotChangeCacheIdentity() {
        GroebnerBasisEquivalenceService service = service(5_000, 5_000, 128);

        assertTrue(service.reducesToZeroModuloIdeal(
            "x*y - 1",
            List.of("x*y - 1", "y^2 - x")
        ));
        assertFalse((Boolean) service.lastResult().payload().get("basisCacheHit"));

        assertTrue(service.reducesToZeroModuloIdeal(
            "x*y - 1",
            List.of("y^2 - x", "x*y - 1")
        ));
        assertTrue((Boolean) service.lastResult().payload().get("basisCacheHit"));
        assertEquals(1, integer(service.lastResult().payload(), "basisCacheSize"));
    }

    @Test
    void exhaustedBasisPreparationIsNeverCached() {
        GroebnerBasisEquivalenceService service = service(0, 10, 128);
        List<String> generators = List.of("x*y - 1", "y^2 - x");

        assertFalse(service.reducesToZeroModuloIdeal("x*y - 1", generators));
        assertEquals(MathematicalAlgorithmRegistry.ExecutionStatus.BUDGET_EXHAUSTED, service.lastResult().status());
        assertFalse((Boolean) service.lastResult().payload().get("basisCacheHit"));
        assertEquals(0, integer(service.lastResult().payload(), "basisCacheSize"));

        assertFalse(service.reducesToZeroModuloIdeal("x*y - 1", generators));
        assertFalse((Boolean) service.lastResult().payload().get("basisCacheHit"));
        assertEquals(0, integer(service.lastResult().payload(), "basisCacheSize"));
    }

    @Test
    void cacheUsesBoundedLeastRecentlyUsedEviction() {
        GroebnerBasisEquivalenceService service = service(100, 100, 1);

        assertTrue(service.reducesToZeroModuloIdeal("x - 1", List.of("x - 1")));
        assertFalse((Boolean) service.lastResult().payload().get("basisCacheHit"));
        assertEquals(1, integer(service.lastResult().payload(), "basisCacheSize"));

        assertTrue(service.reducesToZeroModuloIdeal("y - 1", List.of("y - 1")));
        assertFalse((Boolean) service.lastResult().payload().get("basisCacheHit"));
        assertEquals(1, integer(service.lastResult().payload(), "basisCacheSize"));

        assertTrue(service.reducesToZeroModuloIdeal("x - 1", List.of("x - 1")));
        assertFalse((Boolean) service.lastResult().payload().get("basisCacheHit"));
        assertEquals(1, integer(service.lastResult().payload(), "basisCacheSize"));
        assertEquals(1, integer(service.lastResult().payload(), "basisCacheCapacity"));
    }

    private GroebnerBasisEquivalenceService service(int maxSteps, int maxPairs, int cacheCapacity) {
        MathematicalAlgorithmRegistry registry = new DefaultMathematicalAlgorithmRegistry(
            Map.of(MathematicalAlgorithmRegistry.GROEBNER_BASIS, true),
            Map.of(
                MathematicalAlgorithmRegistry.GROEBNER_BASIS,
                MathematicalAlgorithmRegistry.AlgorithmBudget.bounded(maxSteps, maxPairs, 0, 0.0)
            )
        );
        return new GroebnerBasisEquivalenceService(
            registry,
            false,
            new GradedReverseLexOrder(),
            cacheCapacity
        );
    }

    private int integer(Map<String, Object> payload, String key) {
        return ((Number) payload.get(key)).intValue();
    }
}
