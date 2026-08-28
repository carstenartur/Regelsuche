package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PolynomialTheoryTransformationEngineTest {
    private static final String SOURCE = "x^4 + 4*y^4";
    private static final String TARGET =
        "(x^2 - 2*x*y + 2*y^2) * (x^2 + 2*x*y + 2*y^2)";

    @Test
    void noFactorizationLeavesBaseEngineUntouched() {
        TransformationEngine base = expression -> List.of(
            new Transformation("base", "x"));

        List<Transformation> result =
            new PolynomialTheoryTransformationEngine(
                base,
                PolynomialTheoryTransformationEngine.Policy.NO_FACTORIZATION)
                .transform(SOURCE);

        assertEquals(1, result.size());
        assertEquals("base", result.getFirst().rule());
    }

    @Test
    void onDemandVerificationRetainsAndCacheReplayKeepsIdentity() {
        PolynomialDerivedMacroCache cache = new PolynomialDerivedMacroCache(4);
        PolynomialTheoryTransformationEngine onDemand =
            new PolynomialTheoryTransformationEngine(
                expression -> List.of(),
                PolynomialTheoryTransformationEngine.Policy
                    .ON_DEMAND_VERIFIED_FACTORIZATION,
                new PolynomialDecompositionSynthesisOperator(Integer.MAX_VALUE),
                cache);

        Transformation generated = onDemand.transform(SOURCE).stream()
            .filter(candidate -> candidate.kind() == RewriteKind.FACTOR)
            .findFirst()
            .orElseThrow();
        assertEquals(1, cache.size());

        PolynomialTheoryTransformationEngine replay =
            new PolynomialTheoryTransformationEngine(
                expression -> List.of(),
                PolynomialTheoryTransformationEngine.Policy
                    .VERIFIED_DERIVED_MACRO_CACHE,
                new PolynomialDecompositionSynthesisOperator(),
                cache);
        Transformation cached = replay.transform(SOURCE).stream()
            .findFirst()
            .orElseThrow();

        assertEquals(generated.transformedExpression(), cached.transformedExpression());
        assertEquals(generated.applicationKey(), cached.applicationKey());
        assertTrue(cached.primitiveStepCount() > 0);
    }

    @Test
    void observedIdentityIsClassifiedBeforeCacheInsertion() {
        PolynomialTheoryTransformationEngine engine =
            new PolynomialTheoryTransformationEngine(
                expression -> List.of(),
                PolynomialTheoryTransformationEngine.Policy.NO_FACTORIZATION);

        PolynomialTheorySubsumptionClassifier.Classification result =
            engine.observe(
                SOURCE,
                TARGET,
                List.of("mined-step"),
                List.of("path:held-out-observation"));

        assertTrue(result.subsumed());
        assertEquals(1, engine.cache().size());
        assertEquals(
            List.of("mined-step"),
            engine.cache().entries().getFirst().lineages().getFirst()
                .primitiveRuleIds());
    }
}
