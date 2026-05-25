package de.regelsuche.api.searchgraph;

import de.regelsuche.graph.GraphEdge;
import de.regelsuche.graph.GraphSnapshot;
import de.regelsuche.mining.RuleCandidate;
import de.regelsuche.search.SimplificationSuccess;
import de.regelsuche.validation.CandidateProofStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes aggregate {@link SearchGraphStatsDto search-graph statistics}
 * (dashboard metrics).
 */
public final class SearchGraphStatsService {

    private SearchGraphStatsService() {
    }

    public static SearchGraphStatsDto compute(GraphSnapshot snapshot, List<SimplificationSuccess> successes) {
        return compute(snapshot, successes, List.of(), 0);
    }

    public static SearchGraphStatsDto compute(
        GraphSnapshot snapshot,
        List<SimplificationSuccess> successes,
        List<RuleCandidate> candidates,
        int macroRuleCount
    ) {
        List<GraphEdge> edges = snapshot.edges();
        Set<String> nodes = new HashSet<>(snapshot.nodes());
        for (GraphEdge edge : edges) {
            nodes.add(edge.fromExpression());
            nodes.add(edge.toExpression());
        }

        int nodesVisited = nodes.size();
        int edgesGenerated = edges.size();
        int maxDepthReached = edges.stream().mapToInt(GraphEdge::depth).max().orElse(0);
        int bestScore = successes.stream().mapToInt(SimplificationSuccess::improvement).max().orElse(0);

        // ruleUsageFrequency
        Map<String, Integer> ruleUsage = new HashMap<>();
        Map<String, Integer> ruleImprovement = new HashMap<>();
        Map<String, Integer> outDegree = new HashMap<>();
        Set<String> hasImprovingOutgoing = new HashSet<>();
        int macroConsidered = 0;
        int macroApplied = 0;
        int macroImproved = 0;
        int macroCostReduction = 0;
        Set<String> macroGoals = new HashSet<>();
        for (GraphEdge edge : edges) {
            ruleUsage.merge(edge.transformationRule(), 1, Integer::sum);
            ruleImprovement.merge(edge.transformationRule(), edge.improvement(), Integer::sum);
            outDegree.merge(edge.fromExpression(), 1, Integer::sum);
            if (edge.improvement() > 0) {
                hasImprovingOutgoing.add(edge.fromExpression());
            }
            if (edge.macroMoveExpansion() != null || edge.transformationRule().startsWith("macro")) {
                macroConsidered++;
                macroApplied++;
                if (edge.improvement() > 0 || edge.scoreAfter() < edge.scoreBefore()) {
                    macroImproved++;
                }
                macroCostReduction += Math.max(0, edge.scoreBefore() - edge.scoreAfter());
                if (edge.toExpression() != null && !edge.toExpression().isBlank()) {
                    macroGoals.add(edge.toExpression());
                }
            }
        }
        Map<String, Integer> ruleUsageFrequency = new LinkedHashMap<>();
        ruleUsage.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .forEach(e -> ruleUsageFrequency.put(e.getKey(), e.getValue()));

        // averageBranchingFactor = edges / nodesWithOutgoing
        int nodesWithOutgoing = outDegree.size();
        double averageBranchingFactor = nodesWithOutgoing == 0
            ? 0.0
            : (double) edgesGenerated / (double) nodesWithOutgoing;

        // mostUsefulRules: rank by total improvement, then frequency
        List<String> mostUsefulRules = new ArrayList<>(ruleImprovement.keySet());
        mostUsefulRules.sort(
            Comparator.<String>comparingInt(r -> -ruleImprovement.getOrDefault(r, 0))
                .thenComparingInt(r -> -ruleUsage.getOrDefault(r, 0))
        );

        // deadEnds: nodes that have no improving outgoing edge and are not the simplified result of a success
        Set<String> successTerminals = new HashSet<>();
        for (SimplificationSuccess success : successes) {
            successTerminals.add(success.simplifiedExpression());
        }
        int deadEnds = 0;
        for (String node : nodes) {
            if (!hasImprovingOutgoing.contains(node) && !successTerminals.contains(node)) {
                deadEnds++;
            }
        }

        Map<String, Integer> matchStats = new LinkedHashMap<>();
        matchStats.put("successes", successes.size());
        matchStats.put("candidates", candidates.size());
        matchStats.put("rulesMatched", ruleUsage.values().stream().mapToInt(Integer::intValue).sum());
        int proofEligible = candidates.size();
        long proofSuccesses = candidates.stream()
            .filter(candidate -> candidate.proofStatus() != null
                && candidate.proofStatus().ordinal() >= CandidateProofStatus.VALIDATED_BY_EXAMPLES.ordinal())
            .count();
        long counterexamples = candidates.stream()
            .filter(candidate -> candidate.proofStatus() == CandidateProofStatus.REJECTED)
            .count();
        return new SearchGraphStatsDto(
            nodesVisited,
            edgesGenerated,
            deadEnds,
            bestScore,
            averageBranchingFactor,
            maxDepthReached,
            ruleUsageFrequency,
            mostUsefulRules,
            candidates.size(),
            macroRuleCount,
            nodesVisited + edgesGenerated,
            matchStats,
            new SearchGraphStatsDto.MacroMoveUsage(
                macroConsidered,
                macroApplied,
                macroImproved,
                macroApplied == 0 ? 0.0 : (double) macroCostReduction / (double) macroApplied,
                macroGoals.stream().sorted().limit(10).toList()
            ),
            Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
            new SearchGraphStatsDto.CounterexampleStats(proofEligible, (int) counterexamples),
            proofEligible == 0 ? 0.0 : (double) proofSuccesses / (double) proofEligible,
            Map.of()
        );
    }
}
