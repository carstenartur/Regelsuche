package de.regelsuche.moves.search;

import de.regelsuche.moves.search.SearchSuccessorGenerator.SearchSuccessorState;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Phase-4 Rule Impact Analysis: measures the contribution of each individual
 * rewrite rule during a bounded depth-first exploration of the search space.
 *
 * <p>For every rule encountered during exploration the following per-rule
 * metrics are collected:</p>
 * <ul>
 *   <li><b>successorsGenerated</b> – total successor edges produced by this rule</li>
 *   <li><b>duplicatesGenerated</b> – successor edges that reached an already-visited state</li>
 *   <li><b>cyclesGenerated</b> – successor edges that were DFS back-edges (point back to an
 *       ancestor in the current path)</li>
 *   <li><b>uniqueStatesAdded</b> – successor edges that discovered a previously unseen state
 *       ({@code successorsGenerated == uniqueStatesAdded + duplicatesGenerated + cyclesGenerated})</li>
 *   <li><b>averageReductionImpact</b> – mean character-length delta
 *       ({@code sourceLength − successorLength}) per generated successor; positive means
 *       the rule shrinks expressions on average</li>
 * </ul>
 */
public final class RuleImpactAnalyzer {

    /** Default exploration depth for the no-argument {@link #analyze(String)} overload. */
    public static final int DEFAULT_MAX_DEPTH = 4;

    /** Default state budget for the no-argument {@link #analyze(String)} overload. */
    public static final int DEFAULT_MAX_STATES = 500;

    private final Function<String, List<SearchSuccessorState>> successorSupplier;

    public RuleImpactAnalyzer() {
        this(new SearchSuccessorGenerator());
    }

    public RuleImpactAnalyzer(SearchSuccessorGenerator generator) {
        SearchSuccessorGenerator g = generator == null ? new SearchSuccessorGenerator() : generator;
        this.successorSupplier = g::generate;
    }

    RuleImpactAnalyzer(Function<String, List<SearchSuccessorState>> successorSupplier) {
        this.successorSupplier = successorSupplier == null ? __ -> List.of() : successorSupplier;
    }

    /**
     * Analyses rule impact using {@link #DEFAULT_MAX_DEPTH} and {@link #DEFAULT_MAX_STATES}.
     *
     * @param expression the starting expression
     * @return a {@link RuleImpactReport} with per-rule metrics
     */
    public RuleImpactReport analyze(String expression) {
        return analyze(expression, DEFAULT_MAX_DEPTH, DEFAULT_MAX_STATES);
    }

    /**
     * Analyses rule impact up to {@code maxDepth} rewrite steps visiting at most
     * {@code maxStates} states.
     *
     * @param expression the starting expression
     * @param maxDepth   maximum rewrite depth (clamped to &ge; 0)
     * @param maxStates  maximum state budget (clamped to &ge; 1)
     * @return a {@link RuleImpactReport} with per-rule metrics
     */
    public RuleImpactReport analyze(String expression, int maxDepth, int maxStates) {
        if (expression == null || expression.isBlank()) {
            return RuleImpactReport.empty();
        }
        int depth = Math.max(0, maxDepth);
        int budget = Math.max(1, maxStates);
        ImpactAccumulator accum = new ImpactAccumulator();
        recurse(expression.trim(), 0, depth, accum, budget);
        return accum.build();
    }

    private void recurse(
            String expression,
            int depth,
            int maxDepth,
            ImpactAccumulator accum,
            int budget) {
        if (accum.exploredStates >= budget) {
            return;
        }
        accum.exploredStates++;
        if (!accum.visited.add(expression)) {
            return;
        }
        if (depth >= maxDepth) {
            return;
        }
        List<SearchSuccessorState> successors = successorSupplier.apply(expression);
        accum.currentPath.add(expression);
        for (SearchSuccessorState successor : successors) {
            if (accum.exploredStates >= budget) {
                break;
            }
            String rule = ruleLabel(successor);
            RuleAccumulator ra = accum.byRule.computeIfAbsent(rule, k -> new RuleAccumulator());
            ra.successorsGenerated++;
            ra.totalReductionImpact += (long) expression.length() - successor.successorExpression().length();
            if (accum.currentPath.contains(successor.successorExpression())) {
                ra.cyclesGenerated++;
            } else if (accum.visited.contains(successor.successorExpression())) {
                ra.duplicatesGenerated++;
            } else {
                ra.uniqueStatesAdded++;
            }
            recurse(successor.successorExpression(), depth + 1, maxDepth, accum, budget);
        }
        accum.currentPath.remove(expression);
    }

    private static String ruleLabel(SearchSuccessorState s) {
        return s.moveKind().isBlank() ? s.enumeratorId() : s.moveKind();
    }

    // -------------------------------------------------------------------------
    // Public records
    // -------------------------------------------------------------------------

    /**
     * Per-rule impact metrics for a single rewrite rule.
     *
     * @param successorsGenerated    total successor edges produced by this rule
     * @param duplicatesGenerated    edges that reached an already-visited state
     * @param cyclesGenerated        edges that were DFS back-edges
     * @param uniqueStatesAdded      edges that discovered a previously unseen state
     * @param averageReductionImpact mean {@code (sourceLength − successorLength)} per generated
     *                               successor; positive = rule shrinks expressions
     */
    public record RuleStats(
            int successorsGenerated,
            int duplicatesGenerated,
            int cyclesGenerated,
            int uniqueStatesAdded,
            double averageReductionImpact) {

        public RuleStats {
            successorsGenerated = Math.max(0, successorsGenerated);
            duplicatesGenerated = Math.max(0, duplicatesGenerated);
            cyclesGenerated = Math.max(0, cyclesGenerated);
            uniqueStatesAdded = Math.max(0, uniqueStatesAdded);
        }
    }

    /**
     * Aggregated rule-level impact data from a bounded exploration run.
     *
     * @param ruleStats per-rule metrics keyed by rule label (moveKind, or enumeratorId as fallback)
     */
    public record RuleImpactReport(Map<String, RuleStats> ruleStats) {

        public RuleImpactReport {
            ruleStats = ruleStats == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(ruleStats));
        }

        public static RuleImpactReport empty() {
            return new RuleImpactReport(Map.of());
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static final class RuleAccumulator {
        int successorsGenerated;
        int duplicatesGenerated;
        int cyclesGenerated;
        int uniqueStatesAdded;
        long totalReductionImpact;

        RuleStats build() {
            double avg = successorsGenerated > 0
                    ? (double) totalReductionImpact / successorsGenerated
                    : 0.0;
            return new RuleStats(
                    successorsGenerated, duplicatesGenerated, cyclesGenerated, uniqueStatesAdded, avg);
        }
    }

    private static final class ImpactAccumulator {
        int exploredStates;
        final LinkedHashSet<String> visited = new LinkedHashSet<>();
        final HashSet<String> currentPath = new HashSet<>();
        final LinkedHashMap<String, RuleAccumulator> byRule = new LinkedHashMap<>();

        RuleImpactReport build() {
            Map<String, RuleStats> stats = new LinkedHashMap<>();
            for (Map.Entry<String, RuleAccumulator> entry : byRule.entrySet()) {
                stats.put(entry.getKey(), entry.getValue().build());
            }
            return new RuleImpactReport(stats);
        }
    }
}
