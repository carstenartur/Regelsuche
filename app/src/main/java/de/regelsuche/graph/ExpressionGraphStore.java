package de.regelsuche.graph;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.mining.RuleCandidate;
import java.util.List;

public interface ExpressionGraphStore extends AutoCloseable {
    void saveNode(String expression, int complexity);

    void saveEdge(GraphEdge edge);

    GraphSnapshot snapshot();

    default void saveDiscoveredTransformation(DiscoveredTransformation transformation) {
    }

    default List<DiscoveredTransformation> discoveredTransformations() {
        return List.of();
    }

    default void saveRuleCandidate(RuleCandidate candidate) {
    }

    default List<RuleCandidate> ruleCandidates() {
        return List.of();
    }

    default void saveReusableRule(ReusableRule rule) {
    }

    default List<ReusableRule> reusableRules() {
        return List.of();
    }

    @Override
    default void close() {
    }
}
