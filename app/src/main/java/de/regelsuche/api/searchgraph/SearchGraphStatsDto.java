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
    int macroRuleCount
) {
    public SearchGraphStatsDto {
        ruleUsageFrequency = ruleUsageFrequency == null ? Map.of() : Map.copyOf(ruleUsageFrequency);
        mostUsefulRules = mostUsefulRules == null ? List.of() : List.copyOf(mostUsefulRules);
    }
}
