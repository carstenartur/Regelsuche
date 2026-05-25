package de.regelsuche.persistence.relational;

import java.time.Instant;
import java.util.List;

/** Portable document mirrored into Hibernate Search / PostgreSQL full-text indexes. */
public record SearchIndexDocument(
    SearchEntityType type,
    String entityId,
    String title,
    String body,
    List<SearchFacet> facets,
    Instant updatedAt
) {
    public SearchIndexDocument {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (entityId == null || entityId.isBlank()) {
            throw new IllegalArgumentException("entityId must not be blank");
        }
        title = title == null ? "" : title;
        body = body == null ? "" : body;
        facets = facets == null ? List.of() : List.copyOf(facets);
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }

    public boolean hasFacet(SearchFacet facet) {
        return facets.contains(facet);
    }
}
