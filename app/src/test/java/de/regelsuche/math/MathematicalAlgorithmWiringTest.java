package de.regelsuche.math;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.validation.MathematicalAlgorithmRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MathematicalAlgorithmWiringTest {
    @Test
    void readsExplicitPropertiesMapForTestsAndCli() {
        MathematicalAlgorithmRegistry registry = MathematicalAlgorithmWiring.fromProperties(Map.of(
            "regelsuche.math.polynomialEquivalence.enabled", "false",
            "regelsuche.math.groebnerBasis.enabled", "true",
            "regelsuche.math.jasBackend.enabled", "true"
        ));

        assertFalse(registry.isEnabled(MathematicalAlgorithmRegistry.POLYNOMIAL_EQUIVALENCE));
        assertTrue(registry.isEnabled(MathematicalAlgorithmRegistry.GROEBNER_BASIS));
        assertTrue(registry.isEnabled(MathematicalAlgorithmRegistry.JAS_BACKEND));
    }

    @Test
    void explicitPropertiesOverrideSystemPropertiesAndEnvironment() {
        MathematicalAlgorithmRegistry registry = MathematicalAlgorithmWiring.fromSources(
            Map.of("regelsuche.math.groebnerBasis.enabled", "true"),
            Map.of("regelsuche.math.groebnerBasis.enabled", "false"),
            Map.of("REGELSUCHE_MATH_GROEBNER_BASIS_ENABLED", "false")
        );

        assertTrue(registry.isEnabled(MathematicalAlgorithmRegistry.GROEBNER_BASIS));
    }

    @Test
    void systemPropertiesOverrideEnvironment() {
        MathematicalAlgorithmRegistry registry = MathematicalAlgorithmWiring.fromSources(
            Map.of(),
            Map.of("regelsuche.math.jasBackend.enabled", "true"),
            Map.of("REGELSUCHE_MATH_JAS_BACKEND_ENABLED", "false")
        );

        assertTrue(registry.isEnabled(MathematicalAlgorithmRegistry.JAS_BACKEND));
    }

    @Test
    void environmentVariablesApplyWhenNoPropertyIsSet() {
        MathematicalAlgorithmRegistry registry = MathematicalAlgorithmWiring.fromSources(
            Map.of(),
            Map.of(),
            Map.of("REGELSUCHE_MATH_NUMERIC_RELATION_SEARCH_ENABLED", "true")
        );

        assertTrue(registry.isEnabled(MathematicalAlgorithmRegistry.NUMERIC_RELATION_SEARCH));
    }
}
