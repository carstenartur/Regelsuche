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
            int pageSize = Math.max(query.limit() * 4, 20);
            int offset = 0;
            java.util.ArrayList<SearchResult> results = new java.util.ArrayList<>();
            while (results.size() < query.limit()) {
                List<SearchIndexDocument> hits = Search.session(entityManager)
                    .search(SearchIndexDocument.class)
                    .where(f -> f.bool(b -> {
                        if (!query.text().isBlank()) {
                            b.must(f.match().fields("title", "body", "facets").matching(query.text()));
                        }
                    }))
                    .fetchHits(offset, pageSize);
                if (hits.isEmpty()) {
                    break;
                }
                for (SearchIndexDocument hit : hits) {
                    if (!query.types().isEmpty() && !query.types().contains(hit.type())) {
                        continue;
                    }
                    if (!query.requiredFacets().stream().allMatch(hit::hasFacet)) {
                        continue;
                    }
                    results.add(new SearchResult(hit, 1.0));
                    if (results.size() >= query.limit()) {
                        break;
                    }
                }
                if (hits.size() < pageSize) {
                    break;
                }
                offset += pageSize;
            }
            return List.copyOf(results);
        } finally {
            entityManager.close();
        }
    }
}
