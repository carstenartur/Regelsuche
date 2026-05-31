package de.regelsuche.search.convergence;

import java.util.List;
import java.util.Set;

/** Bounded generated search-space evidence around a convergent discovery. */
public record SearchSpaceSubgraph(
    String inputExpression,
    List<Node> nodes,
    List<Edge> edges,
    int maxDepth
) {
    public SearchSpaceSubgraph {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }

    public record Node(
        String id,
        String expression,
        Set<PathMembership> pathMembership,
        Set<RuleFamily> ruleFamilies,
        int depth,
        int score,
        boolean isTarget,
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
}
