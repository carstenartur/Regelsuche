package de.regelsuche.api;

import de.regelsuche.export.ExportBundle;
import de.regelsuche.graph.ExpressionGraphStore;
import de.regelsuche.inventory.RuleInventoryRepository;
import java.util.List;

/**
 * Reads the current export bundle (discovered transformations, candidates and
 * reusable rules) so that downstream UIs/APIs can offer a single endpoint.
 */
public class ExportQueryService {
    private final ExpressionGraphStore graphStore;
    private final RuleInventoryRepository inventoryRepository;

    public ExportQueryService(ExpressionGraphStore graphStore, RuleInventoryRepository inventoryRepository) {
        this.graphStore = graphStore;
        this.inventoryRepository = inventoryRepository;
    }

    public ExportBundle bundle() {
        return ExportBundle.of(
            List.copyOf(graphStore.discoveredTransformations()),
            List.copyOf(graphStore.ruleCandidates()),
            List.copyOf(inventoryRepository.findAll())
        );
    }

    public ExportBundleDto bundleDto() {
        return ExportBundleDto.from(bundle());
    }
}
