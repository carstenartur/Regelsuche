package de.regelsuche.persistence.relational;

/** Ranked full-text/faceted-search hit. */
public record SearchResult(SearchIndexDocument document, double score) {
    public SearchResult {
        if (document == null) {
            throw new IllegalArgumentException("document must not be null");
        }
    }
}
