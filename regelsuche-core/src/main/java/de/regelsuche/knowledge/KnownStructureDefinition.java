package de.regelsuche.knowledge;

import de.regelsuche.transform.ExprMatcher;
import java.util.List;
import java.util.Objects;

/** Pack-neutral definition consumed by the representation-discovery catalog. */
public record KnownStructureDefinition(
    String id,
    String domainId,
    ExprMatcher matcher,
    List<String> requiredAssumptions,
    List<String> consequenceIds,
    KnownStructureMetadata metadata
) {
    public KnownStructureDefinition {
        id = KnownStructureMetadata.requireText(id, "id");
        domainId = KnownStructureMetadata.requireText(domainId, "domainId");
        matcher = Objects.requireNonNull(matcher, "matcher");
        requiredAssumptions = KnownStructureMetadata.sortedUnique(
            requiredAssumptions, "requiredAssumptions");
        consequenceIds = KnownStructureMetadata.sortedUnique(
            consequenceIds, "consequenceIds");
        if (consequenceIds.isEmpty()) {
            throw new IllegalArgumentException(
                "known structure must declare at least one concrete consequence");
        }
        metadata = Objects.requireNonNull(metadata, "metadata");
    }
}
