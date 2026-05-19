package de.regelsuche.api;

import de.regelsuche.graph.ExpressionGraphStore;
import de.regelsuche.inventory.RuleInventoryRepository;
import de.regelsuche.mining.CandidateProofStatus;
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

    public List<RuleCandidateDto> ruleCandidateDtos() {
        return ruleCandidates().stream().map(RuleCandidateDto::from).toList();
    }

    public List<RuleCandidate> ruleCandidatesByStatus(CandidateProofStatus status) {
        return ruleCandidates().stream()
            .filter(candidate -> candidate.proofStatus() == status)
            .toList();
    }

    public List<RuleInventoryDto> reusableRules() {
        return inventoryRepository.findAll().stream().map(RuleInventoryDto::from).toList();
    }

    public List<RuleInventoryDto> reusableRulesByStatus(CandidateProofStatus status) {
        return inventoryRepository.findByStatus(status).stream().map(RuleInventoryDto::from).toList();
    }
}
