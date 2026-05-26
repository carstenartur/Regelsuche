package de.regelsuche.provenance;

import de.regelsuche.validation.CandidateProofStatus;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Query helpers for common provenance investigations. */
public final class ProvenanceGraphQueries {
    public List<ProvenanceNode> strongestHypotheses(ProvenanceGraph graph, int limit) {
        return graph.nodes().stream()
            .filter(node -> node.type() == ProvenanceNodeType.HYPOTHESIS)
            .sorted(Comparator
                .comparingDouble(ProvenanceGraphQueries::proofStrength).reversed()
                .thenComparing(Comparator.comparingDouble(ProvenanceGraphQueries::compressionRatio).reversed())
                .thenComparing(Comparator.comparingInt((ProvenanceNode node) -> supportCount(graph, node.id()))
                    .reversed()))
            .limit(Math.max(0, limit))
            .toList();
    }

    public List<ProvenanceNode> hypothesesRefutedOnlyInDomain(ProvenanceGraph graph, String domain) {
        Map<String, ProvenanceNode> nodes = graph.nodeIndex();
        return graph.nodes().stream()
            .filter(node -> node.type() == ProvenanceNodeType.HYPOTHESIS)
            .filter(hypothesis -> {
                List<ProvenanceNode> counterexamples = graph.edges().stream()
                    .filter(edge -> edge.type() == ProvenanceEdgeType.REFUTED_BY && edge.fromId().equals(hypothesis.id()))
                    .map(edge -> nodes.get(edge.toId()))
                    .filter(node -> node != null && node.type() == ProvenanceNodeType.COUNTEREXAMPLE)
                    .toList();
                return !counterexamples.isEmpty() && counterexamples.stream()
                    .allMatch(counterexample -> containsDomain(counterexample.properties().get("domains"), domain));
            })
            .toList();
    }

    public List<ProvenanceNode> mostReusedMacroRules(ProvenanceGraph graph, int limit) {
        return graph.nodes().stream()
            .filter(node -> node.type() == ProvenanceNodeType.MACRO_MOVE)
            .sorted(Comparator
                .comparingInt((ProvenanceNode node) -> usefulForCount(graph, node.id())).reversed()
                .thenComparing(Comparator.comparingDouble(ProvenanceGraphQueries::occurrences).reversed()))
            .limit(Math.max(0, limit))
            .toList();
    }

    public List<ProvenanceNode> derivationLineage(ProvenanceGraph graph, String nodeId) {
        Map<String, ProvenanceNode> nodes = graph.nodeIndex();
        List<ProvenanceNode> lineage = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(nodeId);
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (!seen.add(current)) {
                continue;
            }
            ProvenanceNode node = nodes.get(current);
            if (node != null) {
                lineage.add(node);
            }
            graph.edges().stream()
                .filter(edge -> isLineageEdge(edge.type()))
                .filter(edge -> edge.fromId().equals(current) || edge.toId().equals(current))
                .forEach(edge -> pending.add(edge.fromId().equals(current) ? edge.toId() : edge.fromId()));
        }
        return lineage;
    }

    private static boolean isLineageEdge(ProvenanceEdgeType type) {
        return type == ProvenanceEdgeType.DERIVED_FROM
            || type == ProvenanceEdgeType.GENERALIZES
            || type == ProvenanceEdgeType.GENERATED_BY
            || type == ProvenanceEdgeType.SUPPORTED_BY
            || type == ProvenanceEdgeType.REPLAY_OF;
    }

    private static boolean containsDomain(String domains, String domain) {
        if (domains == null || domain == null || domain.isBlank()) {
            return false;
        }
        for (String value : domains.split(",")) {
            if (value.trim().equalsIgnoreCase(domain.trim())) {
                return true;
            }
        }
        return false;
    }

    private static int supportCount(ProvenanceGraph graph, String id) {
        return (int) graph.edges().stream()
            .filter(edge -> edge.fromId().equals(id) && edge.type() == ProvenanceEdgeType.SUPPORTED_BY)
            .count();
    }

    private static int usefulForCount(ProvenanceGraph graph, String id) {
        return (int) graph.edges().stream()
            .filter(edge -> edge.fromId().equals(id) && edge.type() == ProvenanceEdgeType.USEFUL_FOR)
            .count();
    }

    private static double proofStrength(ProvenanceNode node) {
        try {
            return CandidateProofStatus.valueOf(node.properties().getOrDefault("proofStatus", "OBSERVED")).ordinal();
        } catch (IllegalArgumentException ignored) {
            return 0.0;
        }
    }

    private static double compressionRatio(ProvenanceNode node) {
        return parseDouble(node.properties().get("compressionRatio"));
    }

    private static double occurrences(ProvenanceNode node) {
        return parseDouble(node.properties().get("occurrences"));
    }

    private static double parseDouble(String value) {
        if (value == null || value.isBlank()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }
}
