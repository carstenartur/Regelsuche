package de.regelsuche.math.algorithms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.math.algorithms.equivalence.PolynomialNormalFormEquivalenceService;
import de.regelsuche.math.algorithms.registry.DefaultMathematicalAlgorithmRegistry;
import de.regelsuche.validation.MathematicalAlgorithmRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PolynomialNormalFormEquivalenceServiceTest {
    @Test
    void disablingPolynomialEquivalencePreventsNormalFormExecution() {
        PolynomialNormalFormEquivalenceService service = new PolynomialNormalFormEquivalenceService(
            new DefaultMathematicalAlgorithmRegistry(Map.of(
                MathematicalAlgorithmRegistry.POLYNOMIAL_EQUIVALENCE, false,
                MathematicalAlgorithmRegistry.GROEBNER_BASIS, true
            ), Map.of())
        );

        assertFalse(service.arePolynomiallyEquivalent("(x+1)^2", "x^2+2*x+1"));
        assertEquals(MathematicalAlgorithmRegistry.ExecutionStatus.DISABLED, service.lastResult().status());
    }

    @Test
    void polynomialIdentityIsProofOnlyForSupportedPolynomialDomain() {
        PolynomialNormalFormEquivalenceService service = new PolynomialNormalFormEquivalenceService(
            new DefaultMathematicalAlgorithmRegistry(Map.of(
                MathematicalAlgorithmRegistry.POLYNOMIAL_EQUIVALENCE, true,
                MathematicalAlgorithmRegistry.GROEBNER_BASIS, false
            ), Map.of())
        );

        assertTrue(service.arePolynomiallyEquivalent("(x+1)^2", "x^2 + 2*x + 1"));
        assertEquals(MathematicalAlgorithmRegistry.ResultType.PROOF, service.lastResult().resultType());
        assertEquals(MathematicalAlgorithmRegistry.POLYNOMIAL_EQUIVALENCE, service.lastResult().payload().get("capability"));

        assertFalse(service.arePolynomiallyEquivalent("sin(x)", "x"));
        assertEquals(MathematicalAlgorithmRegistry.ExecutionStatus.UNKNOWN, service.lastResult().status());
    }

    @Test
    void supportsSmallLinearNormalFormEliminationWithoutClaimingGroebnerBasis() {
        PolynomialNormalFormEquivalenceService service = new PolynomialNormalFormEquivalenceService(
            new DefaultMathematicalAlgorithmRegistry(Map.of(
                MathematicalAlgorithmRegistry.POLYNOMIAL_EQUIVALENCE, true,
                MathematicalAlgorithmRegistry.GROEBNER_BASIS, false
            ), Map.of())
        );

        List<String> eliminated = service.eliminateLinearVariable(List.of("x + y - 3", "x - y - 1"), "x");
        assertFalse(eliminated.isEmpty());
        assertTrue(eliminated.stream().anyMatch(polynomial -> polynomial.contains("y")));
    }
}
