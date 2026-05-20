package de.regelsuche.api.searchgraph;

import de.regelsuche.export.LaTeXMathRenderer;
import de.regelsuche.export.MathRenderer;
import de.regelsuche.graph.GraphEdge;
import de.regelsuche.graph.GraphSnapshot;
import de.regelsuche.mining.CandidateProofStatus;
import de.regelsuche.mining.RuleCandidate;
import de.regelsuche.search.SimplificationSuccess;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds a {@link SearchGraphDto} from a {@link GraphSnapshot}, the list of
 * known {@link SimplificationSuccess best results}, and optional mined
 * {@link RuleCandidate rule candidates}.
 *
 * <p>Step 1 of the Visual-Search-Graph feature. No HTTP endpoints, no UI –
 * just the data layer that subsequent PRs depend on.
 */
public final class SearchGraphAssembler {

    private final MathRenderer latex;

    public SearchGraphAssembler() {
        this(new LaTeXMathRenderer());
    }

    public SearchGraphAssembler(MathRenderer latex) {
        this.latex = Objects.requireNonNull(latex);
    }

    public SearchGraphDto assemble(GraphSnapshot snapshot, List<SimplificationSuccess> successes) {
        return assemble(snapshot, successes, List.of(), 0);
    }

    public SearchGraphDto assemble(
        GraphSnapshot snapshot,
        List<SimplificationSuccess> successes,
        List<RuleCandidate> candidates,
        int macroRuleCount
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        successes = successes == null ? List.of() : successes;
        candidates = candidates == null ? List.of() : candidates;

        List<GraphEdge> edges = snapshot.edges();

        // ---- Best path: trace from the highest-improvement success back to its root.
        Set<String> bestPathExpressions = collectBestPathExpressions(edges, successes);

        // ---- Visit counts per expression.
        Map<String, Integer> visitedCount = new HashMap<>();
        Map<String, Integer> nodeDepth = new HashMap<>();
        Map<String, Integer> nodeScore = new HashMap<>();
        Map<String, CandidateProofStatus> nodeStatus = new HashMap<>();
        Map<String, Set<String>> outgoingImprovingByNode = new HashMap<>();
        for (GraphEdge edge : edges) {
            visitedCount.merge(edge.toExpression(), 1, Integer::sum);
            nodeDepth.merge(edge.toExpression(), edge.depth(), Math::min);
            nodeScore.put(edge.toExpression(), edge.scoreAfter());
            nodeScore.putIfAbsent(edge.fromExpression(), edge.scoreBefore());
            nodeDepth.putIfAbsent(edge.fromExpression(), Math.max(0, edge.depth() - 1));
            CandidateProofStatus current = nodeStatus.get(edge.toExpression());
            CandidateProofStatus next = edge.validationStatus();
            if (current == null || (next != null && next.ordinal() > current.ordinal())) {
                nodeStatus.put(edge.toExpression(), next);
            }
            outgoingImprovingByNode.computeIfAbsent(edge.fromExpression(), k -> new HashSet<>());
            if (edge.improvement() > 0) {
                outgoingImprovingByNode.get(edge.fromExpression()).add(edge.toExpression());
            }
        }

        // ---- Cluster ids: nodes reached by an edge with the same ruleId
        //      that occurs in more than one supporting edge form a cluster.
        Map<String, String> clusterByNode = computeClusterByNode(edges);

        // ---- Build node DTOs.
        List<String> orderedNodes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String expr : snapshot.nodes()) {
            if (seen.add(expr)) {
                orderedNodes.add(expr);
            }
        }
        for (GraphEdge edge : edges) {
            if (seen.add(edge.fromExpression())) {
                orderedNodes.add(edge.fromExpression());
            }
            if (seen.add(edge.toExpression())) {
                orderedNodes.add(edge.toExpression());
            }
        }

        List<SearchGraphNodeDto> nodeDtos = new ArrayList<>(orderedNodes.size());
        for (String expr : orderedNodes) {
            int depth = nodeDepth.getOrDefault(expr, 0);
            int score = nodeScore.getOrDefault(expr, 0);
            int visited = visitedCount.getOrDefault(expr, 0);
            boolean isBest = bestPathExpressions.contains(expr);
            boolean isDeadEnd = !isBest
                && outgoingImprovingByNode.getOrDefault(expr, Set.of()).isEmpty()
                && !isLeafOnSuccess(expr, successes);
            CandidateProofStatus status = nodeStatus.getOrDefault(expr, CandidateProofStatus.OBSERVED);
            String clusterId = clusterByNode.getOrDefault(expr, "");
            nodeDtos.add(new SearchGraphNodeDto(
                expr,
                expr,
                latex.renderExpression(expr),
                score,
                depth,
                visited,
                isBest,
                isDeadEnd,
                status,
                clusterId
            ));
        }

