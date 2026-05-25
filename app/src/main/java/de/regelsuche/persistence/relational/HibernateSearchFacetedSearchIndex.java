package de.regelsuche.persistence.relational;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import org.hibernate.search.mapper.orm.Search;

public final class HibernateSearchFacetedSearchIndex extends HibernateEntityRepository<SearchIndexDocument>
    implements FacetedSearchIndex {

    private final EntityManagerFactory entityManagerFactory;

    public HibernateSearchFacetedSearchIndex(EntityManagerFactory entityManagerFactory) {
        super(entityManagerFactory, SearchIndexDocument.class);
        this.entityManagerFactory = entityManagerFactory;
    }

    @Override
    public void index(SearchIndexDocument document) {
        save(document);
    }

    @Override
    public List<SearchResult> search(SearchQuery query) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        try {
            List<SearchIndexDocument> hits = Search.session(entityManager)
                .search(SearchIndexDocument.class)
                .where(f -> f.bool(b -> {
                    if (!query.text().isBlank()) {
                        b.must(f.match().fields("title", "body", "facets").matching(query.text()));
                    }
                }))
                .fetchHits(Math.max(query.limit() * 4, 20));
            return hits.stream()
                .filter(document -> query.types().isEmpty() || query.types().contains(document.type()))
                .filter(document -> query.requiredFacets().stream().allMatch(document::hasFacet))
                .limit(query.limit())
                .map(hit -> new SearchResult(hit, 1.0))
                .toList();
        } finally {
            entityManager.close();
        }
    }
}
