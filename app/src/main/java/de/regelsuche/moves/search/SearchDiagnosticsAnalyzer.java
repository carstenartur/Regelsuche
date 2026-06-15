package de.regelsuche.moves.search;

import de.regelsuche.moves.search.BoundedSearchExplorer.ExplorationResult;
import java.util.Map;

/**
 * Computes Phase-3 search diagnostics from a bounded exploration of the
 * reachable search space.
 *
 * <p>Delegates exploration to {@link BoundedSearchExplorer} and derives
 * the following metrics:</p>
 * <ul>
 *   <li><b>reachableStates</b> – unique expressions reachable from the root</li>
 *   <li><b>duplicateRate</b> – fraction of explored states that were already seen</li>
 *   <li><b>cycleCount</b> – DFS back-edges (successor points back to an ancestor in the
 *       current path)</li>
 *   <li><b>cycleRate</b> – fraction of successor edges that are back-edges</li>
 *   <li><b>deadEnds</b> – unique states fully expanded with zero successors</li>
 *   <li><b>averageDepth</b> – depth-weighted mean over all unique discovered states</li>
 * </ul>
 */
public final class SearchDiagnosticsAnalyzer {

    /** Default exploration depth used by the no-argument {@link #analyze(String)} overload. */
    public static final int DEFAULT_MAX_DEPTH = 4;

    /** Default state budget used by the no-argument {@link #analyze(String)} overload. */
    public static final int DEFAULT_MAX_STATES = 500;

    private final BoundedSearchExplorer explorer;

    public SearchDiagnosticsAnalyzer() {
        this(new BoundedSearchExplorer());
    }

    public SearchDiagnosticsAnalyzer(BoundedSearchExplorer explorer) {
        this.explorer = explorer == null ? new BoundedSearchExplorer() : explorer;
    }

    /**
     * Analyses the search space reachable from {@code expression} using
     * {@link #DEFAULT_MAX_DEPTH} and {@link #DEFAULT_MAX_STATES}.
     *
     * @param expression the starting expression
     * @return a {@link DiagnosticsReport} with Phase-3 metrics
     */
    public DiagnosticsReport analyze(String expression) {
        return analyze(expression, DEFAULT_MAX_DEPTH, DEFAULT_MAX_STATES);
    }

    /**
     * Analyses the search space reachable from {@code expression} up to
     * {@code maxDepth} rewrite steps, visiting at most {@code maxStates} states.
     *
     * @param expression the starting expression
     * @param maxDepth   maximum rewrite depth (passed through to the explorer)
     * @param maxStates  maximum state budget (passed through to the explorer)
     * @return a {@link DiagnosticsReport} with Phase-3 metrics
     */
    public DiagnosticsReport analyze(String expression, int maxDepth, int maxStates) {
        ExplorationResult result = explorer.explore(expression, maxDepth, maxStates);
        double duplicateRate = result.exploredStates() > 0
                ? (double) result.duplicateStates() / result.exploredStates()
                : 0.0;
        double averageDepth = computeAverageDepth(result.growthPerDepth());
        return new DiagnosticsReport(
                result.uniqueStates(),
                duplicateRate,
                result.cycleCount(),
                result.cycleRate(),
                result.deadEndCount(),
                averageDepth);
    }

    private static double computeAverageDepth(Map<Integer, Integer> growthPerDepth) {
        long weightedSum = 0;
        int total = 0;
        for (Map.Entry<Integer, Integer> entry : growthPerDepth.entrySet()) {
            weightedSum += (long) entry.getKey() * entry.getValue();
            total += entry.getValue();
        }
        return total > 0 ? (double) weightedSum / total : 0.0;
    }

    /**
     * Phase-3 search diagnostics.
     *
     * @param reachableStates unique expressions discovered during exploration
     * @param duplicateRate   fraction of explored states that were already seen
     *                        ({@code duplicateStates / exploredStates})
     * @param cycleCount      number of DFS back-edges detected
     * @param cycleRate       fraction of successor edges that are back-edges
     *                        ({@code cycleCount / totalEdges})
     * @param deadEnds        unique states fully expanded with zero successors
     * @param averageDepth    depth-weighted mean of all unique discovered states
     */
    public record DiagnosticsReport(
            int reachableStates,
            double duplicateRate,
            int cycleCount,
            double cycleRate,
            int deadEnds,
            double averageDepth) {

        public DiagnosticsReport {
            reachableStates = Math.max(0, reachableStates);
            duplicateRate = Math.max(0d, Math.min(1d, duplicateRate));
            cycleCount = Math.max(0, cycleCount);
            cycleRate = Math.max(0d, Math.min(1d, cycleRate));
            deadEnds = Math.max(0, deadEnds);
            averageDepth = Math.max(0d, averageDepth);
        }
    }
}
