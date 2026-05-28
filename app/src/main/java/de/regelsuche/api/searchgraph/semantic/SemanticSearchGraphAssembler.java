package de.regelsuche.api.searchgraph.semantic;

import de.regelsuche.api.searchgraph.SearchExpression;
import de.regelsuche.api.searchgraph.SearchGraphClusterDto;
import de.regelsuche.api.searchgraph.SearchGraphDto;
import de.regelsuche.api.searchgraph.SearchGraphEdgeDto;
import de.regelsuche.api.searchgraph.SearchGraphNodeDto;
import de.regelsuche.assumption.AssumptionContext;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.export.MathPresentation;
import de.regelsuche.mining.MacroRuleCandidate;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
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
    private static final String EXPAND_AND_COLLECT_RULE_ID = "polynomial_expand_and_collect_like_terms";
    private static final String PREPARE_COLLECT_RULE_ID = "polynomial_prepare_collect_like_terms";
    private static final List<String> EXPAND_AND_COLLECT_HIDDEN_STEPS = List.of(
        "distribute right add",
        "multiply constants",
        "product to power",
        "collect like terms"
    );

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
        return assemble(
            rawGraph,
            paths,
            macroRules,
            mode,
            SemanticMacroStepDisplay.COMPACT,
            showLowSignal,
            showAlternatives,
            showVariants,
            maxAlternatives,
            maxVariantsPerCluster
        );
    }

    public SemanticSearchGraphDto assemble(
        SearchGraphDto rawGraph,
        List<DiscoveredTransformation> paths,
        List<MacroRuleCandidate> macroRules,
        SemanticGraphViewMode mode,
        SemanticMacroStepDisplay showMacroSteps,
        boolean showLowSignal,
        boolean showAlternatives,
        boolean showVariants,
        int maxAlternatives,
        int maxVariantsPerCluster
    ) {
        SearchGraphDto graph = rawGraph == null ? new SearchGraphDto(List.of(), List.of(), List.of(), null) : rawGraph;
        List<DiscoveredTransformation> pathList = paths == null ? List.of() : List.copyOf(paths);
        SemanticMacroStepDisplay macroStepDisplay = showMacroSteps == null ? SemanticMacroStepDisplay.COMPACT : showMacroSteps;
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
        MainPathProjection mainPathProjection = buildMainPathProjection(
            mainPath,
            rawExpressionToClusterId,
            byHash,
            showLowSignal,
            macroStepDisplay
        );

        List<SemanticGraphNodeDto> semanticNodes = canonicalClusters.stream()
            .filter(c -> !mainPathProjection.shadowedCanonicalNodeIds().contains(c.canonicalHash()))
            .map(c -> toSemanticNode(
                c,
                mainPathExpressions.contains(c.canonicalHash()),
                explicitEndpointKinds.get(c.canonicalHash()),
                showVariants,
                maxVariantsPerCluster
            ))
            .collect(Collectors.toCollection(ArrayList::new));
        semanticNodes.addAll(mainPathProjection.nodes());
        semanticNodes = semanticNodes.stream()
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
                macroStepDisplay,
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
        Map<String, CanonicalExpressionCluster> clustersByHash,
        boolean showLowSignal,
        SemanticMacroStepDisplay showMacroSteps
    ) {
        if (mainPath == null) {
            return MainPathProjection.empty();
        }
        boolean didactic = showMacroSteps == SemanticMacroStepDisplay.DIDACTIC;
        List<SemanticGraphEdgeDto> edges = new ArrayList<>();
        List<SemanticGraphNodeDto> nodes = new ArrayList<>();
        Set<String> visibleNodeIds = new LinkedHashSet<>();
        Set<String> shadowedCanonicalNodeIds = new LinkedHashSet<>();
        Set<String> sourceEdgeIds = new LinkedHashSet<>();
        String currentVisible = didactic
            ? didacticNodeId("seed", mainPath.originalExpression())
            : clusterId(mainPath.originalExpression(), rawExpressionToClusterId);
        String goal = didactic
            ? didacticNodeId("goal", mainPath.improvedExpression())
            : clusterId(mainPath.improvedExpression(), rawExpressionToClusterId);
        nodes.add(toMainPathNode(
            currentVisible,
            mainPath.originalExpression(),
            0,
            SemanticNodeKind.SEED,
            rawExpressionToClusterId,
            clustersByHash,
            shadowedCanonicalNodeIds,
            didactic
        ));
        visibleNodeIds.add(currentVisible);
        List<String> hiddenSources = new ArrayList<>();
        int hiddenSteps = 0;
        Set<Integer> preservedLowSignalSteps = lowSignalStepsNeededForReadableProjection(mainPath, showLowSignal);
        for (int i = 0; i < mainPath.steps().size(); i++) {
            TransformationStep step = mainPath.steps().get(i);
            String rawId = edgeId(step.beforeExpression(), step.afterExpression(), step.ruleId());
            sourceEdgeIds.add(rawId);
            boolean lowSignal = classifyStepSignal(step) == RewriteSignal.LOW_SIGNAL;
            if (!showLowSignal && lowSignal && !preservedLowSignalSteps.contains(i)) {
                hiddenSources.add(rawId);
                hiddenSteps++;
                continue;
            }
            boolean finalStep = i == mainPath.steps().size() - 1
                && Objects.equals(step.afterExpression(), mainPath.improvedExpression());
            String to = didactic
                ? didacticNodeId(finalStep ? "goal" : Integer.toString(step.index() + 1), step.afterExpression())
                : clusterId(step.afterExpression(), rawExpressionToClusterId);
            if (!visibleNodeIds.contains(to)) {
                nodes.add(toMainPathNode(
                    to,
                    step.afterExpression(),
                    finalStep ? mainPath.steps().size() + 1 : step.index() + 1,
                    finalStep ? SemanticNodeKind.GOAL : SemanticNodeKind.INTERMEDIATE,
                    rawExpressionToClusterId,
                    clustersByHash,
                    shadowedCanonicalNodeIds,
                    didactic
                ));
            }
            List<String> edgeSources = new ArrayList<>(hiddenSources);
            edgeSources.add(rawId);
            if (didactic && "polynomial_collect_like_terms".equals(step.ruleId())) {
                String expanded = didacticExpandedBeforeCollect(step.beforeExpression(), step.afterExpression());
                String collectInputId = currentVisible;
                String collectInputExpression = step.beforeExpression();
                boolean insertedDidacticStep = false;
                if (!expanded.isBlank()) {
                    String expandedId = didacticNodeId("expand-" + step.index(), expanded);
                    if (!visibleNodeIds.contains(expandedId)) {
                        nodes.add(toMainPathNode(
                            expandedId,
                            expanded,
                            step.index() + 1,
                            SemanticNodeKind.INTERMEDIATE,
                            rawExpressionToClusterId,
                            clustersByHash,
                            shadowedCanonicalNodeIds,
                            true
                        ));
                    }
                    edges.add(projectedMainPathEdge(
                        collectInputId,
                        expandedId,
                        syntheticStep(step, expanded, "ast_distribute_right_add"),
                        0,
                        edgeSources,
                        false
                    ));
                    visibleNodeIds.add(expandedId);
                    collectInputId = expandedId;
                    collectInputExpression = expanded;
                    insertedDidacticStep = true;
                }
                String prepared = didacticPreCollectExpression(collectInputExpression, step.afterExpression());
                if (!prepared.isBlank()) {
                    String preparedId = didacticNodeId("prepare-" + step.index(), prepared);
                    if (!visibleNodeIds.contains(preparedId)) {
                        nodes.add(toMainPathNode(
                            preparedId,
                            prepared,
                            step.index() + 1,
                            SemanticNodeKind.INTERMEDIATE,
                            rawExpressionToClusterId,
                            clustersByHash,
                            shadowedCanonicalNodeIds,
                            true
                        ));
                    }
                    edges.add(projectedMainPathEdge(
                        collectInputId,
                        preparedId,
                        syntheticStep(step, prepared, PREPARE_COLLECT_RULE_ID),
                        0,
                        edgeSources,
                        false
                    ));
                    visibleNodeIds.add(preparedId);
                    collectInputId = preparedId;
                    collectInputExpression = prepared;
                    insertedDidacticStep = true;
                }
                if (insertedDidacticStep) {
                    edges.add(projectedMainPathEdge(
                        collectInputId,
                        to,
                        syntheticStep(step, collectInputExpression, step.afterExpression(), step.ruleId()),
                        0,
                        edgeSources,
                        false
                    ));
                    visibleNodeIds.add(to);
                    currentVisible = to;
                    hiddenSources.clear();
                    hiddenSteps = 0;
                    continue;
                }
            }
            if (currentVisible.equals(to)) {
                if (finalStep) {
                    replaceMainPathNode(
                        nodes,
                        toMainPathNode(
                            to,
                            step.afterExpression(),
                            mainPath.steps().size() + 1,
                            SemanticNodeKind.GOAL,
                            rawExpressionToClusterId,
                            clustersByHash,
                            shadowedCanonicalNodeIds,
                            didactic
                        )
                    );
                    if (edges.isEmpty()) {
                        hiddenSources.add(rawId);
                        hiddenSteps++;
                    } else {
                        int lastIndex = edges.size() - 1;
                        edges.set(lastIndex, reprojectFinalSameClusterEdge(edges.get(lastIndex), step, hiddenSteps, edgeSources, lowSignal));
                        hiddenSources.clear();
                        hiddenSteps = 0;
                    }
                } else if (edges.isEmpty()) {
                    hiddenSources.add(rawId);
                    hiddenSteps++;
                } else {
                    int lastIndex = edges.size() - 1;
                    edges.set(lastIndex, mergeCollapsedStep(edges.get(lastIndex), hiddenSteps + 1, edgeSources, lowSignal));
                    hiddenSources.clear();
                    hiddenSteps = 0;
                }
                visibleNodeIds.add(to);
                continue;
            }
            edges.add(projectedMainPathEdge(currentVisible, to, step, hiddenSteps, edgeSources, lowSignal));
            visibleNodeIds.add(to);
            currentVisible = to;
            hiddenSources.clear();
            hiddenSteps = 0;
        }
        if (hiddenSteps > 0 && !currentVisible.equals(goal)) {
            nodes.add(toMainPathNode(
                goal,
                mainPath.improvedExpression(),
                mainPath.steps().size() + 1,
                SemanticNodeKind.GOAL,
                rawExpressionToClusterId,
                clustersByHash,
                shadowedCanonicalNodeIds,
                didactic
            ));
            edges.add(collapsedMainPathTailEdge(currentVisible, goal, hiddenSteps, hiddenSources));
            currentVisible = goal;
        }
        if (nodes.stream().noneMatch(n -> n.id().equals(goal))) {
            nodes.add(toMainPathNode(
                goal,
                mainPath.improvedExpression(),
                mainPath.steps().size() + 1,
                SemanticNodeKind.GOAL,
                rawExpressionToClusterId,
                clustersByHash,
                shadowedCanonicalNodeIds,
                didactic
            ));
        }
        visibleNodeIds.add(goal);
        return new MainPathProjection(dedupeEdges(edges), visibleNodeIds, sourceEdgeIds, dedupeNodes(nodes), shadowedCanonicalNodeIds);
    }

    private static Set<Integer> lowSignalStepsNeededForReadableProjection(
        DiscoveredTransformation mainPath,
        boolean showLowSignal
    ) {
        if (showLowSignal || mainPath.steps().size() + 1 < 4) {
            return Set.of();
        }
        int visibleStates = 1;
        for (TransformationStep step : mainPath.steps()) {
            if (classifyStepSignal(step) != RewriteSignal.LOW_SIGNAL) {
                visibleStates++;
            }
        }
        TransformationStep lastStep = mainPath.steps().isEmpty() ? null : mainPath.steps().getLast();
        if (lastStep != null
            && classifyStepSignal(lastStep) == RewriteSignal.LOW_SIGNAL
            && Objects.equals(lastStep.afterExpression(), mainPath.improvedExpression())) {
            visibleStates++;
        }
        int missingStates = Math.max(0, 4 - visibleStates);
        if (missingStates == 0) {
            return Set.of();
        }
        Set<Integer> preserved = new LinkedHashSet<>();
        for (int i = 0; i < mainPath.steps().size() && preserved.size() < missingStates; i++) {
            if (classifyStepSignal(mainPath.steps().get(i)) == RewriteSignal.LOW_SIGNAL) {
                preserved.add(i);
            }
        }
        return preserved;
    }

    private SemanticGraphNodeDto toMainPathNode(
        String id,
        String expression,
        int depth,
        SemanticNodeKind kind,
        Map<String, String> rawExpressionToClusterId,
        Map<String, CanonicalExpressionCluster> clustersByHash,
        Set<String> shadowedCanonicalNodeIds,
        boolean distinctClusterIdentity
    ) {
        String canonicalId = clusterId(expression, rawExpressionToClusterId);
        shadowedCanonicalNodeIds.add(canonicalId);
        CanonicalExpressionCluster cluster = clustersByHash.get(canonicalId);
        String canonicalExpression = cluster == null ? expression : cluster.canonicalExpression();
        int variantCount = cluster == null ? 1 : cluster.variants().size();
        int bestScore = cluster == null ? 0 : cluster.bestScore();
        String representativeLatex = MathPresentation.DEFAULT.latex(expression);
        return new SemanticGraphNodeDto(
            id,
            canonicalExpression,
            expression,
            representativeLatex,
            MathPresentation.DEFAULT.layout(representativeLatex),
            List.of(),
            variantCount,
            depth,
            bestScore,
            true,
            variantCount > 1,
            distinctClusterIdentity ? "didactic:" + id : "canonical:" + canonicalId,
            kind,
            kind == SemanticNodeKind.SEED || kind == SemanticNodeKind.GOAL
        );
    }

    private static void replaceMainPathNode(List<SemanticGraphNodeDto> nodes, SemanticGraphNodeDto replacement) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).id().equals(replacement.id())) {
                nodes.set(i, replacement);
                return;
            }
        }
        nodes.add(replacement);
    }

    private SemanticGraphEdgeDto projectedMainPathEdge(
        String from,
        String to,
        TransformationStep step,
        int hiddenSteps,
        List<String> sourceEdgeIds,
        boolean lowSignal
    ) {
        boolean expandAndCollect = isExpandAndCollectMacro(
            step.ruleId(),
            hiddenSteps,
            sourceEdgeIds,
            step.beforeExpression(),
            step.afterExpression()
        );
        String ruleId = expandAndCollect ? EXPAND_AND_COLLECT_RULE_ID : step.ruleId();
        String ruleLatex = MathPresentation.DEFAULT.ruleLatex(ruleId);
        return new SemanticGraphEdgeDto(
            from,
            to,
            ruleId,
            ruleLatex,
            MathPresentation.DEFAULT.layout(ruleLatex),
            SemanticEdgeKind.MAIN_STEP,
            hiddenSteps + 1,
            hiddenSteps,
            lowSignal && hiddenSteps == 0,
            expandAndCollect,
            null,
            sourceEdgeIds,
            expandAndCollect ? EXPAND_AND_COLLECT_HIDDEN_STEPS : List.of(),
            Math.abs(step.scoreBefore() - step.scoreAfter()) + 1.0
        );
    }

    private static SemanticGraphEdgeDto mergeCollapsedStep(
        SemanticGraphEdgeDto edge,
        int collapsedStepCount,
        List<String> sourceEdgeIds,
        boolean lowSignal
    ) {
        List<String> mergedSourceEdgeIds = new ArrayList<>(edge.sourceEdgeIds());
        mergedSourceEdgeIds.addAll(sourceEdgeIds);
        return new SemanticGraphEdgeDto(
            edge.from(),
            edge.to(),
            edge.ruleId(),
            edge.ruleLatex(),
            edge.layout(),
            edge.kind(),
            edge.atomicStepCount() + collapsedStepCount,
            edge.hiddenStepCount() + collapsedStepCount,
            edge.lowSignal() || lowSignal,
            edge.macroMove(),
            edge.macroMoveExpansion(),
            mergedSourceEdgeIds.stream().distinct().toList(),
            edge.interestingness()
        );
    }

    private static SemanticGraphEdgeDto reprojectFinalSameClusterEdge(
        SemanticGraphEdgeDto edge,
        TransformationStep finalStep,
        int hiddenSteps,
        List<String> sourceEdgeIds,
        boolean lowSignal
    ) {
        boolean expandAndCollect = isExpandAndCollectMacro(
            finalStep.ruleId(),
            hiddenSteps + edge.hiddenStepCount(),
            sourceEdgeIds,
            finalStep.beforeExpression(),
            finalStep.afterExpression()
        );
        String ruleId = expandAndCollect ? EXPAND_AND_COLLECT_RULE_ID : finalStep.ruleId();
        String ruleLatex = MathPresentation.DEFAULT.ruleLatex(ruleId);
        List<String> mergedSourceEdgeIds = new ArrayList<>(edge.sourceEdgeIds());
        mergedSourceEdgeIds.addAll(sourceEdgeIds);
        return new SemanticGraphEdgeDto(
            edge.from(),
            edge.to(),
            ruleId,
            ruleLatex,
            MathPresentation.DEFAULT.layout(ruleLatex),
            edge.kind(),
            edge.atomicStepCount() + hiddenSteps + 1,
            edge.hiddenStepCount() + hiddenSteps + 1,
            edge.lowSignal() || lowSignal,
            edge.macroMove() || expandAndCollect,
            edge.macroMoveExpansion(),
            mergedSourceEdgeIds.stream().distinct().toList(),
            expandAndCollect ? EXPAND_AND_COLLECT_HIDDEN_STEPS : edge.hiddenSteps(),
            Math.abs(finalStep.scoreBefore() - finalStep.scoreAfter()) + 1.0
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

    private static boolean isExpandAndCollectMacro(
        String ruleId,
        int hiddenSteps,
        List<String> sourceEdgeIds,
        String beforeExpression,
        String afterExpression
    ) {
        return "polynomial_collect_like_terms".equals(ruleId)
            && ((hiddenSteps > 0
                && sourceEdgeIds != null
                && sourceEdgeIds.stream().anyMatch(id -> id.contains("ast_distribute")))
                || needsRemainingExpansionBeforeCollection(beforeExpression, afterExpression));
    }

    private static boolean needsRemainingExpansionBeforeCollection(String beforeExpression, String afterExpression) {
        String before = beforeExpression == null ? "" : beforeExpression.replaceAll("\\s+", "");
        String after = afterExpression == null ? "" : afterExpression.replaceAll("\\s+", "");
        return before.contains("(") && before.contains(")*") && after.contains("^");
    }

    private static String didacticNodeId(String sequence, String expression) {
        String suffix = Integer.toUnsignedString(Objects.toString(expression, "").hashCode(), 36);
        return "didactic-" + sequence.replaceAll("[^A-Za-z0-9_-]", "-") + "-" + suffix;
    }

    private static String didacticPreCollectExpression(String beforeExpression, String afterExpression) {
        try {
            Expr simplified = simplifyProductsForDidacticCollect(new ExpressionParser().parseTerm(beforeExpression));
            String formatted = ExpressionFormatter.format(simplified);
            String before = ExpressionFormatter.format(new ExpressionParser().parseTerm(beforeExpression));
            String after = ExpressionFormatter.format(new ExpressionParser().parseTerm(afterExpression));
            if (!formatted.equals(before) && !formatted.equals(after)) {
                return formatted;
            }
        } catch (RuntimeException ignored) {
            return "";
        }
        return "";
    }

    private static String didacticExpandedBeforeCollect(String beforeExpression, String afterExpression) {
        try {
            Expr expanded = expandProductsForDidacticCollect(new ExpressionParser().parseTerm(beforeExpression));
            String formatted = ExpressionFormatter.format(expanded);
            String before = ExpressionFormatter.format(new ExpressionParser().parseTerm(beforeExpression));
            String after = ExpressionFormatter.format(new ExpressionParser().parseTerm(afterExpression));
            if (!formatted.equals(before) && !formatted.equals(after)) {
                return formatted;
            }
        } catch (RuntimeException ignored) {
            return "";
        }
        return "";
    }

    private static TransformationStep syntheticStep(TransformationStep source, String afterExpression, String ruleId) {
        return syntheticStep(source, source.beforeExpression(), afterExpression, ruleId);
    }

    private static TransformationStep syntheticStep(
        TransformationStep source,
        String beforeExpression,
        String afterExpression,
        String ruleId
    ) {
        return new TransformationStep(
            source.index(),
            beforeExpression,
            afterExpression,
            ruleId,
            source.ruleKind(),
            source.scoreBefore(),
            source.scoreAfter(),
            source.equivalencePreserving(),
            source.explanation(),
            source.assumptions()
        );
    }

    private static Expr expandProductsForDidacticCollect(Expr expression) {
        if (!(expression instanceof BinaryExpr binary)) {
            return expression;
        }
        Expr left = expandProductsForDidacticCollect(binary.left());
        Expr right = expandProductsForDidacticCollect(binary.right());
        if (binary.operator() == BinaryOperator.ADD || binary.operator() == BinaryOperator.SUB) {
            return new BinaryExpr(left, binary.operator(), right);
        }
        if (binary.operator() != BinaryOperator.MUL) {
            return new BinaryExpr(left, binary.operator(), right);
        }
        if (left instanceof BinaryExpr leftBinary && leftBinary.operator() == BinaryOperator.ADD) {
            return new BinaryExpr(
                new BinaryExpr(leftBinary.left(), BinaryOperator.MUL, right),
                BinaryOperator.ADD,
                new BinaryExpr(leftBinary.right(), BinaryOperator.MUL, right)
            );
        }
        if (right instanceof BinaryExpr rightBinary && rightBinary.operator() == BinaryOperator.ADD) {
            return new BinaryExpr(
                new BinaryExpr(left, BinaryOperator.MUL, rightBinary.left()),
                BinaryOperator.ADD,
                new BinaryExpr(left, BinaryOperator.MUL, rightBinary.right())
            );
        }
        return new BinaryExpr(left, BinaryOperator.MUL, right);
    }

    private static Expr simplifyProductsForDidacticCollect(Expr expression) {
        if (!(expression instanceof BinaryExpr binary)) {
            return expression;
        }
        Expr left = simplifyProductsForDidacticCollect(binary.left());
        Expr right = simplifyProductsForDidacticCollect(binary.right());
        if (binary.operator() == BinaryOperator.ADD || binary.operator() == BinaryOperator.SUB) {
            return new BinaryExpr(left, binary.operator(), right);
        }
        if (binary.operator() != BinaryOperator.MUL) {
            return new BinaryExpr(left, binary.operator(), right);
        }
        if (left instanceof NumberExpr leftNumber && right instanceof NumberExpr rightNumber) {
            return new NumberExpr(leftNumber.value() * rightNumber.value());
        }
        if (left.equals(right)) {
            return new BinaryExpr(left, BinaryOperator.POW, new NumberExpr(2));
        }
        if (!(left instanceof NumberExpr) && right instanceof NumberExpr) {
            return new BinaryExpr(right, BinaryOperator.MUL, left);
        }
        return new BinaryExpr(left, BinaryOperator.MUL, right);
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
            || rule.contains("identity")) {
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
        Set<String> sourceEdgeIds,
        List<SemanticGraphNodeDto> nodes,
        Set<String> shadowedCanonicalNodeIds
    ) {
        private MainPathProjection {
            edges = List.copyOf(edges);
            visibleNodeIds = Set.copyOf(visibleNodeIds);
            sourceEdgeIds = Set.copyOf(sourceEdgeIds);
            nodes = List.copyOf(nodes);
            shadowedCanonicalNodeIds = Set.copyOf(shadowedCanonicalNodeIds);
        }

        private static MainPathProjection empty() {
            return new MainPathProjection(List.of(), Set.of(), Set.of(), List.of(), Set.of());
        }
    }

    private static List<SemanticGraphNodeDto> dedupeNodes(Collection<SemanticGraphNodeDto> nodes) {
        Map<String, SemanticGraphNodeDto> byId = new LinkedHashMap<>();
        for (SemanticGraphNodeDto node : nodes) {
            byId.putIfAbsent(node.id(), node);
        }
        return new ArrayList<>(byId.values());
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
