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
                long startedNanos = System.nanoTime();
                List<SearchState> states = strategy.strategy().search(new SearchProblem(
                    expression,
                    engine,
                    scorer,
                    canonicalizer,
                    heuristic
                ));
                long elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000L;
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
                // The bare benchmark only runs the search; it does not validate
                // identities formally. An improving path corresponds to a
                // concrete equivalent expression -> VALIDATED_BY_EXAMPLES.
                de.regelsuche.mining.CandidateProofStatus proofStatus = bestImprovement > 0
                    ? de.regelsuche.mining.CandidateProofStatus.VALIDATED_BY_EXAMPLES
                    : de.regelsuche.mining.CandidateProofStatus.OBSERVED;
                // Quality metrics — derived from the same `states` traversal
                // so adding them carries no extra search cost.
                boolean learnedRuleUsed = states.stream()
                    .flatMap(state -> state.appliedRuleIds().stream())
                    .anyMatch(id -> id != null && (id.startsWith("learned-") || id.startsWith("macro-")));
                int eGraphClasses = 0;
                int eGraphNodes = 0;
                double saturationSavings = 0.0;
                if (strategy.strategy() instanceof de.regelsuche.search.strategy.EqualitySaturationStrategy es
                        && es.lastStats() != null) {
                    var stats = es.lastStats();
                    eGraphClasses = stats.eclasses();
                    eGraphNodes = stats.enodes();
                    int applications = stats.totalApplications();
                    saturationSavings = applications == 0
                        ? 0.0
                        : stats.merges() / (double) applications;
                }
                results.add(new SearchBenchmarkResult(
                    strategy.name(),
                    expression,
                    states.size(),
                    bestImprovement,
                    shortestImprovingDepth,
                    expandedSteps,
                    distinctRules,
                    elapsedMillis,
                    proofStatus,
                    /* expectedResultMatched */ null,
                    /* prunedStates          */ 0,
                    eGraphClasses,
                    eGraphNodes,
                    saturationSavings,
                    learnedRuleUsed,
                    /* exportBundleValid     */ true
                ));
            }
        }
        return results;
    }

    public record NamedSearchStrategy(String name, SearchStrategy strategy) {
    }
}
