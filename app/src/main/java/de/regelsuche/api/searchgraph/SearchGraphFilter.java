package de.regelsuche.api.searchgraph;

import de.regelsuche.mining.CandidateProofStatus;
import de.regelsuche.transform.RewriteKind;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Declarative filter for {@link SearchGraphDto} — used by the interactive
 * Cytoscape workbench and by the filtered export endpoints
 * ({@code GET /api/exports/search-graph.json?filter=...}).
 *
 * <p>Filter expression syntax is a comma-separated list of {@code key=value}
 * pairs:
 * <ul>
 *   <li>{@code bestPath=true} – keep only nodes/edges on the best path</li>
 *   <li>{@code hideDeadEnds=true} – drop dead-end nodes</li>
 *   <li>{@code ruleKind=NORMALIZE|SIMPLIFY|...} – keep edges of given kind</li>
 *   <li>{@code rule=ruleId,ruleId,…} – keep edges using one of these ids</li>
 *   <li>{@code proofStatus=OBSERVED|SYMBOLICALLY_VERIFIED|…}</li>
 *   <li>{@code minScoreDelta=&lt;int&gt;}, {@code maxScoreDelta=&lt;int&gt;}</li>
 *   <li>{@code cluster=clusterId} – keep only nodes inside that cluster</li>
 *   <li>{@code path=pathId} – keep only edges/nodes participating in that path</li>
 * </ul>
 */
public final class SearchGraphFilter {

    private final boolean bestPathOnly;
    private final boolean hideDeadEnds;
    private final RewriteKind ruleKind;
    private final Set<String> ruleIds;
    private final CandidateProofStatus proofStatus;
    private final Integer minScoreDelta;
    private final Integer maxScoreDelta;
    private final String clusterId;
    private final String pathId;

    private SearchGraphFilter(Builder builder) {
        this.bestPathOnly = builder.bestPathOnly;
        this.hideDeadEnds = builder.hideDeadEnds;
        this.ruleKind = builder.ruleKind;
        this.ruleIds = builder.ruleIds == null ? Set.of() : Set.copyOf(builder.ruleIds);
        this.proofStatus = builder.proofStatus;
        this.minScoreDelta = builder.minScoreDelta;
        this.maxScoreDelta = builder.maxScoreDelta;
        this.clusterId = builder.clusterId;
        this.pathId = builder.pathId;
    }

    public static SearchGraphFilter passThrough() {
        return new Builder().build();
    }

    public static SearchGraphFilter parse(String expression) {
        Builder b = new Builder();
        if (expression == null || expression.isBlank()) {
            return b.build();
        }
        for (String part : expression.split(",")) {
            int idx = part.indexOf('=');
            if (idx < 0) {
                continue;
            }
            String key = part.substring(0, idx).trim();
            String value = part.substring(idx + 1).trim();
            switch (key) {
                case "bestPath" -> b.bestPathOnly = Boolean.parseBoolean(value);
                case "hideDeadEnds" -> b.hideDeadEnds = Boolean.parseBoolean(value);
                case "ruleKind" -> {
                    try {
                        b.ruleKind = RewriteKind.valueOf(value);
                    } catch (IllegalArgumentException ignore) {
                        // ignore unknown kind
                    }
                }
                case "rule" -> {
                    if (b.ruleIds == null) {
                        b.ruleIds = new HashSet<>();
                    }
                    for (String r : value.split("\\+")) {
                        if (!r.isBlank()) {
                            b.ruleIds.add(r);
                        }
                    }
                }
                case "proofStatus" -> {
                    try {
                        b.proofStatus = CandidateProofStatus.valueOf(value);
                    } catch (IllegalArgumentException ignore) {
                        // ignore unknown
                    }
                }
                case "minScoreDelta" -> b.minScoreDelta = tryInt(value);
                case "maxScoreDelta" -> b.maxScoreDelta = tryInt(value);
                case "cluster" -> b.clusterId = value;
                case "path" -> b.pathId = value;
                default -> { /* ignore unknown keys for forward-compat */ }
            }
        }
        return b.build();
    }

