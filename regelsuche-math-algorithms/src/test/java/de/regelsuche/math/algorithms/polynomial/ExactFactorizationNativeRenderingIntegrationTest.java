package de.regelsuche.math.algorithms.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.polynomial.ExactFactorizationTransformationPipeline;
import de.regelsuche.polynomial.ExactParsedFactorizationPipeline;
import de.regelsuche.polynomial.FactorizationVerifier;
import org.junit.jupiter.api.Test;

class ExactFactorizationNativeRenderingIntegrationTest {
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void rendersAndReconstructsOneNativeExactRationalFactorization() {
        var source = parser.parseExactTerm("1/2*x^2 - 1/2");
        var factorization = new ExactParsedFactorizationPipeline().factor(
            source,
            NativeUnivariateFactorizationEngine.boundedRationals());
        var transformation =
            new ExactFactorizationTransformationPipeline()
                .transformRoot(source, factorization);

        assertTrue(factorization.executed(), factorization.detailCode());
        assertTrue(
            factorization.report().orElseThrow().successful(),
            factorization.report().orElseThrow().toString());
        assertTrue(transformation.transformed(), transformation.detailCode());
        assertEquals(
            ExactFactorizationTransformationPipeline.Kind
                .VERIFIED_DECOMPOSITION_WITH_COMPLETE_BACKEND_CLAIM,
            transformation.kind());
        assertEquals(
            FactorizationVerifier.ClaimStrength.BACKEND_CLAIMED_COMPLETE,
            factorization.report().orElseThrow().claimStrength());
        assertEquals(
            factorization.request().orElseThrow().source(),
            transformation.reconstruction().orElseThrow()
                .polynomial().orElseThrow());
        String expression = transformation.transformedExpression()
            .orElseThrow();
        assertTrue(expression.contains("1 / 2"), expression);
        assertTrue(expression.contains("x"), expression);
        assertTrue(
            transformation.totalWork().within(
                factorization.policy().maxTotalWorkUnits()));
        assertTrue(
            transformation.certificateHash().matches(
                "sha256:[0-9a-f]{64}"));
    }
}
