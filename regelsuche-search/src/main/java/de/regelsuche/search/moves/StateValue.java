package de.regelsuche.search.moves;

import java.util.Map;
import java.util.TreeMap;

/** Costed capability inspection, separate from move ranking and mathematical admission. */
@FunctionalInterface
public interface StateValue {
    Assessment evaluate(MoveState state, MoveContext context);
    record Capability(String providerId, String sourceExpression, String subtreePath, String matchedExpression, String rewrittenExpression) {
        public Capability {
            if (providerId == null || providerId.isBlank() || sourceExpression == null || subtreePath == null
                    || matchedExpression == null || rewrittenExpression == null || matchedExpression.equals(rewrittenExpression))
                throw new IllegalArgumentException("capability requires an executable non-identity witness");
        }
    }
    record Assessment(int complexity, double value, long searchWork, long primitiveWork, Map<String, Capability> capabilities) {
        public static final Assessment EMPTY = new Assessment(0, 0, 0, 0, Map.of());
        public Assessment {
            if (complexity < 0 || !Double.isFinite(value) || searchWork < 0 || primitiveWork < 0 || capabilities == null
                    || capabilities.entrySet().stream().anyMatch(entry -> entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null))
                throw new IllegalArgumentException("invalid state assessment");
            capabilities = java.util.Collections.unmodifiableMap(new TreeMap<>(capabilities));
        }
    }
    StateValue NONE = (state, context) -> Assessment.EMPTY;
}
