package de.regelsuche.search.convergence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bounded, reconstructed search-space subgraph around a convergent discovery.
 *
 * <p>The graph is a <em>bounded replay</em> of an explored search space: some nodes were
 * originally explored by the search ({@link Node#originallyExplored()} is {@code true}),
 * while others are reconstructed for visualization through a bounded expansion of the
 * problem (limited by {@link #maxDepth()} and {@link #maxStates()}). It is therefore a
 * reconstructed subgraph rather than a complete record of the search.
 */
public record SearchSpaceSubgraph(
    String inputExpression,
    List<Node> nodes,
    List<Edge> edges,
    int maxDepth,
    int maxStates
) {
    public SearchSpaceSubgraph {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }

    /** Backwards-compatible constructor that infers {@code maxStates} from the node count. */
    public SearchSpaceSubgraph(String inputExpression, List<Node> nodes, List<Edge> edges, int maxDepth) {
        this(inputExpression, nodes, edges, maxDepth, nodes.size());
    }

    /**
     * Roles describing how a state relates to the explored equivalence classes.
     *
     * <p>The role is intentionally neutral: a state is only labelled a
     * {@link #CONVERGENCE_TARGET} when several distinct transformation paths actually
     * converge on it. Otherwise canonical states stay {@link #CANONICAL_REPRESENTATIVE}
     * or {@link #EQUIVALENT_STATE} so readers do not over-interpret the diagram.
     */
    public enum StateRole {
        /** The root/input expression the search started from. */
        ROOT,
        /** An ordinary explored or reconstructed search state. */
        SEARCH_STATE,
        /** A canonical representative of an equivalence class (no proven convergence). */
        CANONICAL_REPRESENTATIVE,
        /** A non-canonical member of an equivalence class shared with another node. */
        EQUIVALENT_STATE,
        /** A state where several distinct transformation paths actually converge. */
        CONVERGENCE_TARGET
    }

    public record Node(
        String id,
        String expression,
        String canonicalKey,
        Set<PathMembership> pathMembership,
        Set<RuleFamily> ruleFamilies,
        StateRole role,
        int depth,
        int score,
        boolean isTarget,
        boolean isConvergencePoint,
        boolean originallyExplored,
        boolean isOnDidacticPath,
        boolean isOnMacroPath,
        boolean isDeadEnd,
        boolean notSelected
    ) {
        public Node {
            pathMembership = Set.copyOf(pathMembership);
            ruleFamilies = Set.copyOf(ruleFamilies);
        }
    }

    public record Edge(
        String id,
        String fromId,
        String toId,
        String ruleId,
        RuleFamily ruleFamily,
        int depth,
        int score,
        boolean isTarget,
        boolean isOnDidacticPath,
        boolean isOnMacroPath,
        boolean isDeadEnd,
        boolean notSelected
    ) {
    }

    public enum PathMembership {
        DIDACTIC,
        MACRO,
        TARGET,
        ALTERNATIVE
    }

    /** Number of states that were originally explored by the search (not reconstructed). */
    public long originallyExploredCount() {
        return nodes.stream().filter(Node::originallyExplored).count();
    }

    /** Number of states added only to reconstruct the bounded visualization. */
    public long reconstructedCount() {
        return nodes.size() - originallyExploredCount();
    }

    /** States that several distinct transformation paths actually converge on. */
    public List<Node> convergenceTargets() {
        return nodes.stream().filter(Node::isConvergencePoint).toList();
    }

    /**
     * Groups node ids by their canonical equivalence-class key, keeping only classes that
     * contain more than one rendered state.
     */
    public Map<String, List<String>> equivalenceClasses() {
        Map<String, List<String>> byKey = new LinkedHashMap<>();
        for (Node node : nodes) {
            byKey.computeIfAbsent(node.canonicalKey(), ignored -> new ArrayList<>()).add(node.id());
        }
        Map<String, List<String>> classes = new LinkedHashMap<>();
        byKey.forEach((key, ids) -> {
            if (ids.size() > 1) {
                classes.put(key, List.copyOf(ids));
            }
        });
        return classes;
    }

    /**
     * Returns a compact subgraph limited to at most {@code maxNodes} states (unless the
     * essential root/target/convergence states alone already exceed that budget). Essential
     * states are kept first, then as many selected-path states and alternatives as fit, so the
     * core convergence story stays intact. Edges that would dangle after pruning are dropped.
     */
    public SearchSpaceSubgraph compact(int maxNodes) {
        if (nodes.size() <= maxNodes) {
            return this;
        }
        Set<String> keep = new LinkedHashSet<>();
        // 1. Essential states: root, targets and true convergence points.
        addUntilBudget(keep, maxNodes, nodes.stream()
            .filter(node -> node.depth() == 0 || node.isTarget() || node.isConvergencePoint()));
        // 2. Selected-path states (didactic first, then macro) closest to the root.
        addUntilBudget(keep, maxNodes, nodes.stream()
            .filter(node -> node.isOnDidacticPath() || node.isOnMacroPath())
            .sorted((a, b) -> Integer.compare(a.depth(), b.depth())));
        // 3. Remaining alternatives by depth then score.
        addUntilBudget(keep, maxNodes, nodes.stream()
            .sorted((a, b) -> {
                int byDepth = Integer.compare(a.depth(), b.depth());
                if (byDepth != 0) {
                    return byDepth;
                }
                int byScore = Integer.compare(a.score(), b.score());
                return byScore != 0 ? byScore : a.id().compareTo(b.id());
            }));
        List<Node> keptNodes = nodes.stream().filter(node -> keep.contains(node.id())).toList();
        List<Edge> keptEdges = edges.stream()
            .filter(edge -> keep.contains(edge.fromId()) && keep.contains(edge.toId()))
            .toList();
        int compactMaxDepth = keptNodes.stream().mapToInt(Node::depth).max().orElse(0);
        return new SearchSpaceSubgraph(inputExpression, keptNodes, keptEdges, compactMaxDepth, maxNodes);
    }

    private void addUntilBudget(Set<String> keep, int maxNodes, java.util.stream.Stream<Node> candidates) {
        candidates.forEachOrdered(node -> {
            if (keep.size() < maxNodes || keep.contains(node.id())) {
                keep.add(node.id());
            }
        });
    }
}
