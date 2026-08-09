package de.regelsuche.search;

import de.regelsuche.scoring.cost.TransformationGoal;
import de.regelsuche.search.strategy.AStarSearchStrategy;
import de.regelsuche.search.strategy.BeamSearchStrategy;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.EqualitySaturationStrategy;
import de.regelsuche.search.strategy.HybridSearchStrategy;
import de.regelsuche.search.strategy.MonteCarloTreeSearchStrategy;
import de.regelsuche.search.strategy.SearchStrategy;
import de.regelsuche.search.strategy.StructuralDiversitySearchStrategy;

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
    FAST_SIMPLIFY(new SearchHeuristic(4, 200, 1, 2, 32, 4), TransformationGoal.SIMPLIFY) {
        @Override
        public SearchStrategy newStrategy() {
            return new BestFirstSearchStrategy();
        }
    },
    /** Wide search optimised for discovering new rule candidates. */
    DISCOVERY(new SearchHeuristic(6, 1500, 1, 6, 80, 16), TransformationGoal.SIMPLIFY) {
        @Override
        public SearchStrategy newStrategy() {
            return new HybridSearchStrategy();
        }
    },
    /**
     * Target-blind quality-diversity control retaining one elite per frozen
     * structural cell instead of collapsing every survivor into one scalar
     * ranking.
     */
    DIVERSITY_DISCOVERY(new SearchHeuristic(6, 2000, 1, 6, 80, 24), TransformationGoal.SIMPLIFY) {
        @Override
        public SearchStrategy newStrategy() {
            return new StructuralDiversitySearchStrategy();
        }
    },
    /** Mid-sized search that prefers small, easy to explain steps. */
    TEACHING(new SearchHeuristic(5, 600, 1, 3, 40, 8), TransformationGoal.TEACHING_FRIENDLY) {
        @Override
        public SearchStrategy newStrategy() {
            return new BeamSearchStrategy();
        }
    },
    /** A* driven search that prefers paths plausibly leading to a proof. */
    PROOF_ORIENTED(new SearchHeuristic(8, 2000, 1, 4, 60, 8), TransformationGoal.PROOF_FRIENDLY) {
        @Override
        public SearchStrategy newStrategy() {
            return new AStarSearchStrategy();
        }
    },
    /** Exhaustive randomised search for small expressions. */
    EXHAUSTIVE_SMALL(new SearchHeuristic(5, 3000, 1, 6, 80, 24), TransformationGoal.SIMPLIFY) {
        @Override
        public SearchStrategy newStrategy() {
            return new MonteCarloTreeSearchStrategy(42L);
        }
    },
    /**
     * Search-intelligence profile: activates the mathematical transposition
     * table, keeps non-improving paths that bring new rule combinations and
     * applies stricter cycle detection. Used to demonstrate learning
     * macro-rules across multiple runs.
     */
    DISCOVERY_PLUS(new SearchHeuristic(6, 2000, 1, 6, 80, 16), TransformationGoal.SIMPLIFY) {
        @Override
        public SearchStrategy newStrategy() {
            return new BestFirstSearchStrategy();
        }

        @Override
        public boolean usesTranspositionTable() {
            return true;
        }
    },
    /**
     * Equality-saturation profile: builds an {@link
     * de.regelsuche.egraph.EGraph} of the input, applies every rewrite
     * rule egg-style until fix-point or budget, then extracts the
     * cheapest representative. Unlike the path-based strategies, every
     * order of rewrites collapses into a single shared graph — no
     * combinatorial explosion of permutations. Surfaces detailed
     * {@link de.regelsuche.egraph.SaturationStats} via
     * {@link EqualitySaturationStrategy#lastStats()}.
     */
    EQUALITY_SATURATION(new SearchHeuristic(4, 100, 1, 6, 200, 32), TransformationGoal.SIMPLIFY) {
        @Override
        public SearchStrategy newStrategy() {
            return new EqualitySaturationStrategy();
        }
    };

    private final SearchHeuristic heuristic;
    private final TransformationGoal defaultGoal;

    SearchProfile(SearchHeuristic heuristic, TransformationGoal defaultGoal) {
        this.heuristic = heuristic;
        this.defaultGoal = defaultGoal;
    }

    public SearchHeuristic heuristic() {
        return heuristic;
    }

    /**
     * Default {@link TransformationGoal} for this profile. The UI may
     * override this with an explicit selection from the goal dropdown.
     */
    public TransformationGoal defaultGoal() {
        return defaultGoal;
    }

    public abstract SearchStrategy newStrategy();

    /**
     * Whether this profile activates the mathematical transposition table by
     * default. Only {@link #DISCOVERY_PLUS} returns {@code true}; the other
     * profiles preserve their pre-PR behaviour so existing tests stay green.
     */
    public boolean usesTranspositionTable() {
        return false;
    }
}
