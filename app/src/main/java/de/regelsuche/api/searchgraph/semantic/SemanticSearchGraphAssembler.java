package de.regelsuche.api.searchgraph.semantic;

import de.regelsuche.api.searchgraph.SearchExpression;
import de.regelsuche.api.searchgraph.SearchGraphClusterDto;
import de.regelsuche.api.searchgraph.SearchGraphDto;
import de.regelsuche.api.searchgraph.SearchGraphEdgeDto;
import de.regelsuche.api.searchgraph.SearchGraphNodeDto;
import de.regelsuche.assumption.AssumptionContext;
import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.export.MathPresentation;
import de.regelsuche.mining.MacroRuleCandidate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class SemanticSearchGraphAssembler {

    private final CanonicalGraphClusterer clusterer;
    private final RewriteSignalClassifier rewriteSignalClassifier;
    private final MainPathSelector mainPathSelector;
    private final SemanticMacroCompressor macroCompressor;
    private final SemanticLayoutService layoutService;

    public SemanticSearchGraphAssembler() {
        this(
            new CanonicalGraphClusterer(),
            new RewriteSignalClassifier(),
            new WeightedMainPathSelector(),
            new SemanticMacroCompressor(),
            new SemanticLayoutService()
        );
    }

    public SemanticSearchGraphAssembler(
        CanonicalGraphClusterer clusterer,
        RewriteSignalClassifier rewriteSignalClassifier,
        MainPathSelector mainPathSelector,
        SemanticMacroCompressor macroCompressor,
        SemanticLayoutService layoutService
    ) {
        this.clusterer = Objects.requireNonNull(clusterer);
        this.rewriteSignalClassifier = Objects.requireNonNull(rewriteSignalClassifier);
        this.mainPathSelector = Objects.requireNonNull(mainPathSelector);
        this.macroCompressor = Objects.requireNonNull(macroCompressor);
        this.layoutService = Objects.requireNonNull(layoutService);
    }

    public SemanticSearchGraphDto assemble(
        SearchGraphDto rawGraph,
        List<DiscoveredTransformation> paths,
        List<MacroRuleCandidate> macroRules,
        SemanticGraphViewMode mode,
        boolean showLowSignal,
        boolean showAlternatives,
        boolean showVariants,
        int maxAlternatives,
        int maxVariantsPerCluster
    ) {
        SearchGraphDto graph = rawGraph == null ? new SearchGraphDto(List.of(), List.of(), List.of(), null) : rawGraph;
        List<DiscoveredTransformation> pathList = paths == null ? List.of() : List.copyOf(paths);
        MainPathCriteria criteria = MainPathCriteria.defaults();
        var mainPath = mainPathSelector.selectMainPath(pathList, graph, criteria).orElse(null);
        Set<String> mainPathExpressions = canonicalMainPath(mainPath);
        Map<String, SemanticNodeKind> explicitEndpointKinds = canonicalMainPathEndpointKinds(mainPath);

        List<CanonicalGraphClusterer.ExpressionEvidence> evidence = graph.nodes().stream()
            .map(n -> new CanonicalGraphClusterer.ExpressionEvidence(n.expression(), n.depth(), n.score()))
            .toList();
        List<CanonicalExpressionCluster> canonicalClusters = clusterer.clusterWithEvidence(evidence, new AssumptionContext());
        Map<String, CanonicalExpressionCluster> byHash = canonicalClusters.stream()
            .collect(Collectors.toMap(CanonicalExpressionCluster::canonicalHash, c -> c));

        Map<String, String> rawExpressionToClusterId = new LinkedHashMap<>();
        for (CanonicalExpressionCluster c : canonicalClusters) {
            for (String variant : c.variants()) {
                rawExpressionToClusterId.put(variant, c.canonicalHash());
            }
        }
        MainPathProjection mainPathProjection = buildMainPathProjection(mainPath, rawExpressionToClusterId, showLowSignal);

        List<SemanticGraphNodeDto> semanticNodes = canonicalClusters.stream()
            .map(c -> toSemanticNode(
                c,
                mainPathExpressions.contains(c.canonicalHash()),
                explicitEndpointKinds.get(c.canonicalHash()),
                showVariants,
                maxVariantsPerCluster
            ))
            .sorted(Comparator.comparingInt(SemanticGraphNodeDto::minDepth).thenComparing(SemanticGraphNodeDto::id))
            .toList();

        List<SearchGraphEdgeDto> filteredRawEdges = graph.edges().stream()
            .filter(e -> showAlternatives || edgeOnMainPath(e, mainPath))
            .toList();
        int hiddenAlternativeCount = showAlternatives
            ? 0
            : (int) graph.edges().stream().filter(e -> !edgeOnMainPath(e, mainPath)).count();

        List<SemanticGraphEdgeDto> macroEdges = macroCompressor.compress(filteredRawEdges, pathList, macroRules == null ? List.of() : macroRules);
        List<SemanticGraphEdgeDto> semanticEdges = collapseLowSignalEdges(
            filteredRawEdges,
            macroEdges,
            rawExpressionToClusterId,
            mainPathExpressions,
            mainPathProjection,
            showLowSignal,
            maxAlternatives
        );

        List<SemanticGraphNodeDto> modeNodes = filterNodesByMode(semanticNodes, mode);
        Set<String> modeNodeIds = modeNodes.stream().map(SemanticGraphNodeDto::id).collect(Collectors.toCollection(LinkedHashSet::new));
        List<SemanticGraphEdgeDto> visibleEdges = semanticEdges.stream()
            .filter(e -> modeNodeIds.contains(e.from()) && modeNodeIds.contains(e.to()))
            .toList();
        List<SemanticGraphNodeDto> visibleNodes = pruneNodesAfterEdgeFiltering(
            modeNodes,
            visibleEdges,
            mode,
            mainPathProjection.visibleNodeIds()
        );
        Set<String> visibleNodeIds = visibleNodes.stream().map(SemanticGraphNodeDto::id).collect(Collectors.toCollection(LinkedHashSet::new));
        List<SemanticGraphClusterDto> semanticClusters = filterClustersByVisibleNodes(
            buildClusters(canonicalClusters, graph.clusters(), rawExpressionToClusterId),
            visibleNodeIds
        );
        SemanticLayoutKind layoutKind = switch (mode) {
            case COMPLEXITY -> SemanticLayoutKind.COMPLEXITY_AXIS;
            case MAIN_PATH -> SemanticLayoutKind.MAIN_PATH_LAYERED;
            case RAW -> SemanticLayoutKind.CLUSTERED_EXPLANATION;
            default -> SemanticLayoutKind.CLUSTERED_EXPLANATION;
        };
        SemanticLayoutDto layout = layoutService.layout(visibleNodes, visibleEdges, layoutKind);

        SemanticGraphStatsDto stats = new SemanticGraphStatsDto(
            graph.nodes().size(),
            graph.edges().size(),
            visibleNodes.size(),
            visibleEdges.size(),
            Math.max(0, graph.nodes().size() - semanticNodes.size()),
            (int) graph.edges().stream().filter(e -> rewriteSignalClassifier.classify(e) == RewriteSignal.LOW_SIGNAL).count(),
            (int) visibleEdges.stream().filter(SemanticGraphEdgeDto::macroMove).count(),
            mainPath == null ? 0 : mainPath.steps().size(),
            hiddenAlternativeCount
        );

        return new SemanticSearchGraphDto(
            visibleNodes,
            visibleEdges,
            semanticClusters,
            stats,
            new SemanticGraphViewConfigDto(
                mode == null ? SemanticGraphViewMode.SEMANTIC : mode,
                showLowSignal,
                showAlternatives,
                showVariants,
                maxAlternatives,
                maxVariantsPerCluster,
                layout
            )
        );
    }

    private SemanticGraphNodeDto toSemanticNode(
        CanonicalExpressionCluster cluster,
        boolean onMainPath,
        SemanticNodeKind explicitEndpointKind,
        boolean showVariants,
        int maxVariantsPerCluster
    ) {
        List<String> variants = cluster.variants();
        List<String> renderedVariants;
        if (showVariants) {
            renderedVariants = variants.stream().limit(Math.max(1, maxVariantsPerCluster)).toList();
        } else {
            renderedVariants = List.of();
        }
        boolean explicitEndpoint = explicitEndpointKind != null;
        SemanticNodeKind kind = classifyNodeKind(cluster.representativeExpression(), explicitEndpointKind);
        String representativeLatex = MathPresentation.DEFAULT.latex(cluster.representativeExpression());
        return new SemanticGraphNodeDto(
            cluster.canonicalHash(),
            cluster.canonicalExpression(),
            cluster.representativeExpression(),
            representativeLatex,
            MathPresentation.DEFAULT.layout(representativeLatex),
            renderedVariants,
            variants.size(),
            cluster.minDepth(),
            cluster.bestScore(),
            onMainPath,
            variants.size() > 1,
            "canonical:" + cluster.canonicalHash(),
            kind,
            explicitEndpoint
        );
    }

    private SemanticNodeKind classifyNodeKind(String expression, SemanticNodeKind explicitEndpointKind) {
        if (explicitEndpointKind != null) {
            return explicitEndpointKind;
        }
        SearchExpression type = SearchExpression.classify(expression);
        return switch (type) {
            case EQUATION, INEQUALITY -> SemanticNodeKind.GOAL;
            case MATRIX, VECTOR -> SemanticNodeKind.INTERMEDIATE;
            default -> SemanticNodeKind.INTERMEDIATE;
        };
    }

    private static List<SemanticGraphNodeDto> filterNodesByMode(
        List<SemanticGraphNodeDto> nodes,
        SemanticGraphViewMode mode
    ) {
        if (mode == null || mode == SemanticGraphViewMode.SEMANTIC || mode == SemanticGraphViewMode.RAW) {
            return nodes;
        }
        if (mode == SemanticGraphViewMode.MAIN_PATH) {
            return nodes.stream().filter(SemanticGraphNodeDto::onMainPath).toList();
        }
        // Complexity mode currently keeps all nodes and relies on layout kind.
        return nodes;
    }

    private static List<SemanticGraphNodeDto> pruneNodesAfterEdgeFiltering(
        List<SemanticGraphNodeDto> nodes,
        List<SemanticGraphEdgeDto> edges,
        SemanticGraphViewMode mode,
        Set<String> visibleMainPathNodeIds
    ) {
        if (mode == SemanticGraphViewMode.RAW || mode == SemanticGraphViewMode.COMPLEXITY) {
            return nodes;
        }
        Set<String> connectedNodeIds = edges.stream()
            .flatMap(e -> java.util.stream.Stream.of(e.from(), e.to()))
            .collect(Collectors.toCollection(LinkedHashSet::new));
        return nodes.stream()
            .filter(n -> connectedNodeIds.contains(n.id())
                || n.explicitEndpoint()
                || visibleMainPathNodeIds.contains(n.id())
                || (visibleMainPathNodeIds.isEmpty() && n.onMainPath()))
            .toList();
    }

    private static List<SemanticGraphClusterDto> filterClustersByVisibleNodes(
        List<SemanticGraphClusterDto> clusters,
        Set<String> visibleNodeIds
    ) {
        return clusters.stream()
            .map(cluster -> {
                List<String> nodeIds = cluster.nodeIds().stream()
                    .filter(visibleNodeIds::contains)
                    .toList();
                if (nodeIds.isEmpty()) {
                    return null;
                }
                int hidden = cluster.hiddenNodeCount() + Math.max(0, cluster.nodeIds().size() - nodeIds.size());
                return new SemanticGraphClusterDto(
                    cluster.id(),
                    cluster.label(),
                    cluster.kind(),
                    nodeIds,
                    hidden,
                    cluster.cohesion()
                );
            })
            .filter(Objects::nonNull)
            .toList();
    }

    private List<SemanticGraphEdgeDto> collapseLowSignalEdges(
        List<SearchGraphEdgeDto> rawEdges,
        List<SemanticGraphEdgeDto> macroEdges,
        Map<String, String> rawExpressionToClusterId,
        Set<String> mainPathExpressions,
        MainPathProjection mainPathProjection,
        boolean showLowSignal,
        int maxAlternatives
    ) {
        Map<String, List<SearchGraphEdgeDto>> lowSignalByPair = new LinkedHashMap<>();
        List<SemanticGraphEdgeDto> out = new ArrayList<>(mainPathProjection.edges());
        Set<String> mainPathSourceEdgeIds = mainPathProjection.sourceEdgeIds();
        int alternatives = 0;
        Map<String, SemanticGraphEdgeDto> macroByRawId = macroEdges.stream().collect(Collectors.toMap(
            e -> e.sourceEdgeIds().isEmpty() ? "" : e.sourceEdgeIds().getFirst(),
            e -> e,
            (a, b) -> a,
            LinkedHashMap::new
        ));
        for (SearchGraphEdgeDto edge : rawEdges) {
            String from = rawExpressionToClusterId.getOrDefault(edge.from(), edge.from());
            String to = rawExpressionToClusterId.getOrDefault(edge.to(), edge.to());
            String rawId = edgeId(edge.from(), edge.to(), edge.ruleId());
            if (mainPathSourceEdgeIds.contains(rawId)) {
                continue;
            }
            RewriteSignal signal = rewriteSignalClassifier.classify(edge);
            if (signal == RewriteSignal.LOW_SIGNAL) {
                if (!showLowSignal) {
                    String key = from + "->" + to;
                    lowSignalByPair.computeIfAbsent(key, k -> new ArrayList<>()).add(edge);
                    continue;
                }
            }
            SemanticGraphEdgeDto macro = macroByRawId.get(rawId);
            SemanticEdgeKind kind;
            if (macro != null && macro.macroMove()) {
                kind = SemanticEdgeKind.MACRO_MOVE;
            } else if (mainPathExpressions.contains(from) && mainPathExpressions.contains(to)) {
                kind = SemanticEdgeKind.MAIN_STEP;
            } else if (signal == RewriteSignal.LOW_SIGNAL) {
                kind = SemanticEdgeKind.NORMALIZATION;
            } else {
                kind = SemanticEdgeKind.ALTERNATIVE;
            }
            if (kind == SemanticEdgeKind.ALTERNATIVE && maxAlternatives > 0 && alternatives >= maxAlternatives) {
                continue;
            }
            if (kind == SemanticEdgeKind.ALTERNATIVE) {
                alternatives++;
            }
            out.add(new SemanticGraphEdgeDto(
                from,
                to,
                edge.ruleId(),
                edge.ruleLatex(),
                edge.layout(),
                kind,
                macro != null ? macro.atomicStepCount() : 1,
                0,
                signal == RewriteSignal.LOW_SIGNAL,
                macro != null && macro.macroMove(),
                edge.macroMoveExpansion(),
                List.of(rawId),
                Math.abs(edge.scoreDelta()) + (kind == SemanticEdgeKind.MAIN_STEP ? 1.0 : 0.0)
            ));
        }
        for (Map.Entry<String, List<SearchGraphEdgeDto>> entry : lowSignalByPair.entrySet()) {
            List<SearchGraphEdgeDto> collapsed = entry.getValue();
            if (collapsed.isEmpty()) {
                continue;
            }
            String[] pair = entry.getKey().split("->", 2);
            out.add(new SemanticGraphEdgeDto(
                pair[0],
                pair[1],
                "low-signal-collapsed",
                MathPresentation.DEFAULT.ruleLatex("low-signal-collapsed"),
                MathPresentation.DEFAULT.layout(MathPresentation.DEFAULT.ruleLatex("low-signal-collapsed")),
                SemanticEdgeKind.LOW_SIGNAL_COLLAPSED,
                0,
                collapsed.size(),
                true,
                false,
                null,
                collapsed.stream().map(e -> e.from() + "->" + e.to() + ":" + e.ruleId()).toList(),
                0.0
            ));
        }
        return dedupeEdges(out);
    }

    private MainPathProjection buildMainPathProjection(
        DiscoveredTransformation mainPath,
        Map<String, String> rawExpressionToClusterId,
        boolean showLowSignal
    ) {
        if (mainPath == null) {
            return MainPathProjection.empty();
        }
        List<SemanticGraphEdgeDto> edges = new ArrayList<>();
        Set<String> visibleNodeIds = new LinkedHashSet<>();
        Set<String> sourceEdgeIds = new LinkedHashSet<>();
        String currentVisible = clusterId(mainPath.originalExpression(), rawExpressionToClusterId);
        String goal = clusterId(mainPath.improvedExpression(), rawExpressionToClusterId);
        visibleNodeIds.add(currentVisible);
        List<String> hiddenSources = new ArrayList<>();
        int hiddenSteps = 0;
        for (TransformationStep step : mainPath.steps()) {
            String rawId = edgeId(step.beforeExpression(), step.afterExpression(), step.ruleId());
            sourceEdgeIds.add(rawId);
            boolean lowSignal = classifyStepSignal(step) == RewriteSignal.LOW_SIGNAL;
            if (!showLowSignal && lowSignal) {
                hiddenSources.add(rawId);
                hiddenSteps++;
                continue;
            }
            String to = clusterId(step.afterExpression(), rawExpressionToClusterId);
            List<String> edgeSources = new ArrayList<>(hiddenSources);
            edgeSources.add(rawId);
            if (!currentVisible.equals(to)) {
                edges.add(projectedMainPathEdge(currentVisible, to, step, hiddenSteps, edgeSources, lowSignal));
            }
            visibleNodeIds.add(to);
            currentVisible = to;
            hiddenSources.clear();
            hiddenSteps = 0;
        }
        if (hiddenSteps > 0 && !currentVisible.equals(goal)) {
            edges.add(collapsedMainPathTailEdge(currentVisible, goal, hiddenSteps, hiddenSources));
            currentVisible = goal;
        }
        visibleNodeIds.add(goal);
        return new MainPathProjection(dedupeEdges(edges), visibleNodeIds, sourceEdgeIds);
    }

    private SemanticGraphEdgeDto projectedMainPathEdge(
        String from,
        String to,
        TransformationStep step,
        int hiddenSteps,
        List<String> sourceEdgeIds,
        boolean lowSignal
    ) {
        String ruleLatex = MathPresentation.DEFAULT.ruleLatex(step.ruleId());
        return new SemanticGraphEdgeDto(
            from,
            to,
            step.ruleId(),
            ruleLatex,
            MathPresentation.DEFAULT.layout(ruleLatex),
            SemanticEdgeKind.MAIN_STEP,
            hiddenSteps + 1,
            hiddenSteps,
            lowSignal && hiddenSteps == 0,
            false,
            null,
            sourceEdgeIds,
            Math.abs(step.scoreBefore() - step.scoreAfter()) + 1.0
        );
    }

    private static SemanticGraphEdgeDto collapsedMainPathTailEdge(
        String from,
        String to,
        int hiddenSteps,
        List<String> sourceEdgeIds
    ) {
        String ruleId = "low-signal-collapsed";
        String ruleLatex = MathPresentation.DEFAULT.ruleLatex(ruleId);
        return new SemanticGraphEdgeDto(
            from,
            to,
            ruleId,
            ruleLatex,
            MathPresentation.DEFAULT.layout(ruleLatex),
            SemanticEdgeKind.MAIN_STEP,
            hiddenSteps,
            hiddenSteps,
            true,
            false,
            null,
            sourceEdgeIds,
            1.0
        );
    }

    private static RewriteSignal classifyStepSignal(TransformationStep step) {
        String rule = (step.ruleId() == null ? "" : step.ruleId()).toLowerCase(java.util.Locale.ROOT);
        if (rule.contains("commut")
            || rule.contains("associat")
            || rule.contains("parenth")
            || rule.contains("format")
            || rule.contains("sort")
            || rule.contains("canonical")
            || rule.contains("normalize")
            || rule.contains("neutral")
            || rule.contains("identity")
            || rule.contains("ast_")) {
            return RewriteSignal.LOW_SIGNAL;
        }
        if (step.ruleKind() == de.regelsuche.transform.RewriteKind.NORMALIZE) {
            return step.scoreBefore() == step.scoreAfter() ? RewriteSignal.LOW_SIGNAL : RewriteSignal.MEDIUM_SIGNAL;
        }
        if (Math.abs(step.scoreBefore() - step.scoreAfter()) >= 2) {
            return RewriteSignal.HIGH_SIGNAL;
        }
        return RewriteSignal.MEDIUM_SIGNAL;
    }

    private static String clusterId(String expression, Map<String, String> rawExpressionToClusterId) {
        String mapped = rawExpressionToClusterId.get(expression);
        if (mapped != null) {
            return mapped;
        }
        de.regelsuche.canonical.ExpressionCanonicalizer canonicalizer = new de.regelsuche.canonical.ExpressionCanonicalizer();
        return canonicalizer.stableHash(canonicalizer.canonicalize(expression));
    }

    private static String edgeId(String from, String to, String ruleId) {
        return from + "->" + to + ":" + ruleId;
    }

    private record MainPathProjection(
        List<SemanticGraphEdgeDto> edges,
        Set<String> visibleNodeIds,
        Set<String> sourceEdgeIds
    ) {
        private MainPathProjection {
            edges = List.copyOf(edges);
            visibleNodeIds = Set.copyOf(visibleNodeIds);
            sourceEdgeIds = Set.copyOf(sourceEdgeIds);
        }

        private static MainPathProjection empty() {
            return new MainPathProjection(List.of(), Set.of(), Set.of());
        }
    }

    private static List<SemanticGraphEdgeDto> dedupeEdges(Collection<SemanticGraphEdgeDto> edges) {
        Map<String, SemanticGraphEdgeDto> byKey = new LinkedHashMap<>();
        for (SemanticGraphEdgeDto edge : edges) {
            String key = edge.from() + "|" + edge.to() + "|" + edge.ruleId() + "|" + edge.kind();
            byKey.putIfAbsent(key, edge);
        }
        return new ArrayList<>(byKey.values());
    }

    private static List<SemanticGraphClusterDto> buildClusters(
        List<CanonicalExpressionCluster> canonicalClusters,
        List<SearchGraphClusterDto> rawClusters,
        Map<String, String> rawExpressionToClusterId
    ) {
        List<SemanticGraphClusterDto> out = new ArrayList<>();
        for (CanonicalExpressionCluster cluster : canonicalClusters) {
            out.add(new SemanticGraphClusterDto(
                "canonical:" + cluster.canonicalHash(),
                cluster.canonicalExpression(),
                SemanticClusterKind.CANONICAL_EQUIVALENCE,
                List.of(cluster.canonicalHash()),
                Math.max(0, cluster.variants().size() - 1),
                cluster.variants().isEmpty() ? 0.0 : 1.0 / cluster.variants().size()
            ));
        }
        if (rawClusters != null) {
            for (SearchGraphClusterDto cluster : rawClusters) {
                List<String> mappedNodeIds = cluster.nodeIds().stream()
                    .map(id -> rawExpressionToClusterId.getOrDefault(id, id))
                    .distinct()
                    .toList();
                if (mappedNodeIds.isEmpty()) {
                    continue;
                }
                out.add(new SemanticGraphClusterDto(
                    "raw:" + cluster.id(),
                    cluster.label(),
                    switch (cluster.type()) {
                        case MACRO_SEQUENCE -> SemanticClusterKind.MACRO_SEQUENCE;
                        case STRUCTURAL_PATTERN -> SemanticClusterKind.STRUCTURAL_PATTERN;
                        default -> SemanticClusterKind.DOMAIN_FAMILY;
                    },
                    mappedNodeIds,
                    0,
                    cluster.cohesionScore()
                ));
            }
        }
        return out;
    }

    private static boolean edgeOnMainPath(SearchGraphEdgeDto edge, DiscoveredTransformation mainPath) {
        if (mainPath == null) {
            return true;
        }
        for (TransformationStep step : mainPath.steps()) {
            if (step.beforeExpression().equals(edge.from())
                && step.afterExpression().equals(edge.to())
                && step.ruleId().equals(edge.ruleId())) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> canonicalMainPath(DiscoveredTransformation path) {
        if (path == null) {
            return Set.of();
        }
        de.regelsuche.canonical.ExpressionCanonicalizer canonicalizer = new de.regelsuche.canonical.ExpressionCanonicalizer();
        Set<String> result = new LinkedHashSet<>();
        result.add(canonicalizer.stableHash(canonicalizer.canonicalize(path.originalExpression())));
        for (TransformationStep step : path.steps()) {
            result.add(canonicalizer.stableHash(canonicalizer.canonicalize(step.beforeExpression())));
            result.add(canonicalizer.stableHash(canonicalizer.canonicalize(step.afterExpression())));
        }
        result.add(canonicalizer.stableHash(canonicalizer.canonicalize(path.improvedExpression())));
        return result;
    }

    private static Map<String, SemanticNodeKind> canonicalMainPathEndpointKinds(DiscoveredTransformation path) {
        if (path == null) {
            return Map.of();
        }
        de.regelsuche.canonical.ExpressionCanonicalizer canonicalizer = new de.regelsuche.canonical.ExpressionCanonicalizer();
        Map<String, SemanticNodeKind> result = new LinkedHashMap<>();
        result.put(canonicalizer.stableHash(canonicalizer.canonicalize(path.originalExpression())), SemanticNodeKind.SEED);
        result.put(canonicalizer.stableHash(canonicalizer.canonicalize(path.improvedExpression())), SemanticNodeKind.GOAL);
        return result;
    }

}
