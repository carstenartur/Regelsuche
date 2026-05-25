package de.regelsuche.persistence.relational;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Lightweight demo-mode implementation of the same full-text/facet contract. */
public final class InMemoryFacetedSearchIndex implements FacetedSearchIndex {
    private final Map<String, SearchIndexDocument> documents = new ConcurrentHashMap<>();

    @Override
    public void index(SearchIndexDocument document) {
        documents.put(document.type() + ":" + document.entityId(), document);
    }

    @Override
    public List<SearchResult> search(SearchQuery query) {
        List<String> terms = tokenize(query.text());
        return documents.values().stream()
            .filter(document -> query.types().isEmpty() || query.types().contains(document.type()))
            .filter(document -> query.requiredFacets().stream().allMatch(document::hasFacet))
            .map(document -> new SearchResult(document, score(document, terms)))
            .filter(result -> terms.isEmpty() || result.score() > 0.0)
            .sorted(Comparator.comparingDouble(SearchResult::score).reversed()
                .thenComparing(result -> result.document().type().name())
                .thenComparing(result -> result.document().entityId()))
            .limit(query.limit())
            .toList();
    }

    private static double score(SearchIndexDocument document, List<String> terms) {
        if (terms.isEmpty()) {
            return 1.0;
        }
        String title = document.title().toLowerCase(Locale.ROOT);
        String body = document.body().toLowerCase(Locale.ROOT);
        double score = 0.0;
        for (String term : terms) {
            if (title.contains(term)) {
                score += 2.0;
            }
            if (body.contains(term)) {
                score += 1.0;
            }
        }
        return score;
    }

    private static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String[] rawTerms = text.toLowerCase(Locale.ROOT).split("[^\\p{Alnum}]+", -1);
        List<String> terms = new ArrayList<>();
        for (String term : rawTerms) {
            if (!term.isBlank()) {
                terms.add(term);
            }
        }
        return terms;
    }
}
