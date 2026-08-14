package de.regelsuche.knowledge;

import de.regelsuche.transform.ExprMatcher;
import java.util.List;
import java.util.Objects;

/** Pack-neutral known form consumed by representation discovery. */
public record KnownStructureDefinition(
    String id, String domainId, ExprMatcher matcher,
    List<String> requiredAssumptions, List<String> consequenceIds,
    KnownStructureMetadata metadata
) {
    public KnownStructureDefinition {
        if (id == null || id.isBlank() || domainId == null || domainId.isBlank()) {
            throw new IllegalArgumentException("id and domainId are required");
        }
        matcher = Objects.requireNonNull(matcher);
        requiredAssumptions = List.copyOf(requiredAssumptions);
        consequenceIds = List.copyOf(consequenceIds);
        if (consequenceIds.isEmpty()) {
            throw new IllegalArgumentException("consequenceIds are required");
        }
        metadata = Objects.requireNonNull(metadata);
    }
}
