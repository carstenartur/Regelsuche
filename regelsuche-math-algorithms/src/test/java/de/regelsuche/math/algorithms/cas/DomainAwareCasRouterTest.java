package de.regelsuche.math.algorithms.cas;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.regelsuche.math.algorithms.registry.DefaultMathematicalAlgorithmRegistry;
import de.regelsuche.validation.MathematicalAlgorithmRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DomainAwareCasRouterTest {
    @Test
    void routesDirectPolynomialIdentityToNormalFormBackend() {
        DomainAwareCasRouter router = new DomainAwareCasRouter(new DefaultMathematicalAlgorithmRegistry());

        MathematicalAlgorithmRegistry.AlgorithmExecutionResult result =
            router.provePolynomialIdentity("(x + 1)^2", "x^2 + 2*x + 1");

        assertEquals(MathematicalAlgorithmRegistry.ExecutionStatus.SUCCESS, result.status());
        assertEquals(MathematicalAlgorithmRegistry.ResultType.PROOF, result.resultType());
        assertEquals(MathematicalAlgorithmRegistry.POLYNOMIAL_EQUIVALENCE, result.payload().get("capability"));
    }

    @Test
    void routesIdealMembershipToGroebnerOrSingularByRegistry() {
        DomainAwareCasRouter groebnerRouter = new DomainAwareCasRouter(new DefaultMathematicalAlgorithmRegistry(
            Map.of(MathematicalAlgorithmRegistry.GROEBNER_BASIS, true), Map.of()));

        MathematicalAlgorithmRegistry.AlgorithmExecutionResult groebner =
            groebnerRouter.proveIdealMembership("x^2 - 1", List.of("x - 1"));

        assertEquals(MathematicalAlgorithmRegistry.ExecutionStatus.SUCCESS, groebner.status());
        assertEquals(MathematicalAlgorithmRegistry.ResultType.PROOF, groebner.resultType());

        DomainAwareCasRouter singularRouter = new DomainAwareCasRouter(new DefaultMathematicalAlgorithmRegistry(
            Map.of(MathematicalAlgorithmRegistry.SINGULAR_BACKEND, true), Map.of()));

        MathematicalAlgorithmRegistry.AlgorithmExecutionResult singular =
            singularRouter.proveIdealMembership("x", List.of("x"));

        assertEquals(MathematicalAlgorithmRegistry.ExecutionStatus.UNAVAILABLE, singular.status());
    }

    @Test
    void fallsBackToGroebnerWhenSingularIsUnavailableAndGroebnerIsEnabled() {
        DomainAwareCasRouter router = new DomainAwareCasRouter(
            new DefaultMathematicalAlgorithmRegistry(Map.of(
                MathematicalAlgorithmRegistry.SINGULAR_BACKEND, true,
                MathematicalAlgorithmRegistry.GROEBNER_BASIS, true
            ), Map.of()),
            new de.regelsuche.math.algorithms.equivalence.PolynomialNormalFormEquivalenceService(
                new DefaultMathematicalAlgorithmRegistry()),
            new de.regelsuche.math.algorithms.equivalence.GroebnerBasisEquivalenceService(
                new DefaultMathematicalAlgorithmRegistry(Map.of(MathematicalAlgorithmRegistry.GROEBNER_BASIS, true), Map.of())),
            (polynomialExpression, generatorExpressions, timeout) ->
                MathematicalAlgorithmRegistry.AlgorithmExecutionResult.unavailable("singular unavailable")
        );

        MathematicalAlgorithmRegistry.AlgorithmExecutionResult result =
            router.proveIdealMembership("x^2 - 1", List.of("x - 1"));

        assertEquals(MathematicalAlgorithmRegistry.ExecutionStatus.SUCCESS, result.status());
        assertEquals(MathematicalAlgorithmRegistry.ResultType.PROOF, result.resultType());
    }

    @Test
    void routesNumericRelationDiscoveryToPslqBackend() {
        DomainAwareCasRouter router = new DomainAwareCasRouter(new DefaultMathematicalAlgorithmRegistry(
            Map.of(
                MathematicalAlgorithmRegistry.NUMERIC_RELATION_SEARCH, true,
                MathematicalAlgorithmRegistry.PSLQ, true
            ),
            Map.of()
        ));

        MathematicalAlgorithmRegistry.AlgorithmExecutionResult result =
            router.discoverNumericRelation(List.of(Math.sqrt(2), Math.sqrt(8)));

        assertEquals(MathematicalAlgorithmRegistry.ExecutionStatus.SUCCESS, result.status());
        assertEquals(MathematicalAlgorithmRegistry.ResultType.HYPOTHESIS, result.resultType());
        assertEquals(List.of(2, -1), result.payload().get("coefficients"));
    }
}