    private static Integer tryInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public SearchGraphDto apply(SearchGraphDto graph) {
        // 1. Filter edges first; this constrains which nodes survive.
        List<SearchGraphEdgeDto> keptEdges = new ArrayList<>();
        for (SearchGraphEdgeDto edge : graph.edges()) {
            if (!edgeMatches(edge)) {
                continue;
            }
            keptEdges.add(edge);
        }
        Set<String> nodesFromEdges = new HashSet<>();
        for (SearchGraphEdgeDto edge : keptEdges) {
            nodesFromEdges.add(edge.from());
            nodesFromEdges.add(edge.to());
        }

        // 2. Cluster constraint also forces node membership.
        Set<String> clusterNodes = null;
        if (clusterId != null && !clusterId.isBlank()) {
            clusterNodes = new HashSet<>();
            for (SearchGraphClusterDto cluster : graph.clusters()) {
                if (cluster.id().equals(clusterId)) {
                    clusterNodes.addAll(cluster.nodeIds());
                }
            }
        }

        List<SearchGraphNodeDto> keptNodes = new ArrayList<>();
        for (SearchGraphNodeDto node : graph.nodes()) {
            if (!nodeMatches(node, nodesFromEdges, clusterNodes)) {
                continue;
            }
            keptNodes.add(node);
        }

        Set<String> survivingNodes = new HashSet<>();
        for (SearchGraphNodeDto node : keptNodes) {
            survivingNodes.add(node.id());
        }
        // 3. Drop edges whose endpoints didn't survive node filtering.
        List<SearchGraphEdgeDto> finalEdges = new ArrayList<>();
        for (SearchGraphEdgeDto edge : keptEdges) {
            if (survivingNodes.contains(edge.from()) && survivingNodes.contains(edge.to())) {
                finalEdges.add(edge);
            }
        }

        // 4. Clusters restricted to surviving nodes.
        List<SearchGraphClusterDto> keptClusters = new ArrayList<>();
        for (SearchGraphClusterDto cluster : graph.clusters()) {
            if (clusterId != null && !clusterId.isBlank() && !cluster.id().equals(clusterId)) {
                continue;
            }
            List<String> filteredNodeIds = new ArrayList<>();
            for (String n : cluster.nodeIds()) {
                if (survivingNodes.contains(n)) {
                    filteredNodeIds.add(n);
                }
            }
            if (filteredNodeIds.isEmpty()) {
                continue;
            }
            keptClusters.add(new SearchGraphClusterDto(
                cluster.id(),
                cluster.label(),
                cluster.type(),
                filteredNodeIds,
                cluster.supportingPathIds(),
                cluster.cohesionScore()
            ));
        }

        // 5. Stats: recompute the few values that depend on filtered topology.
        SearchGraphStatsDto stats = graph.stats();
        Map<String, Integer> rules = new LinkedHashMap<>();
        for (SearchGraphEdgeDto edge : finalEdges) {
            rules.merge(edge.ruleId(), 1, Integer::sum);
        }
        SearchGraphStatsDto filteredStats = new SearchGraphStatsDto(
            keptNodes.size(),
            finalEdges.size(),
            countDeadEnds(keptNodes),
            stats.bestScore(),
            stats.averageBranchingFactor(),
            stats.maxDepthReached(),
            rules,
            stats.mostUsefulRules(),
            stats.candidateCount(),
            stats.macroRuleCount()
        );
        return new SearchGraphDto(keptNodes, finalEdges, keptClusters, filteredStats);
    }

    private static int countDeadEnds(List<SearchGraphNodeDto> nodes) {
        int deadEnds = 0;
        for (SearchGraphNodeDto node : nodes) {
            if (node.isDeadEnd()) {
                deadEnds++;
            }
        }
        return deadEnds;
    }

    private boolean edgeMatches(SearchGraphEdgeDto edge) {
        if (bestPathOnly) {
            // a "best path" edge participates in a success: pathIds contains a "success:" tag
            boolean onBest = edge.pathIds().stream().anyMatch(id -> id.startsWith("success:"));
            if (!onBest) {
                return false;
            }
        }
        if (ruleKind != null && edge.ruleKind() != ruleKind) {
            return false;
        }
        if (!ruleIds.isEmpty() && !ruleIds.contains(edge.ruleId())) {
            return false;
        }
        if (minScoreDelta != null && edge.scoreDelta() < minScoreDelta) {
            return false;
        }
        if (maxScoreDelta != null && edge.scoreDelta() > maxScoreDelta) {
            return false;
        }
        if (pathId != null && !pathId.isBlank() && !edge.pathIds().contains(pathId)) {
            return false;
        }
        return true;
    }

    private boolean nodeMatches(SearchGraphNodeDto node, Set<String> nodesFromEdges, Set<String> clusterNodes) {
        if (bestPathOnly && !node.isBest()) {
            return false;
        }
        if (hideDeadEnds && node.isDeadEnd()) {
            return false;
        }
        if (proofStatus != null && node.candidateStatus() != proofStatus) {
            return false;
        }
        if (clusterNodes != null && !clusterNodes.contains(node.id())) {
            return false;
        }
        // If we have any edge constraint that actually filtered edges, keep nodes participating in surviving edges
        if (hasEdgeConstraints() && !nodesFromEdges.contains(node.id())) {
            // also keep isolated nodes if no edges are filtered at all (and no other reason to drop them)
            return false;
        }
        return true;
    }

    private boolean hasEdgeConstraints() {
        return bestPathOnly
            || ruleKind != null
            || !ruleIds.isEmpty()
            || minScoreDelta != null
            || maxScoreDelta != null
            || (pathId != null && !pathId.isBlank());
    }

    public static final class Builder {
        private boolean bestPathOnly;
        private boolean hideDeadEnds;
        private RewriteKind ruleKind;
        private Set<String> ruleIds;
        private CandidateProofStatus proofStatus;
        private Integer minScoreDelta;
        private Integer maxScoreDelta;
        private String clusterId;
        private String pathId;

        public SearchGraphFilter build() {
            return new SearchGraphFilter(this);
        }
    }
}
