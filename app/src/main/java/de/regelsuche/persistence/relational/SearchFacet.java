package de.regelsuche.persistence.relational;

/** A normalized key/value facet used for PostgreSQL JSONB and in-memory filtering. */
public record SearchFacet(String key, String value) {
    public SearchFacet {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("facet key must not be blank");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("facet value must not be blank");
        }
        key = key.trim();
        value = value.trim();
    }
}
