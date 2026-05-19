package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.AStarSearchStrategy;
import de.regelsuche.search.strategy.BeamSearchStrategy;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.RandomMonteCarloSearchStrategy;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchBenchmarkTest {
    @Test
    void benchmarksSearchQualityAndExplosionMetrics() {
        SearchBenchmark benchmark = new SearchBenchmark(
            new AstRewriteTransformationEngine(),
            new SearchHeuristic(4, 100, 1, 3, 50, 8)
        );

        List<SearchBenchmarkResult> results = benchmark.run(
            List.of("(x + 0) * 1"),
            List.of(
                new SearchBenchmark.NamedSearchStrategy("best-first", new BestFirstSearchStrategy()),
                new SearchBenchmark.NamedSearchStrategy("beam", new BeamSearchStrategy()),
                new SearchBenchmark.NamedSearchStrategy("astar", new AStarSearchStrategy()),
                new SearchBenchmark.NamedSearchStrategy("random", new RandomMonteCarloSearchStrategy(1))
            )
        );

        assertEquals(4, results.size());
        assertTrue(results.stream().allMatch(result -> result.exploredStates() > 0));
        assertTrue(results.stream().anyMatch(result -> result.bestImprovement() > 0));
    }
}
