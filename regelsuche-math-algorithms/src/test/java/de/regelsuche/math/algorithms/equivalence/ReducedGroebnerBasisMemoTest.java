package de.regelsuche.math.algorithms.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReducedGroebnerBasisMemoTest {
    @Test
    void completeInterreductionIsReusedWithoutCurrentCallSteps() {
        GroebnerBasisEngine engine = new GroebnerBasisEngine(new GradedReverseLexOrder());
        List<Polynomial> generators = List.of(
            Polynomial.variable("x").add(Polynomial.variable("y")),
            Polynomial.variable("x").subtract(Polynomial.variable("y"))
        );
        GroebnerBasisEngine.BasisPreparation preparation = engine.prepareIdeal(generators, 1_000, 1_000);
        assertFalse(preparation.budgetExhausted());

        GroebnerBasisEngine.EngineResult first = engine.normalFormModuloPreparedIdeal(
            Polynomial.zero(), preparation, 1_000, true);
        assertFalse(first.budgetExhausted());
        assertEquals("COMPLETE", first.reducedBasisStatus());
        assertFalse(first.reducedBasisCacheHit());
        assertTrue(first.interreductionSteps() > 0);
        assertEquals(0, first.reducedBasisStepsSaved());

        GroebnerBasisEngine.EngineResult second = engine.normalFormModuloPreparedIdeal(
            Polynomial.zero(), preparation, 0, true);
        assertFalse(second.budgetExhausted());
        assertEquals("COMPLETE", second.reducedBasisStatus());
        assertTrue(second.reducedBasisCacheHit());
        assertEquals(0, second.interreductionSteps());
        assertEquals(first.interreductionSteps(), second.reducedBasisStepsSaved());
        assertEquals(first.reducedBasis(), second.reducedBasis());
        assertEquals(0, second.steps());
    }

    @Test
    void incompleteInterreductionIsNotMemoized() {
        GroebnerBasisEngine engine = new GroebnerBasisEngine(new GradedReverseLexOrder());
        List<Polynomial> generators = List.of(
            Polynomial.variable("x").add(Polynomial.variable("y")),
            Polynomial.variable("x").subtract(Polynomial.variable("y"))
        );
        GroebnerBasisEngine.BasisPreparation preparation = engine.prepareIdeal(generators, 1_000, 1_000);
        assertFalse(preparation.budgetExhausted());

        GroebnerBasisEngine.EngineResult insufficient = engine.normalFormModuloPreparedIdeal(
            Polynomial.zero(), preparation, 0, true);
        assertFalse(insufficient.budgetExhausted());
        assertEquals("BUDGET_EXHAUSTED", insufficient.reducedBasisStatus());
        assertFalse(insufficient.reducedBasisCacheHit());

        GroebnerBasisEngine.EngineResult completed = engine.normalFormModuloPreparedIdeal(
            Polynomial.zero(), preparation, 1_000, true);
        assertFalse(completed.budgetExhausted());
        assertEquals("COMPLETE", completed.reducedBasisStatus());
        assertFalse(completed.reducedBasisCacheHit());
        assertTrue(completed.interreductionSteps() > 0);

        GroebnerBasisEngine.EngineResult reused = engine.normalFormModuloPreparedIdeal(
            Polynomial.zero(), preparation, 0, true);
        assertEquals("COMPLETE", reused.reducedBasisStatus());
        assertTrue(reused.reducedBasisCacheHit());
        assertEquals(0, reused.interreductionSteps());
        assertEquals(completed.interreductionSteps(), reused.reducedBasisStepsSaved());
    }
}
