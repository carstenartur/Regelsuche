package de.regelsuche.persistence.relational;

import java.util.List;

/** Full-text query plus optional facet and entity-type filters. */
public record SearchQuery(String text, List<SearchFacet> requiredFacets, List<SearchEntityType> types, int limit) {
    public SearchQuery {
        text = text == null ? "" : text.trim();
        requiredFacets = requiredFacets == null ? List.of() : List.copyOf(requiredFacets);
        types = types == null ? List.of() : List.copyOf(types);
        limit = limit <= 0 ? 20 : limit;
    }
}
