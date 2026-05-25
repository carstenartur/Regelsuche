package de.regelsuche.persistence.relational;

import java.util.List;

/** Search port shared by demo JSON mode and PostgreSQL/Hibernate Search mode. */
public interface FacetedSearchIndex {
    void index(SearchIndexDocument document);

    List<SearchResult> search(SearchQuery query);
}
