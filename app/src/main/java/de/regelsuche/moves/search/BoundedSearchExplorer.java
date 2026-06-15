package de.regelsuche.moves.search;

import de.regelsuche.moves.search.SearchSuccessorGenerator.SearchSuccessorState;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Recursively explores the reachable search space from a given expression
 * within bounded depth and state limits, collecting structural metrics.
 *
 * <p>Uses {@link SearchSuccessorGenerator} to expand each state. No goal,
 * no heuristics, no ranking – pure recursive bounded exploration.</p>
 *
 * <p>A global visited set tracks unique expressions across all branches.
 * States encountered a second time are counted as duplicates and not
 * expanded further, which keeps the exploration acyclic.</p>
 */
public final class BoundedSearchExplorer {

    private final SearchSuccessorGenerator generator;

    public BoundedSearchExplorer() {
        this(new SearchSuccessorGenerator());
    }

    public BoundedSearchExplorer(SearchSuccessorGenerator generator) {
        this.generator = generator == null ? new SearchSuccessorGenerator() : generator;
    }

    /**
     * Explores the search space reachable from {@code expression} up to
     * {@code maxDepth} rewrite steps, visiting at most {@code maxStates}
     * states in total.
     *
     * @param expression the starting expression
     * @param maxDepth   maximum rewrite depth (clamped to &ge; 0)
     * @param maxStates  maximum number of states to explore (clamped to &ge; 1)
     * @return {@link ExplorationResult} with collected metrics
     */
    public ExplorationResult explore(String expression, int maxDepth, int maxStates) {
        if (expression == null || expression.isBlank()) {
            return ExplorationResult.empty();
        }
        int depth = Math.max(0, maxDepth);
        int budget = Math.max(1, maxStates);
        ExplorationAccumulator accum = new ExplorationAccumulator();
        recurse(expression.trim(), 0, depth, accum, budget);
        return accum.build();
    }

    private void recurse(
            String expression,
            int depth,
            int maxDepth,
            ExplorationAccumulator accum,
            int budget) {
        if (accum.exploredStates >= budget) {
            return;
        }
        accum.exploredStates++;
        if (!accum.visited.add(expression)) {
            accum.duplicateStates++;
            return;
        }
        accum.uniqueStates++;
        accum.growthPerDepth.merge(depth, 1, Integer::sum);
        if (depth >= maxDepth) {
            return;
        }
        List<SearchSuccessorState> successors = generator.generate(expression);
        int branchCount = successors.size();
        accum.totalExpanded++;
        accum.totalSuccessors += branchCount;
        if (branchCount > accum.maxBranchingFactor) {
            accum.maxBranchingFactor = branchCount;
        }
        for (SearchSuccessorState successor : successors) {
            if (accum.exploredStates >= budget) {
                break;
            }
            recurse(successor.successorExpression(), depth + 1, maxDepth, accum, budget);
        }
    }

    /**
     * Metrics collected during a bounded exploration run.
     *
     * @param exploredStates       total states processed (unique + duplicate)
     * @param uniqueStates         states with a previously unseen expression
     * @param duplicateStates      states whose expression had already been visited
     * @param maxBranchingFactor   largest successor count observed at any single state
     * @param averageBranchingFactor mean successor count over all expanded states
     * @param growthPerDepth       number of unique new states discovered at each depth level,
     *                             keyed by depth (0 = root)
     */
    public record ExplorationResult(
            int exploredStates,
            int uniqueStates,
            int duplicateStates,
            int maxBranchingFactor,
            double averageBranchingFactor,
            Map<Integer, Integer> growthPerDepth) {

        public ExplorationResult {
            growthPerDepth = growthPerDepth == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(growthPerDepth));
        }

        public static ExplorationResult empty() {
            return new ExplorationResult(0, 0, 0, 0, 0.0, Map.of());
        }
    }

    private static final class ExplorationAccumulator {
        int exploredStates;
        int uniqueStates;
        int duplicateStates;
        int maxBranchingFactor;
        int totalExpanded;
        int totalSuccessors;
        final LinkedHashSet<String> visited = new LinkedHashSet<>();
        final TreeMap<Integer, Integer> growthPerDepth = new TreeMap<>();

        ExplorationResult build() {
            double avg = totalExpanded > 0 ? (double) totalSuccessors / totalExpanded : 0.0;
            return new ExplorationResult(
                    exploredStates,
                    uniqueStates,
                    duplicateStates,
                    maxBranchingFactor,
                    avg,
                    new LinkedHashMap<>(growthPerDepth));
        }
    }
}
