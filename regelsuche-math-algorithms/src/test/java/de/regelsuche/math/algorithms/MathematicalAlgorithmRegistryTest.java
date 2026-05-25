package de.regelsuche.math.algorithms;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.math.algorithms.registry.DefaultMathematicalAlgorithmRegistry;
import de.regelsuche.validation.MathematicalAlgorithmRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MathematicalAlgorithmRegistryTest {
    @Test
    void defaultsEnableOnlyCheapDeterministicAlgorithms() {
        MathematicalAlgorithmRegistry registry = new DefaultMathematicalAlgorithmRegistry();

        assertTrue(registry.isEnabled(MathematicalAlgorithmRegistry.POLYNOMIAL_EQUIVALENCE));
        assertTrue(registry.isEnabled(MathematicalAlgorithmRegistry.CRITICAL_PAIRS));
        assertFalse(registry.isEnabled(MathematicalAlgorithmRegistry.GROEBNER_BASIS));
        assertFalse(registry.isEnabled(MathematicalAlgorithmRegistry.KNUTH_BENDIX));
        assertFalse(registry.isEnabled(MathematicalAlgorithmRegistry.PSLQ));
        assertFalse(registry.isEnabled(MathematicalAlgorithmRegistry.SINGULAR_BACKEND));
    }

    @Test
    void registryReportsExplicitOverrides() {
        MathematicalAlgorithmRegistry registry = new DefaultMathematicalAlgorithmRegistry(
            Map.of(
                MathematicalAlgorithmRegistry.GROEBNER_BASIS, true,
                MathematicalAlgorithmRegistry.PSLQ, true,
                MathematicalAlgorithmRegistry.NUMERIC_RELATION_SEARCH, true
            ),
            Map.of()
        );

        assertTrue(registry.isEnabled(MathematicalAlgorithmRegistry.GROEBNER_BASIS));
        assertTrue(registry.isEnabled(MathematicalAlgorithmRegistry.PSLQ));
        assertTrue(registry.isEnabled(MathematicalAlgorithmRegistry.NUMERIC_RELATION_SEARCH));
    }
}
