package de.regelsuche.search.strategy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hybrid strategy that combines complementary search strategies and unions
 * their explored states.
 *
 * <p>Defaults to running {@link BestFirstSearchStrategy} followed by
 * {@link BeamSearchStrategy}; callers can supply any list of strategies. States
 * are deduplicated by canonical hash + applied rule applications so a state
 * discovered by multiple strategies is only returned once.</p>
 */
public class HybridSearchStrategy implements SearchStrategy {
    private final List<SearchStrategy> strategies;

    public HybridSearchStrategy() {
        this(List.of(new BestFirstSearchStrategy(), new BeamSearchStrategy()));
    }

    public HybridSearchStrategy(List<SearchStrategy> strategies) {
        if (strategies == null || strategies.isEmpty()) {
            throw new IllegalArgumentException("hybrid strategies must not be empty");
        }
        this.strategies = List.copyOf(strategies);
    }

    @Override
    public List<SearchState> search(SearchProblem problem) {
        Map<String, SearchState> deduplicated = new LinkedHashMap<>();
        for (SearchStrategy strategy : strategies) {
            for (SearchState state : strategy.search(problem)) {
                deduplicated.putIfAbsent(
                    state.canonicalHash() + ":" + state.appliedRuleApplications(),
                    state
                );
            }
        }
        return new ArrayList<>(deduplicated.values());
    }
}
