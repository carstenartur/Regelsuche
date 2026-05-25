package de.regelsuche.math;

import de.regelsuche.math.algorithms.registry.DefaultMathematicalAlgorithmRegistry;
import de.regelsuche.validation.MathematicalAlgorithmRegistry;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * App-layer wiring for mathematische Algorithmen. Runtime precedence is:
 * explicit properties map, JVM system properties, environment variables, registry defaults.
 */
public final class MathematicalAlgorithmWiring {
    private static final String PREFIX = "regelsuche.math.";
    private static final String ENV_PREFIX = "REGELSUCHE_MATH_";
    private static final Map<String, String> FLAG_KEYS = Map.of(
        MathematicalAlgorithmRegistry.POLYNOMIAL_EQUIVALENCE, "polynomialEquivalence.enabled",
        MathematicalAlgorithmRegistry.GROEBNER_BASIS, "groebnerBasis.enabled",
        MathematicalAlgorithmRegistry.JAS_BACKEND, "jasBackend.enabled",
        MathematicalAlgorithmRegistry.SINGULAR_BACKEND, "singularBackend.enabled",
        MathematicalAlgorithmRegistry.KNUTH_BENDIX, "knuthBendix.enabled",
        MathematicalAlgorithmRegistry.CRITICAL_PAIRS, "criticalPairs.enabled",
        MathematicalAlgorithmRegistry.PSLQ, "pslq.enabled",
        MathematicalAlgorithmRegistry.NUMERIC_RELATION_SEARCH, "numericRelationSearch.enabled"
    );

    private MathematicalAlgorithmWiring() {
    }

    public static MathematicalAlgorithmRegistry fromSystemProperties() {
        return fromSources(Map.of(), propertyMap(System.getProperties()), System.getenv());
    }

    public static MathematicalAlgorithmRegistry fromProperties(Map<String, String> properties) {
        return fromSources(properties, Map.of(), Map.of());
    }

    static MathematicalAlgorithmRegistry fromSources(Map<String, String> explicitProperties,
                                                     Map<String, String> systemProperties,
                                                     Map<String, String> environment) {
        Map<String, Boolean> enabled = new HashMap<>();
        FLAG_KEYS.forEach((algorithmId, key) -> enabled.put(algorithmId,
            readFlag(key, explicitProperties, systemProperties, environment)));

        enabled.entrySet().removeIf(entry -> entry.getValue() == null);
        return new DefaultMathematicalAlgorithmRegistry(enabled, Map.of());
    }

    private static Boolean readFlag(String key,
                                    Map<String, String> explicitProperties,
                                    Map<String, String> systemProperties,
                                    Map<String, String> environment) {
        String propertyKey = PREFIX + key;
        String value = firstNonNull(
            explicitProperties.get(propertyKey),
            explicitProperties.get(key),
            systemProperties.get(propertyKey),
            environment.get(envKey(key))
        );
        return value == null ? null : Boolean.parseBoolean(value);
    }

    private static String envKey(String key) {
        StringBuilder builder = new StringBuilder(ENV_PREFIX);
        for (int i = 0; i < key.length(); i++) {
            char character = key.charAt(i);
            if (character == '.') {
                builder.append('_');
            } else if (Character.isUpperCase(character)) {
                builder.append('_').append(character);
            } else {
                builder.append(Character.toUpperCase(character));
            }
        }
        return builder.toString();
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Map<String, String> propertyMap(Properties properties) {
        Map<String, String> values = new HashMap<>();
        properties.stringPropertyNames().forEach(name -> values.put(name, properties.getProperty(name)));
        return values;
    }
}
