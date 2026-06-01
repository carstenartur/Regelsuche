package de.regelsuche.docs;

import de.regelsuche.search.SearchSpaceAnalytics;
import java.util.List;
import java.util.Map;

public record DiscoveryBenchmarkEvidence(
        String scenarioId,
        String inputExpression,
        String targetExpression,
        boolean success,
        String failureReason,
        SearchRunEvidence withoutMacroRun,
        SearchRunEvidence withMacroRun,
        List<List<String>> foundPaths,
        List<String> bridgeRulesUsed,
        List<String> ruleFamiliesUsed,
        List<String> convergentStates,
        List<String> learnedMacros,
        List<String> reusedMacros,
        SearchSpaceAnalytics analytics,
        String validationStatus,
        List<EvidenceNode> nodes,
        List<EvidenceEdge> edges,
        String smallGraphMessage) {
    public DiscoveryBenchmarkEvidence {
        foundPaths = foundPaths == null ? List.of() : foundPaths.stream().map(List::copyOf).toList();
        bridgeRulesUsed = bridgeRulesUsed == null ? List.of() : List.copyOf(bridgeRulesUsed);
        ruleFamiliesUsed = ruleFamiliesUsed == null ? List.of() : List.copyOf(ruleFamiliesUsed);
        convergentStates = convergentStates == null ? List.of() : List.copyOf(convergentStates);
        learnedMacros = learnedMacros == null ? List.of() : List.copyOf(learnedMacros);
        reusedMacros = reusedMacros == null ? List.of() : List.copyOf(reusedMacros);
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }

    public int nodeCount() {
        return nodes.size();
    }

    public int edgeCount() {
        return edges.size();
    }

    public record SearchRunEvidence(
            boolean success,
            String failureReason,
            List<String> path,
            List<String> appliedRuleIds,
            SearchSpaceAnalytics analytics) {
        public SearchRunEvidence {
            path = path == null ? List.of() : List.copyOf(path);
            appliedRuleIds = appliedRuleIds == null ? List.of() : List.copyOf(appliedRuleIds);
        }
    }

    public record EvidenceNode(String id, String label, String kind) {
    }

    public record EvidenceEdge(
            String from,
            String to,
            String ruleId,
            String kind,
            String source,
            String packId,
            List<de.regelsuche.knowledge.SearchEffect> searchEffect) {
        public EvidenceEdge {
            searchEffect = searchEffect == null ? List.of() : List.copyOf(searchEffect);
        }
    }

    public Map<String, Object> summary() {
        return Map.of(
                "scenarioId", scenarioId,
                "success", success,
                "nodeCount", nodeCount(),
                "edgeCount", edgeCount(),
                "bridgeRulesUsed", bridgeRulesUsed,
                "learnedMacros", learnedMacros,
                "reusedMacros", reusedMacros);
    }
}
