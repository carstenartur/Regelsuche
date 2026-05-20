package de.regelsuche.benchmark;

import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.SearchProfile;
import de.regelsuche.search.strategy.AStarSearchStrategy;
import de.regelsuche.search.strategy.BeamSearchStrategy;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.HybridSearchStrategy;
import de.regelsuche.search.strategy.MonteCarloTreeSearchStrategy;
import de.regelsuche.search.strategy.RandomMonteCarloSearchStrategy;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import java.util.ArrayList;
import java.util.List;

/**
 * Collection of opinionated benchmark scenarios.
 *
 * <p>Each scenario exercises {@link SearchBenchmark} with a representative set
 * of expressions and a fixed list of {@link de.regelsuche.search.strategy.SearchStrategy}
 * implementations so different runs are comparable. Scenarios are intentionally
 * small enough to finish in a few seconds.</p>
 */
public final class BenchmarkSuite {

    public List<BenchmarkSuiteResult> runAll() {
        List<BenchmarkSuiteResult> all = new ArrayList<>();
        all.add(run("known-identities", List.of(
            "x + 0",
            "x * 1",
            "x * 0",
            "a + a"
        ), SearchProfile.FAST_SIMPLIFY.heuristic()));
        all.add(run("polynomial-simplification", List.of(
            "(x + 1)*(x + 1)",
            "x*x + x*x",
            "(x + 2)*(x + 3)",
            "(x + a)^2"
        ), SearchProfile.DISCOVERY.heuristic()));
        all.add(run("rational-simplification", List.of(
            "(x*y)/(x*z)",
            "(a/b)*(c/d)",
            "a/(b/c)"
        ), SearchProfile.TEACHING.heuristic()));
        all.add(run("search-explosion", List.of(
            "(x + a)*(x + b)*(x + c)"
        ), SearchProfile.EXHAUSTIVE_SMALL.heuristic()));
        return all;
    }

    public BenchmarkSuiteResult run(String name, List<String> expressions, SearchHeuristic heuristic) {
        SearchBenchmark benchmark = new SearchBenchmark(new AstRewriteTransformationEngine(), heuristic);
        List<SearchBenchmark.NamedSearchStrategy> strategies = List.of(
            new SearchBenchmark.NamedSearchStrategy("best-first", new BestFirstSearchStrategy()),
            new SearchBenchmark.NamedSearchStrategy("beam", new BeamSearchStrategy()),
            new SearchBenchmark.NamedSearchStrategy("a-star", new AStarSearchStrategy()),
            new SearchBenchmark.NamedSearchStrategy("random-mc", new RandomMonteCarloSearchStrategy(7L)),
            new SearchBenchmark.NamedSearchStrategy("mcts", new MonteCarloTreeSearchStrategy(7L)),
            new SearchBenchmark.NamedSearchStrategy("hybrid", new HybridSearchStrategy())
        );
        return new BenchmarkSuiteResult(name, benchmark.run(expressions, strategies));
    }

    public record BenchmarkSuiteResult(String name, List<SearchBenchmarkResult> results) {
        public BenchmarkSuiteResult {
            results = List.copyOf(results);
        }
    }
}
