package de.regelsuche.api;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.graph.ExpressionGraphStore;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class TransformationQueryService {
    private final ExpressionGraphStore graphStore;
    private final DefaultTransformationExportService exportService = new DefaultTransformationExportService();

    public TransformationQueryService(ExpressionGraphStore graphStore) {
        this.graphStore = graphStore;
    }

    public List<TransformationPathDto> allFoundImprovements() {
        return graphStore.discoveredTransformations().stream().map(TransformationPathDto::from).toList();
    }

    public List<TransformationPathDto> bestImprovements() {
        return graphStore.discoveredTransformations().stream()
            .sorted(Comparator.comparing(DiscoveredTransformation::totalImprovement).reversed())
            .map(TransformationPathDto::from)
            .toList();
    }

    public List<TransformationPathDto> improvementsForExpression(String expression) {
        return graphStore.discoveredTransformations().stream()
            .filter(path -> path.originalExpression().equals(expression) || path.improvedExpression().equals(expression))
            .map(TransformationPathDto::from)
            .toList();
    }

    public Optional<TransformationPathDto> pathById(String id) {
        return graphStore.discoveredTransformations().stream()
            .filter(path -> path.id().equals(id))
            .findFirst()
            .map(TransformationPathDto::from);
    }

    public String graphView(String id) {
        List<DiscoveredTransformation> paths = graphStore.discoveredTransformations().stream()
            .filter(path -> path.id().equals(id))
            .toList();
        return exportService.exportMermaid(paths);
    }
}
