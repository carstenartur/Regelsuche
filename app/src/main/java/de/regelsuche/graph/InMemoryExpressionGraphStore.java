package de.regelsuche.graph;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.mining.RuleCandidate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryExpressionGraphStore implements ExpressionGraphStore {
    private final Set<String> nodes = ConcurrentHashMap.newKeySet();
    private final List<GraphEdge> edges = java.util.Collections.synchronizedList(new ArrayList<>());
    private final List<DiscoveredTransformation> transformations = java.util.Collections.synchronizedList(new ArrayList<>());
    private final List<RuleCandidate> ruleCandidates = java.util.Collections.synchronizedList(new ArrayList<>());
    private final List<ReusableRule> reusableRules = java.util.Collections.synchronizedList(new ArrayList<>());

    @Override
    public void saveNode(String expression, int complexity) {
        nodes.add(expression);
    }

    @Override
    public void saveEdge(GraphEdge edge) {
        edges.add(edge);
    }

    @Override
    public GraphSnapshot snapshot() {
        List<GraphEdge> edgeCopy;
        synchronized (edges) {
            edgeCopy = new ArrayList<>(edges);
        }
        return new GraphSnapshot(new ArrayList<>(nodes), edgeCopy);
    }

    @Override
    public void saveDiscoveredTransformation(DiscoveredTransformation transformation) {
        transformations.add(transformation);
    }

    @Override
    public List<DiscoveredTransformation> discoveredTransformations() {
        synchronized (transformations) {
            return List.copyOf(transformations);
        }
    }

    @Override
    public void saveRuleCandidate(RuleCandidate candidate) {
        ruleCandidates.add(candidate);
    }

    @Override
    public List<RuleCandidate> ruleCandidates() {
        synchronized (ruleCandidates) {
            return List.copyOf(ruleCandidates);
        }
    }

    @Override
    public void saveReusableRule(ReusableRule rule) {
        reusableRules.add(rule);
    }

    @Override
    public List<ReusableRule> reusableRules() {
        synchronized (reusableRules) {
            return List.copyOf(reusableRules);
        }
    }
}
