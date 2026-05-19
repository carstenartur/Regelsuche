package de.regelsuche.benchmark;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.search.strategy.SearchStrategy;
import de.regelsuche.transform.TransformationEngine;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class SearchBenchmark {
    private final TransformationEngine engine;
    private final ExpressionScorer scorer;
    private final ExpressionCanonicalizer canonicalizer;
    private final SearchHeuristic heuristic;

    public SearchBenchmark(TransformationEngine engine, SearchHeuristic heuristic) {
        this.engine = engine;
        this.heuristic = heuristic;
        this.scorer = new ExpressionScorer();
        this.canonicalizer = new ExpressionCanonicalizer();
    }

    public List<SearchBenchmarkResult> run(List<String> expressions, List<NamedSearchStrategy> strategies) {
        List<SearchBenchmarkResult> results = new ArrayList<>();
        for (String expression : expressions) {
            for (NamedSearchStrategy strategy : strategies) {
                List<SearchState> states = strategy.strategy().search(new SearchProblem(
                    expression,
                    engine,
                    scorer,
                    canonicalizer,
                    heuristic
                ));
                int bestImprovement = states.stream().mapToInt(SearchState::improvement).max().orElse(0);
                int shortestImprovingDepth = states.stream()
                    .filter(state -> state.improvement() > 0)
                    .map(SearchState::depth)
                    .min(Comparator.naturalOrder())
                    .orElse(-1);
                int expandedSteps = states.stream().mapToInt(SearchState::expandedStepCount).max().orElse(0);
                int distinctRules = (int) states.stream()
                    .flatMap(state -> state.appliedRuleIds().stream())
                    .distinct()
                    .count();
                results.add(new SearchBenchmarkResult(
                    strategy.name(),
                    expression,
                    states.size(),
                    bestImprovement,
                    shortestImprovingDepth,
                    expandedSteps,
                    distinctRules
                ));
            }
        }
        return results;
    }

    public record NamedSearchStrategy(String name, SearchStrategy strategy) {
    }
}
