package de.regelsuche.persistence.relational;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class InMemoryFacetedSearchIndexTest {

    @Test
    void searchesRulesHypothesesReportsSeedsAndBenchmarksWithFacets() {
        InMemoryFacetedSearchIndex index = new InMemoryFacetedSearchIndex();
        index.index(new SearchIndexDocument(
            SearchEntityType.RULE,
            "rule-1",
            "Quadratic expansion",
            "Expand (a+b)^2 into a^2 + 2ab + b^2",
            List.of(new SearchFacet("domain", "polynomial"), new SearchFacet("status", "validated")),
            Instant.EPOCH
        ));
        index.index(new SearchIndexDocument(
            SearchEntityType.SEED,
            "seed-1",
            "Linear seed",
            "Solve x + 3 = 5",
            List.of(new SearchFacet("domain", "equation")),
            Instant.EPOCH
        ));
        index.index(new SearchIndexDocument(
            SearchEntityType.BENCHMARK,
            "bench-1",
            "Polynomial benchmark",
            "Measures expansion and factoring quality",
            List.of(new SearchFacet("domain", "polynomial")),
            Instant.EPOCH
        ));

        List<SearchResult> results = index.search(new SearchQuery(
            "expansion",
            List.of(new SearchFacet("domain", "polynomial")),
            List.of(SearchEntityType.RULE, SearchEntityType.BENCHMARK),
            10
        ));

        assertEquals(List.of("rule-1", "bench-1"), results.stream()
            .map(result -> result.document().entityId())
            .toList());
    }

    @Test
    void emptyTextActsAsFacetedBrowseQuery() {
        InMemoryFacetedSearchIndex index = new InMemoryFacetedSearchIndex();
        index.index(new SearchIndexDocument(
            SearchEntityType.REPORT,
            "report-1",
            "Discovery report",
            "Transformation quality dashboard",
            List.of(new SearchFacet("experiment", "demo")),
            Instant.EPOCH
        ));

        List<SearchResult> results = index.search(new SearchQuery(
            "",
            List.of(new SearchFacet("experiment", "demo")),
            List.of(SearchEntityType.REPORT),
            5
        ));

        assertEquals(1, results.size());
        assertEquals(1.0, results.getFirst().score());
    }
}
