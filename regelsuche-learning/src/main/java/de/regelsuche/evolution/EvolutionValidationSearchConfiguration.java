package de.regelsuche.evolution;

import java.util.Map;
import java.util.TreeMap;

/** Bounded search parameters selected together with an evolution genome. */
public record EvolutionValidationSearchConfiguration(
    int maxDepth,
    int maxExpandedStates,
    int maxCandidatesPerState
) {
    public EvolutionValidationSearchConfiguration {
        if (maxDepth < 1 || maxExpandedStates < 1
                || maxCandidatesPerState < 1) {
            throw new IllegalArgumentException(
                "search configuration limits must be positive");
        }
    }

    Map<String, Object> canonicalMaterial() {
        Map<String, Object> value = new TreeMap<>();
        value.put("maxCandidatesPerState", maxCandidatesPerState);
        value.put("maxDepth", maxDepth);
        value.put("maxExpandedStates", maxExpandedStates);
        return value;
    }
}
