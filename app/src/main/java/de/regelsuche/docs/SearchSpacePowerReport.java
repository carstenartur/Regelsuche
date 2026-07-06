package de.regelsuche.docs;

import de.regelsuche.search.SearchSpaceAnalytics;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Aggregated metrics summarising the power and structure of a searched transformation space.
 *
 * <p>All fields are derived from the {@link DiscoveryBenchmarkEvidence} produced by the existing
 * search/trace pipeline; no independent search is performed.</p>
 */
public record SearchSpacePowerReport(
        String scenarioId,
        String inputExpression,
        String targetExpression,
        int selectedPathLength,
        int maxExploredDepth,
        int nodeCount,
        int edgeCount,
        int statesExplored,
        int uniqueCanonicalStates,
        double pruningRatio,
        Map<Integer, Long> depthHistogram,
        int deadEndCount,
        int selectedPathNodeCount,
        int selectedPathEdgeCount,
        int alternativeBranchNodeCount,
        int alternativeBranchEdgeCount,
        Map<String, Long> edgeSourceBreakdown,
        Map<String, Long> edgeKindBreakdown,
        Map<String, Long> topRuleIds) {

    public SearchSpacePowerReport {
        depthHistogram = depthHistogram == null ? Map.of() : Map.copyOf(depthHistogram);
        edgeSourceBreakdown = edgeSourceBreakdown == null ? Map.of() : Map.copyOf(edgeSourceBreakdown);
        edgeKindBreakdown = edgeKindBreakdown == null ? Map.of() : Map.copyOf(edgeKindBreakdown);
        topRuleIds = topRuleIds == null ? Map.of() : Map.copyOf(topRuleIds);
    }

    /** Computes a {@code SearchSpacePowerReport} from a fully-populated evidence object. */
    public static SearchSpacePowerReport compute(DiscoveryBenchmarkEvidence evidence) {
        List<DiscoveryBenchmarkEvidence.EvidenceNode> nodes = evidence.nodes();
        List<DiscoveryBenchmarkEvidence.EvidenceEdge> edges = evidence.edges();

        int selectedPathLength = evidence.withoutMacroRun() != null
                ? Math.max(0, evidence.withoutMacroRun().appliedRuleIds().size())
                : 0;
        int maxDepth = nodes.stream().mapToInt(DiscoveryBenchmarkEvidence.EvidenceNode::depth).max().orElse(0);

        Map<Integer, Long> depthHistogram = nodes.stream()
                .collect(Collectors.groupingBy(DiscoveryBenchmarkEvidence.EvidenceNode::depth, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));

        long deadEnds = nodes.stream()
                .filter(n -> n.tags().contains("dead-end"))
                .count();

        long selectedNodes = nodes.stream()
                .filter(n -> n.tags().contains("selected-path"))
                .count();
        long alternativeNodes = nodes.stream()
                .filter(n -> n.tags().contains("alternative-branch"))
                .count();

        long selectedEdges = edges.stream()
                .filter(e -> e.tags().contains("selected-path"))
                .count();
        long alternativeEdges = edges.stream()
                .filter(e -> e.tags().contains("alternative-branch"))
                .count();

        Map<String, Long> sourceBreakdown = edges.stream()
                .collect(Collectors.groupingBy(
                        e -> e.source() == null || e.source().isBlank() ? "unknown" : e.source(),
                        Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .collect(Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));

        Map<String, Long> kindBreakdown = edges.stream()
                .collect(Collectors.groupingBy(
                        e -> e.kind() == null || e.kind().isBlank() ? "unknown" : e.kind(),
                        Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .collect(Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));

        Map<String, Long> ruleUsage = edges.stream()
                .filter(e -> e.ruleId() != null && !e.ruleId().isBlank())
                .collect(Collectors.groupingBy(DiscoveryBenchmarkEvidence.EvidenceEdge::ruleId, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(10)
                .collect(Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));

        SearchSpaceAnalytics analytics = evidence.analytics();
        int statesExplored = analytics != null ? analytics.statesExplored() : 0;
        int uniqueCanonical = analytics != null ? analytics.uniqueCanonicalStates() : 0;
        double pruningRatio = statesExplored > 0
                ? Math.round((1.0 - (double) uniqueCanonical / statesExplored) * 10000.0) / 10000.0
                : 0.0;

        return new SearchSpacePowerReport(
                evidence.scenarioId(),
                evidence.inputExpression(),
                evidence.targetExpression(),
                selectedPathLength,
                maxDepth,
                evidence.nodeCount(),
                evidence.edgeCount(),
                statesExplored,
                uniqueCanonical,
                pruningRatio,
                depthHistogram,
                (int) deadEnds,
                (int) selectedNodes,
                (int) selectedEdges,
                (int) alternativeNodes,
                (int) alternativeEdges,
                sourceBreakdown,
                kindBreakdown,
                ruleUsage);
    }
}
