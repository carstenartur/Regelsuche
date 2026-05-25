package de.regelsuche.api.searchgraph;

import java.util.List;
import java.util.Map;

/**
 * Aggregate statistics describing the search graph as a whole.
 *
 * <p>Powers the dashboard tile view defined in {@code docs/visual-search-graph.md}.
 */
public record SearchGraphStatsDto(
    int nodesVisited,
    int edgesGenerated,
    int deadEnds,
    int bestScore,
    double averageBranchingFactor,
    int maxDepthReached,
    Map<String, Integer> ruleUsageFrequency,
    List<String> mostUsefulRules,
    int candidateCount,
    int macroRuleCount,
    int searchSpaceSize,
    Map<String, Integer> matchStats,
    MacroMoveUsage macroMoveUsage,
    long memoryUsage,
    CounterexampleStats counterexampleStats,
    double proofSuccessRate,
    Map<String, Integer> artifactCounts
) {
    public SearchGraphStatsDto {
        ruleUsageFrequency = ruleUsageFrequency == null ? Map.of() : Map.copyOf(ruleUsageFrequency);
        mostUsefulRules = mostUsefulRules == null ? List.of() : List.copyOf(mostUsefulRules);
        matchStats = matchStats == null ? Map.of() : Map.copyOf(matchStats);
        macroMoveUsage = macroMoveUsage == null ? new MacroMoveUsage(0, 0, 0, 0.0, List.of()) : macroMoveUsage;
        counterexampleStats = counterexampleStats == null ? new CounterexampleStats(0, 0) : counterexampleStats;
        artifactCounts = artifactCounts == null ? Map.of() : Map.copyOf(artifactCounts);
    }

    public SearchGraphStatsDto(
        int nodesVisited,
        int edgesGenerated,
        int deadEnds,
        int bestScore,
        double averageBranchingFactor,
        int maxDepthReached,
        Map<String, Integer> ruleUsageFrequency,
        List<String> mostUsefulRules,
        int candidateCount,
        int macroRuleCount
    ) {
        this(
            nodesVisited,
            edgesGenerated,
            deadEnds,
            bestScore,
            averageBranchingFactor,
            maxDepthReached,
            ruleUsageFrequency,
            mostUsefulRules,
            candidateCount,
            macroRuleCount,
            nodesVisited + edgesGenerated,
            Map.of(),
            new MacroMoveUsage(0, 0, 0, 0.0, List.of()),
            Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
            new CounterexampleStats(candidateCount, 0),
            0.0,
            Map.of()
        );
    }

    public record MacroMoveUsage(
        int timesConsidered,
        int timesApplied,
        int timesImprovedScore,
        double averageCostReduction,
        List<String> usefulForGoals
    ) {
        public MacroMoveUsage {
            usefulForGoals = usefulForGoals == null ? List.of() : List.copyOf(usefulForGoals);
        }
    }

    public record CounterexampleStats(int checked, int found) {
    }
}
