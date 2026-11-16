package de.regelsuche.discovery;

import de.regelsuche.api.searchgraph.SearchGraphAssembler;
import de.regelsuche.api.searchgraph.SearchGraphDto;
import de.regelsuche.api.searchgraph.semantic.SemanticEdgeKind;
import de.regelsuche.api.searchgraph.semantic.SemanticGraphEdgeDto;
import de.regelsuche.api.searchgraph.semantic.SemanticGraphViewMode;
import de.regelsuche.api.searchgraph.semantic.SemanticSearchGraphAssembler;
import de.regelsuche.api.searchgraph.semantic.SemanticSearchGraphDto;
import de.regelsuche.benchmark.DeterministicDiscoveryExperimentRunner;
import de.regelsuche.benchmark.DiscoverySemanticReportView;
import de.regelsuche.graph.ExpressionGraphStore;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Bridges app-level search graph semantics into experiment report artifacts. */
final class SemanticDiscoveryReportViewAssembler {
    private final SearchGraphAssembler rawAssembler = new SearchGraphAssembler();
    private final SemanticSearchGraphAssembler semanticAssembler = new SemanticSearchGraphAssembler();

    DiscoverySemanticReportView assemble(
        DeterministicDiscoveryExperimentRunner.DiscoveryReport report,
        ExpressionGraphStore graphStore
    ) {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(graphStore, "graphStore");
        List<DiscoveredTransformation> transformations = graphStore.discoveredTransformations();
        SearchGraphDto raw = rawAssembler.assemble(
            graphStore.snapshot(),
            List.of(),
            graphStore.ruleCandidates(),
            graphStore.reusableRules().size(),
            transformations
        );
        SemanticSearchGraphDto global = semanticAssembler.assemble(
            raw,
            transformations,
            List.of(),
            SemanticGraphViewMode.SEMANTIC,
            false,
            true,
            false,
            64,
            8
        );
        List<DiscoverySemanticReportView.SemanticPath> paths = report.rows().stream()
            .map(row -> semanticPath(row, raw, transformations))
            .toList();
        int semanticNodeCount = paths.stream().mapToInt(path -> path.nodes().size()).sum();
        int semanticEdgeCount = paths.stream().mapToInt(path -> path.edges().size()).sum();
        int collapsedLowSignalCount = global.edges().stream()
            .filter(edge -> edge.kind() == SemanticEdgeKind.LOW_SIGNAL_COLLAPSED)
            .mapToInt(SemanticGraphEdgeDto::hiddenStepCount)
            .sum();
        return new DiscoverySemanticReportView(
            "SemanticSearchGraphAssembler",
            global.stats().rawNodeCount(),
            global.stats().rawEdgeCount(),
            semanticNodeCount,
            semanticEdgeCount,
            global.stats().collapsedVariantCount(),
            collapsedLowSignalCount,
            paths
        );
    }

    private DiscoverySemanticReportView.SemanticPath semanticPath(
        DeterministicDiscoveryExperimentRunner.SeedRunReport row,
        SearchGraphDto raw,
        List<DiscoveredTransformation> transformations
    ) {
        DiscoveredTransformation transformation = findTransformation(row, transformations);
        if (transformation == null) {
            return DiscoverySemanticReportView.fromReplayPaths(new DeterministicDiscoveryExperimentRunner.DiscoveryReport(
                List.of(row),
                new DeterministicDiscoveryExperimentRunner.DiscoveryMetrics(1, row.success() ? 1 : 0,
                    row.hypotheses().size(), row.counterexamples().size(), row.elapsedMillis(), row.memoryBytes()),
                0L
            )).paths().getFirst();
        }
        SemanticSearchGraphDto semantic = semanticAssembler.assemble(
            raw,
            List.of(transformation),
            List.of(),
            SemanticGraphViewMode.MAIN_PATH,
            false,
            false,
            false,
            16,
            6
        );
        List<DiscoverySemanticReportView.SemanticNode> nodes = semantic.nodes().stream()
            .sorted(Comparator.comparingInt(node -> firstReplayIndex(row.replayPath(), node.representativeExpression())))
            .map(node -> new DiscoverySemanticReportView.SemanticNode(node.id(), node.representativeExpression()))
            .toList();
        List<DiscoverySemanticReportView.SemanticEdge> edges = semantic.edges().stream()
            .map(edge -> new DiscoverySemanticReportView.SemanticEdge(
                edge.from(),
                edge.to(),
                edge.kind() == SemanticEdgeKind.LOW_SIGNAL_COLLAPSED ? "low-signal collapsed" : "semantic main step",
                edge.kind().name(),
                edge.hiddenStepCount()
            ))
            .toList();
        return new DiscoverySemanticReportView.SemanticPath(row.seed().id(), nodes, edges);
    }

    private static DiscoveredTransformation findTransformation(
        DeterministicDiscoveryExperimentRunner.SeedRunReport row,
        List<DiscoveredTransformation> transformations
    ) {
        if (row.replayPath().isEmpty()) {
            return null;
        }
        String first = row.replayPath().getFirst();
        String last = row.replayPath().getLast();
        return transformations.stream()
            .filter(t -> sameEndpoint(t.originalExpression(), first) && sameEndpoint(t.improvedExpression(), last))
            .findFirst()
            .or(() -> transformations.stream()
                .filter(t -> t.steps().stream().anyMatch(step -> row.replayPath().contains(step.beforeExpression())
                    || row.replayPath().contains(step.afterExpression())))
                .findFirst())
            .orElse(null);
    }

    private static boolean sameEndpoint(String left, String right) {
        return Objects.equals(left, right);
    }

    private static int firstReplayIndex(List<String> replayPath, String expression) {
        int index = replayPath.indexOf(expression);
        return index < 0 ? Integer.MAX_VALUE : index;
    }
}
