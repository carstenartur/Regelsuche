package de.regelsuche.search.strategy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Hybrid strategy that combines complementary search strategies and unions
 * their explored states.
 *
 * <p>Defaults to running {@link BestFirstSearchStrategy} followed by
 * {@link BeamSearchStrategy}; callers can supply any list of strategies. States
 * are deduplicated only when the complete immutable state, including retained
 * lineage and assumptions, agrees. Equal values are not necessarily equal evidence.</p>
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
        Set<SearchState> deduplicated = new LinkedHashSet<>();
        for (SearchStrategy strategy : strategies) {
            deduplicated.addAll(strategy.search(problem));
        }
        return new ArrayList<>(deduplicated);
    }
}
