package de.regelsuche.search;

import de.regelsuche.search.strategy.AStarSearchStrategy;
import de.regelsuche.search.strategy.BeamSearchStrategy;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.HybridSearchStrategy;
import de.regelsuche.search.strategy.MonteCarloTreeSearchStrategy;
import de.regelsuche.search.strategy.SearchStrategy;

/**
 * Pre-defined search profiles bundling a {@link SearchHeuristic} preset and a
 * matching {@link SearchStrategy}.
 *
 * <p>The profiles exist so callers can pick a high-level intent (fast
 * simplification, discovery, teaching, exhaustive small searches, ...) instead
 * of tuning individual heuristic knobs.</p>
 */
public enum SearchProfile {
    /** Quick, depth-limited search aimed at returning a result fast. */
    FAST_SIMPLIFY(new SearchHeuristic(4, 200, 1, 2, 32, 4)) {
        @Override
        public SearchStrategy newStrategy() {
            return new BestFirstSearchStrategy();
        }
    },
    /** Wide search optimised for discovering new rule candidates. */
    DISCOVERY(new SearchHeuristic(6, 1500, 1, 6, 80, 16)) {
        @Override
        public SearchStrategy newStrategy() {
            return new HybridSearchStrategy();
        }
    },
    /** Mid-sized search that prefers small, easy to explain steps. */
    TEACHING(new SearchHeuristic(5, 600, 1, 3, 40, 8)) {
        @Override
        public SearchStrategy newStrategy() {
            return new BeamSearchStrategy();
        }
    },
    /** A* driven search that prefers paths plausibly leading to a proof. */
    PROOF_ORIENTED(new SearchHeuristic(8, 2000, 1, 4, 60, 8)) {
        @Override
        public SearchStrategy newStrategy() {
            return new AStarSearchStrategy();
        }
    },
    /** Exhaustive randomised search for small expressions. */
    EXHAUSTIVE_SMALL(new SearchHeuristic(5, 3000, 1, 6, 80, 24)) {
        @Override
        public SearchStrategy newStrategy() {
            return new MonteCarloTreeSearchStrategy(42L);
        }
    };

    private final SearchHeuristic heuristic;

    SearchProfile(SearchHeuristic heuristic) {
        this.heuristic = heuristic;
    }

    public SearchHeuristic heuristic() {
        return heuristic;
    }

    public abstract SearchStrategy newStrategy();
}
