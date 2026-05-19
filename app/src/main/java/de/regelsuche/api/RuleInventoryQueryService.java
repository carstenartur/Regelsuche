package de.regelsuche.api;

import de.regelsuche.graph.ExpressionGraphStore;
import de.regelsuche.inventory.RuleInventoryRepository;
import de.regelsuche.mining.RuleCandidate;
import java.util.List;

public class RuleInventoryQueryService {
    private final ExpressionGraphStore graphStore;
    private final RuleInventoryRepository inventoryRepository;

    public RuleInventoryQueryService(ExpressionGraphStore graphStore, RuleInventoryRepository inventoryRepository) {
        this.graphStore = graphStore;
        this.inventoryRepository = inventoryRepository;
    }

    public List<RuleCandidate> ruleCandidates() {
        return graphStore.ruleCandidates();
    }

    public List<RuleInventoryDto> reusableRules() {
        return inventoryRepository.findAll().stream().map(RuleInventoryDto::from).toList();
    }
}
