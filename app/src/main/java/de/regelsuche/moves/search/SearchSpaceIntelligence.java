package de.regelsuche.moves.search;

import de.regelsuche.moves.search.BoundedSearchExplorer.ExplorationResult;
import de.regelsuche.moves.search.RuleImpactAnalyzer.RuleImpactReport;
import de.regelsuche.moves.search.RuleImpactAnalyzer.RuleStats;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Phase-5 Search Space Intelligence: interprets exploration metrics and emits
 * actionable warnings about the structure of the reachable search space.
 *
 * <p>Internally runs {@link BoundedSearchExplorer} (Phases 2–3) and
 * {@link RuleImpactAnalyzer} (Phase 4) and produces higher-level intelligence:</p>
 * <ul>
 *   <li>Warnings about structural problems (high branching, explosion, dominant rules, etc.)</li>
 *   <li>{@code dominantRule} / {@code dominantRuleShare} – the rule driving the most growth</li>
 *   <li>{@code duplicateHeavySearchSpace} – quick boolean flag for heavily duplicate spaces</li>
 *   <li>{@code estimatedGrowth} – projected state count at the configured depth
 *       ({@code averageBranchingFactor ^ maxDepth})</li>
 * </ul>
 */
public final class SearchSpaceIntelligence {

    // -------------------------------------------------------------------------
    // Warning constants
    // -------------------------------------------------------------------------

    /**
     * Emitted when the average branching factor across the explored space exceeds
     * {@value #HIGH_BRANCHING_FACTOR_THRESHOLD}.
     */
    public static final String WARNING_HIGH_BRANCHING_FACTOR = "HIGH_BRANCHING_FACTOR";

    /**
     * Emitted when {@link IntelligenceReport#estimatedGrowth()} exceeds
     * {@value #SEARCH_SPACE_EXPLOSION_THRESHOLD} projected states.
     */
    public static final String WARNING_SEARCH_SPACE_EXPLOSION = "SEARCH_SPACE_EXPLOSION";

    /**
     * Emitted when a single rule is responsible for at least
     * {@value #DOMINANT_RULE_SHARE_THRESHOLD} of all generated successor edges.
     */
    public static final String WARNING_DOMINANT_RULE = "DOMINANT_RULE";

    /** Emitted when more than half of all explored states were already-visited duplicates. */
    public static final String WARNING_HIGH_DUPLICATE_RATE = "HIGH_DUPLICATE_RATE";

    /**
     * Emitted when the fraction of DFS back-edges (cycles) among all successor edges
     * exceeds {@value #CYCLE_HEAVY_THRESHOLD}.
     */
    public static final String WARNING_CYCLE_HEAVY = "CYCLE_HEAVY";

    // -------------------------------------------------------------------------
    // Thresholds (package-visible for tests)
    // -------------------------------------------------------------------------

    static final double HIGH_BRANCHING_FACTOR_THRESHOLD = 5.0;
    static final double SEARCH_SPACE_EXPLOSION_THRESHOLD = 1_000.0;
    static final double DOMINANT_RULE_SHARE_THRESHOLD = 0.8;
    static final double HIGH_DUPLICATE_RATE_THRESHOLD = 0.5;
    static final double CYCLE_HEAVY_THRESHOLD = 0.1;

    /** Default exploration depth for the no-argument {@link #analyze(String)} overload. */
    public static final int DEFAULT_MAX_DEPTH = 4;

    /** Default state budget for the no-argument {@link #analyze(String)} overload. */
    public static final int DEFAULT_MAX_STATES = 500;

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final BoundedSearchExplorer explorer;
    private final RuleImpactAnalyzer ruleImpactAnalyzer;

    public SearchSpaceIntelligence() {
        this(new BoundedSearchExplorer(), new RuleImpactAnalyzer());
    }

