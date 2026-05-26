package de.regelsuche.provenance;

import de.regelsuche.validation.CandidateProofStatus;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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

    public List<ProvenanceNode> proofLineage(ProvenanceGraph graph, String runId, String hypothesisId) {
        String nodeId = hypothesisId.startsWith("hypothesis:")
            ? hypothesisId
            : "hypothesis:" + runId + ":" + hypothesisId;
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
                .filter(edge -> edge.fromId().equals(current))
                .filter(edge -> edge.type() == ProvenanceEdgeType.SUPPORTED_BY
                    || edge.type() == ProvenanceEdgeType.GENERALIZES
                    || edge.type() == ProvenanceEdgeType.DERIVED_FROM)
                .forEach(edge -> pending.add(edge.toId()));
        }
        return lineage;
    }

    public List<HypothesisFamily> quantitativeHypothesisFamilies(
        ProvenanceGraph graph,
        String runId,
        double similarityThreshold
    ) {
        double threshold = Math.max(0.0, Math.min(1.0, similarityThreshold));
        List<ProvenanceNode> hypotheses = graph.nodes().stream()
            .filter(node -> node.type() == ProvenanceNodeType.HYPOTHESIS)
            .filter(node -> runId == null || runId.isBlank() || node.id().startsWith("hypothesis:" + runId + ":"))
            .sorted(Comparator.comparing(ProvenanceNode::id))
            .toList();
        List<List<ProvenanceNode>> groups = new ArrayList<>();
        for (ProvenanceNode hypothesis : hypotheses) {
            List<ProvenanceNode> group = groups.stream()
                .filter(existing -> patternSimilarity(existing.getFirst(), hypothesis) >= threshold)
                .findFirst()
                .orElseGet(() -> {
                    List<ProvenanceNode> created = new ArrayList<>();
                    groups.add(created);
                    return created;
                });
            group.add(hypothesis);
        }
        return groups.stream()
            .map(group -> new HypothesisFamily(
                familyKey(group.getFirst()),
                List.copyOf(group),
                group.stream().collect(Collectors.groupingBy(
                    node -> node.properties().getOrDefault("proofStatus", "OBSERVED"),
                    LinkedHashMap::new,
                    Collectors.counting()
                ))
            ))
            .toList();
    }

    public Map<String, List<ProvenanceNode>> crossRunProvenance(ProvenanceGraph graph, List<String> runIds) {
        Set<String> selectedRuns = runIds == null ? Set.of() : Set.copyOf(runIds);
        Map<String, List<ProvenanceNode>> byPattern = graph.nodes().stream()
            .filter(node -> node.type() == ProvenanceNodeType.HYPOTHESIS)
            .filter(node -> selectedRuns.isEmpty() || selectedRuns.contains(runId(node.id())))
            .collect(Collectors.groupingBy(
                ProvenanceGraphQueries::familyKey,
                LinkedHashMap::new,
                Collectors.toList()
            ));
        byPattern.entrySet().removeIf(entry -> entry.getValue().stream()
            .map(node -> runId(node.id()))
            .collect(Collectors.toSet())
            .size() < 2);
        return byPattern;
    }

    public Map<String, ErrorDistribution> errorDistributionByDomain(ProvenanceGraph graph, String runId) {
        Map<String, ErrorDistribution> result = new LinkedHashMap<>();
        graph.nodes().stream()
            .filter(node -> node.type() == ProvenanceNodeType.COUNTEREXAMPLE)
            .filter(node -> runId == null || runId.isBlank() || node.id().startsWith("counterexample:" + runId + ":"))
            .forEach(counterexample -> {
                String failure = counterexample.properties().getOrDefault("invalidRule",
                    counterexample.properties().getOrDefault("failedAssumptions", "unknown"));
                for (String domain : domains(counterexample.properties().get("domains"))) {
                    result.merge(domain, new ErrorDistribution(domain, 1, Map.of(failure, 1L)), ErrorDistribution::merge);
                }
            });
        return result;
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

    private static List<String> domains(String domains) {
        if (domains == null || domains.isBlank()) {
            return List.of("unknown");
        }
        return java.util.Arrays.stream(domains.split(","))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .toList();
    }

    private static String runId(String provenanceNodeId) {
        String[] parts = provenanceNodeId.split(":", 3);
        return parts.length >= 2 ? parts[1] : "";
    }

    private static String familyKey(ProvenanceNode node) {
        return normalizePattern(node.properties().get("leftPattern"))
            + " -> "
            + normalizePattern(node.properties().get("rightPattern"));
    }

    private static String normalizePattern(String pattern) {
        return pattern == null ? "" : pattern.replaceAll("\\s+", " ").trim();
    }

    private static double patternSimilarity(ProvenanceNode left, ProvenanceNode right) {
        Set<String> leftTokens = tokens(familyKey(left));
        Set<String> rightTokens = tokens(familyKey(right));
        if (leftTokens.isEmpty() && rightTokens.isEmpty()) {
            return 1.0;
        }
        Set<String> intersection = new HashSet<>(leftTokens);
        intersection.retainAll(rightTokens);
        Set<String> union = new HashSet<>(leftTokens);
        union.addAll(rightTokens);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    private static Set<String> tokens(String value) {
        return java.util.Arrays.stream(value.split("[^A-Za-z0-9_]+"))
            .filter(token -> !token.isBlank())
            .collect(Collectors.toSet());
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

    public record HypothesisFamily(
        String patternKey,
        List<ProvenanceNode> hypotheses,
        Map<String, Long> proofStatusCounts
    ) {
    }

    public record ErrorDistribution(
        String domain,
        long total,
        Map<String, Long> failureModes
    ) {
        private ErrorDistribution merge(ErrorDistribution other) {
            Map<String, Long> mergedModes = new LinkedHashMap<>(failureModes);
            other.failureModes.forEach((mode, count) -> mergedModes.merge(mode, count, Long::sum));
            return new ErrorDistribution(domain, total + other.total, mergedModes);
        }
    }
}
