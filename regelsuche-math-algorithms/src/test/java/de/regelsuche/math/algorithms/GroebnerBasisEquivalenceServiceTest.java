package de.regelsuche.math.algorithms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.math.algorithms.equivalence.GroebnerBasisEquivalenceService;
import de.regelsuche.math.algorithms.registry.DefaultMathematicalAlgorithmRegistry;
import de.regelsuche.validation.MathematicalAlgorithmRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GroebnerBasisEquivalenceServiceTest {
    @Test
    void reducesPolynomialModuloSmallIdeal() {
        GroebnerBasisEquivalenceService service = new GroebnerBasisEquivalenceService(
            new DefaultMathematicalAlgorithmRegistry(Map.of(
                MathematicalAlgorithmRegistry.POLYNOMIAL_EQUIVALENCE, false,
                MathematicalAlgorithmRegistry.GROEBNER_BASIS, true,
                MathematicalAlgorithmRegistry.JAS_BACKEND, false
            ), Map.of())
        );

        assertTrue(service.reducesToZeroModuloIdeal("x^2 - 1", List.of("x - 1")));
        assertEquals(MathematicalAlgorithmRegistry.ExecutionStatus.SUCCESS, service.lastResult().status());
        assertEquals(MathematicalAlgorithmRegistry.ResultType.PROOF, service.lastResult().resultType());
        assertEquals(MathematicalAlgorithmRegistry.GROEBNER_BASIS, service.lastResult().payload().get("capability"));
    }

    @Test
    void provesIdealMembershipWithMultipleGenerators() {
        GroebnerBasisEquivalenceService service = new GroebnerBasisEquivalenceService(
            new DefaultMathematicalAlgorithmRegistry(Map.of(
                MathematicalAlgorithmRegistry.GROEBNER_BASIS, true
            ), Map.of())
        );

        assertTrue(service.reducesToZeroModuloIdeal("x*y + x", List.of("x", "y - 1")));
        assertEquals(MathematicalAlgorithmRegistry.ExecutionStatus.SUCCESS, service.lastResult().status());
        assertEquals(MathematicalAlgorithmRegistry.ResultType.PROOF, service.lastResult().resultType());
        assertEquals("pureJavaSmallGroebner", service.lastResult().payload().get("backend"));
    }

    @Test
    void groebnerDisabledPreventsIdealReduction() {
        GroebnerBasisEquivalenceService service = new GroebnerBasisEquivalenceService(
            new DefaultMathematicalAlgorithmRegistry(Map.of(
                MathematicalAlgorithmRegistry.GROEBNER_BASIS, false
            ), Map.of())
        );

        assertFalse(service.reducesToZeroModuloIdeal("x^2 - 1", List.of("x - 1")));
        assertEquals(MathematicalAlgorithmRegistry.ExecutionStatus.DISABLED, service.lastResult().status());
    }

    @Test
    void jasBackendRequestedButUnavailableDoesNotFallbackToNormalForm() {
        GroebnerBasisEquivalenceService service = new GroebnerBasisEquivalenceService(
            new DefaultMathematicalAlgorithmRegistry(Map.of(
                MathematicalAlgorithmRegistry.GROEBNER_BASIS, true,
                MathematicalAlgorithmRegistry.JAS_BACKEND, true
            ), Map.of()),
            false
        );

        assertFalse(service.reducesToZeroModuloIdeal("x^2 - 1", List.of("x - 1")));
        assertEquals(MathematicalAlgorithmRegistry.ExecutionStatus.UNAVAILABLE, service.lastResult().status());
    }

    @Test
    void nonMemberHasSeparateGroebnerRefutationMetadata() {
        GroebnerBasisEquivalenceService service = new GroebnerBasisEquivalenceService(
            new DefaultMathematicalAlgorithmRegistry(Map.of(
                MathematicalAlgorithmRegistry.GROEBNER_BASIS, true
            ), Map.of())
        );

        assertFalse(service.reducesToZeroModuloIdeal("x", List.of("x^2")));
        assertEquals(MathematicalAlgorithmRegistry.ResultType.REFUTATION, service.lastResult().resultType());
        assertEquals(MathematicalAlgorithmRegistry.GROEBNER_BASIS, service.lastResult().payload().get("capability"));
        assertEquals("x", service.lastResult().payload().get("remainder"));
    }

    @Test
    void budgetExhaustedStopsBasisComputation() {
        GroebnerBasisEquivalenceService service = new GroebnerBasisEquivalenceService(
            new DefaultMathematicalAlgorithmRegistry(
                Map.of(MathematicalAlgorithmRegistry.GROEBNER_BASIS, true),
                Map.of(MathematicalAlgorithmRegistry.GROEBNER_BASIS,
                    MathematicalAlgorithmRegistry.AlgorithmBudget.bounded(0, 1, 0, 0.0))
            )
        );

        assertFalse(service.reducesToZeroModuloIdeal("x*y", List.of("x", "y")));
        assertEquals(MathematicalAlgorithmRegistry.ExecutionStatus.BUDGET_EXHAUSTED, service.lastResult().status());
    }

    @Test
    void unsupportedPolynomialDomainStaysUnknown() {
        GroebnerBasisEquivalenceService service = new GroebnerBasisEquivalenceService(
            new DefaultMathematicalAlgorithmRegistry(Map.of(
                MathematicalAlgorithmRegistry.GROEBNER_BASIS, true
            ), Map.of())
        );

        assertFalse(service.reducesToZeroModuloIdeal("sin(x)", List.of("x")));
        assertEquals(MathematicalAlgorithmRegistry.ExecutionStatus.UNKNOWN, service.lastResult().status());
    }
}
