package de.regelsuche.math;

import de.regelsuche.math.algorithms.registry.DefaultMathematicalAlgorithmRegistry;
import de.regelsuche.validation.MathematicalAlgorithmRegistry;
import java.util.HashMap;
import java.util.Map;

/**
 * App-layer wiring for mathematische Algorithmen. Reads config flags and
 * returns a registry without embedding algorithm logic in app.
 */
public final class MathematicalAlgorithmWiring {
    private static final String PREFIX = "regelsuche.math.";

    private MathematicalAlgorithmWiring() {
    }

    public static MathematicalAlgorithmRegistry fromSystemProperties() {
        Map<String, Boolean> enabled = new HashMap<>();
        enabled.put(MathematicalAlgorithmRegistry.POLYNOMIAL_EQUIVALENCE,
            readFlag("polynomialEquivalence.enabled"));
        enabled.put(MathematicalAlgorithmRegistry.GROEBNER_BASIS,
            readFlag("groebnerBasis.enabled"));
        enabled.put(MathematicalAlgorithmRegistry.SINGULAR_BACKEND,
            readFlag("singularBackend.enabled"));
        enabled.put(MathematicalAlgorithmRegistry.KNUTH_BENDIX,
            readFlag("knuthBendix.enabled"));
        enabled.put(MathematicalAlgorithmRegistry.CRITICAL_PAIRS,
            readFlag("criticalPairs.enabled"));
        enabled.put(MathematicalAlgorithmRegistry.PSLQ,
            readFlag("pslq.enabled"));
        enabled.put(MathematicalAlgorithmRegistry.NUMERIC_RELATION_SEARCH,
            readFlag("numericRelationSearch.enabled"));

        enabled.entrySet().removeIf(entry -> entry.getValue() == null);
        return new DefaultMathematicalAlgorithmRegistry(enabled, Map.of());
    }

    private static Boolean readFlag(String key) {
        String value = System.getProperty(PREFIX + key);
        if (value == null) {
            return null;
        }
        return Boolean.parseBoolean(value);
    }
}
