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

/** Search profiles bind a heuristic preset, strategy and default goal. */
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
    /** Target-blind one-elite-per-structural-cell diagnostic. */
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
    /** Best-first discovery with the mathematical transposition table. */
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
    /** Equality saturation followed by cheapest-representative extraction. */
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

    public TransformationGoal defaultGoal() {
        return defaultGoal;
    }

    public abstract SearchStrategy newStrategy();

    public boolean usesTranspositionTable() {
        return false;
    }
}