    public SearchSpaceIntelligence(BoundedSearchExplorer explorer, RuleImpactAnalyzer ruleImpactAnalyzer) {
        this.explorer = explorer == null ? new BoundedSearchExplorer() : explorer;
        this.ruleImpactAnalyzer = ruleImpactAnalyzer == null ? new RuleImpactAnalyzer() : ruleImpactAnalyzer;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Analyses the search space of {@code expression} using {@link #DEFAULT_MAX_DEPTH}
     * and {@link #DEFAULT_MAX_STATES}.
     *
     * @param expression the starting expression
     * @return an {@link IntelligenceReport} with Phase-5 intelligence
     */
    public IntelligenceReport analyze(String expression) {
        return analyze(expression, DEFAULT_MAX_DEPTH, DEFAULT_MAX_STATES);
    }

    /**
     * Analyses the search space of {@code expression} up to {@code maxDepth} steps,
     * visiting at most {@code maxStates} states.
     *
     * @param expression the starting expression
     * @param maxDepth   maximum rewrite depth (passed through to sub-analysers)
     * @param maxStates  maximum state budget (passed through to sub-analysers)
     * @return an {@link IntelligenceReport} with Phase-5 intelligence
     */
    public IntelligenceReport analyze(String expression, int maxDepth, int maxStates) {
        if (expression == null || expression.isBlank()) {
            return IntelligenceReport.empty();
        }
        ExplorationResult exploration = explorer.explore(expression, maxDepth, maxStates);
        RuleImpactReport ruleImpact = ruleImpactAnalyzer.analyze(expression, maxDepth, maxStates);

        double duplicateRate = exploration.exploredStates() > 0
                ? (double) exploration.duplicateStates() / exploration.exploredStates()
                : 0.0;
        double avgBranching = exploration.averageBranchingFactor();
        double estimatedGrowth = computeEstimatedGrowth(avgBranching, Math.max(0, maxDepth));

        String dominantRule = computeDominantRule(ruleImpact.ruleStats());
        double dominantRuleShare = computeDominantRuleShare(ruleImpact.ruleStats(), dominantRule);
        boolean duplicateHeavy = duplicateRate > HIGH_DUPLICATE_RATE_THRESHOLD;

        List<String> warnings = buildWarnings(
                avgBranching, estimatedGrowth, dominantRuleShare,
                duplicateRate, exploration.cycleRate());

        return new IntelligenceReport(
                dominantRule,
                dominantRuleShare,
                duplicateHeavy,
                estimatedGrowth,
                warnings);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static double computeEstimatedGrowth(double avgBranching, int maxDepth) {
        if (avgBranching <= 0.0 || maxDepth <= 0) {
            return 1.0;
        }
        return Math.pow(avgBranching, maxDepth);
    }

    private static String computeDominantRule(Map<String, RuleStats> ruleStats) {
        return ruleStats.entrySet().stream()
                .max((a, b) -> Integer.compare(
                        a.getValue().successorsGenerated(),
                        b.getValue().successorsGenerated()))
                .map(Map.Entry::getKey)
                .orElse("");
    }

    private static double computeDominantRuleShare(
            Map<String, RuleStats> ruleStats, String dominantRule) {
        if (dominantRule.isEmpty()) {
            return 0.0;
        }
        int total = ruleStats.values().stream()
                .mapToInt(RuleStats::successorsGenerated)
                .sum();
        if (total == 0) {
            return 0.0;
        }
        return (double) ruleStats.get(dominantRule).successorsGenerated() / total;
    }

    private static List<String> buildWarnings(
            double avgBranching,
            double estimatedGrowth,
            double dominantRuleShare,
            double duplicateRate,
            double cycleRate) {
        List<String> warnings = new ArrayList<>();
        if (avgBranching > HIGH_BRANCHING_FACTOR_THRESHOLD) {
            warnings.add(WARNING_HIGH_BRANCHING_FACTOR);
        }
        if (estimatedGrowth > SEARCH_SPACE_EXPLOSION_THRESHOLD) {
            warnings.add(WARNING_SEARCH_SPACE_EXPLOSION);
        }
        if (dominantRuleShare >= DOMINANT_RULE_SHARE_THRESHOLD) {
            warnings.add(WARNING_DOMINANT_RULE);
        }
        if (duplicateRate > HIGH_DUPLICATE_RATE_THRESHOLD) {
            warnings.add(WARNING_HIGH_DUPLICATE_RATE);
        }
        if (cycleRate > CYCLE_HEAVY_THRESHOLD) {
            warnings.add(WARNING_CYCLE_HEAVY);
        }
        return Collections.unmodifiableList(warnings);
    }

    // -------------------------------------------------------------------------
    // Output record
    // -------------------------------------------------------------------------

    /**
     * Phase-5 intelligence report.
     *
     * @param dominantRule              the rule generating the most successor edges, or {@code ""}
     * @param dominantRuleShare         fraction of all successor edges attributable to
     *                                  {@code dominantRule} (in [0, 1])
     * @param duplicateHeavySearchSpace {@code true} if more than half of explored states were duplicates
     * @param estimatedGrowth           projected reachable-state count at the configured depth
     *                                  ({@code averageBranchingFactor ^ maxDepth}); &ge; 0
     * @param warnings                  immutable list of active warning codes
     */
    public record IntelligenceReport(
            String dominantRule,
            double dominantRuleShare,
            boolean duplicateHeavySearchSpace,
            double estimatedGrowth,
            List<String> warnings) {

        public IntelligenceReport {
            dominantRule = dominantRule == null ? "" : dominantRule;
            dominantRuleShare = Math.max(0d, Math.min(1d, dominantRuleShare));
            estimatedGrowth = Math.max(0d, estimatedGrowth);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        public static IntelligenceReport empty() {
            return new IntelligenceReport("", 0.0, false, 0.0, List.of());
        }
    }
}
