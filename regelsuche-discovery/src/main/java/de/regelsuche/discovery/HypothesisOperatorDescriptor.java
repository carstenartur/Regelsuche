package de.regelsuche.discovery;

import de.regelsuche.transform.HypothesisOperator;
import java.util.List;
import java.util.function.IntFunction;

/** Metadata and factory for a registered hypothesis operator. */
public record HypothesisOperatorDescriptor(
    String id,
    String displayName,
    String family,
    IntFunction<HypothesisOperator> factory,
    boolean defaultEnabled,
    List<String> tags
) {
    public HypothesisOperatorDescriptor {
        if (id == null || id.isBlank() || factory == null) {
            throw new IllegalArgumentException("id and factory are required");
        }
        displayName = displayName == null || displayName.isBlank() ? id : displayName;
        family = family == null || family.isBlank() ? "general" : family;
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    public HypothesisOperator create(int maxCandidates) {
        return factory.apply(maxCandidates);
    }
}