        // ---- Build edge DTOs. pathIds = all SimplificationSuccess root#depth labels
        //      whose recorded edge matches this (from,to,rule).
        Map<String, List<String>> edgePathIds = buildEdgePathIds(edges, successes);
        List<SearchGraphEdgeDto> edgeDtos = new ArrayList<>(edges.size());
        for (GraphEdge edge : edges) {
            String key = edge.fromExpression() + "|" + edge.toExpression() + "|" + edge.transformationRule();
            edgeDtos.add(new SearchGraphEdgeDto(
                edge.fromExpression(),
                edge.toExpression(),
                edge.transformationRule(),
                edge.rewriteKind(),
                edge.scoreAfter() - edge.scoreBefore(),
                List.of(),
                edgePathIds.getOrDefault(key, List.of(edge.pathId() == null ? "" : edge.pathId())),
                edge.equivalencePreservingByConstruction()
            ));
        }

        // ---- Build cluster DTOs (one per distinct clusterId).
        Map<String, List<String>> nodesByCluster = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : clusterByNode.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            nodesByCluster.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());
        }
        List<SearchGraphClusterDto> clusterDtos = new ArrayList<>(nodesByCluster.size());
        for (Map.Entry<String, List<String>> entry : nodesByCluster.entrySet()) {
            clusterDtos.add(new SearchGraphClusterDto(entry.getKey(), entry.getKey(), entry.getValue()));
        }

        SearchGraphStatsDto stats = SearchGraphStatsService.compute(
            snapshot,
            successes,
            candidates,
            macroRuleCount
        );

        return new SearchGraphDto(nodeDtos, edgeDtos, clusterDtos, stats);
    }

    // -------------------------------------------------------------- helpers

    private static Set<String> collectBestPathExpressions(
        List<GraphEdge> edges, List<SimplificationSuccess> successes
    ) {
        Set<String> result = new HashSet<>();
        if (successes.isEmpty()) {
            return result;
        }
        SimplificationSuccess best = successes.stream()
            .max(Comparator.comparingInt(SimplificationSuccess::improvement))
            .orElseThrow();

        // Walk backwards from best.simplifiedExpression to best.originalExpression
        // following edges that match by toExpression / fromExpression.
        Map<String, GraphEdge> incomingByExpression = new HashMap<>();
        for (GraphEdge edge : edges) {
            // pick the strongest improving edge as canonical incoming
            GraphEdge prev = incomingByExpression.get(edge.toExpression());
            if (prev == null || edge.improvement() > prev.improvement()) {
                incomingByExpression.put(edge.toExpression(), edge);
            }
        }

        String cursor = best.simplifiedExpression();
        String root = best.originalExpression();
        result.add(cursor);
        Set<String> guard = new HashSet<>();
        while (!Objects.equals(cursor, root) && guard.add(cursor)) {
            GraphEdge incoming = incomingByExpression.get(cursor);
            if (incoming == null) {
                break;
            }
            cursor = incoming.fromExpression();
            result.add(cursor);
        }
        result.add(root);
        return result;
    }

    private static boolean isLeafOnSuccess(String expr, List<SimplificationSuccess> successes) {
        for (SimplificationSuccess s : successes) {
            if (expr.equals(s.simplifiedExpression())) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> computeClusterByNode(List<GraphEdge> edges) {
        Map<String, Integer> ruleUsage = new HashMap<>();
        for (GraphEdge edge : edges) {
            ruleUsage.merge(edge.transformationRule(), 1, Integer::sum);
        }
        Map<String, String> result = new HashMap<>();
        for (GraphEdge edge : edges) {
            int count = ruleUsage.getOrDefault(edge.transformationRule(), 0);
            if (count >= 2) {
                result.put(edge.toExpression(), "cluster:" + edge.transformationRule());
            }
        }
        return result;
    }

    private static Map<String, List<String>> buildEdgePathIds(
        List<GraphEdge> edges, List<SimplificationSuccess> successes
    ) {
        // The TransformationSearchService records pathId = root + "#" + depth,
        // so we group edges by (from,to,rule) and surface their pathIds.
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (GraphEdge edge : edges) {
            String key = edge.fromExpression() + "|" + edge.toExpression() + "|" + edge.transformationRule();
            result.computeIfAbsent(key, k -> new ArrayList<>()).add(edge.pathId() == null ? "" : edge.pathId());
        }
        // Annotate successes by linking their (root -> simplified) pair to a synthetic best-path id.
        for (SimplificationSuccess success : successes) {
            for (GraphEdge edge : edges) {
                if (edge.fromExpression().equals(success.originalExpression())
                    && edge.toExpression().equals(success.simplifiedExpression())
                    && Objects.equals(edge.transformationRule(), success.transformationRule())) {
                    String key = edge.fromExpression() + "|" + edge.toExpression() + "|" + edge.transformationRule();
                    String successId = "success:" + success.originalExpression() + "->" + success.simplifiedExpression();
                    result.computeIfAbsent(key, k -> new ArrayList<>()).add(successId);
                }
            }
        }
        return result;
    }
}
