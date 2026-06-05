package de.regelsuche.moves.report;

import de.regelsuche.moves.MoveOrdinal;
import de.regelsuche.moves.RewriteMove;
import de.regelsuche.moves.enumerate.Depth1MoveEnumerator;
import java.util.List;

/**
 * Serializable model behind {@code move-tree-report.json} / {@code .md}.
 *
 * @param scenarioId            the scenario this report belongs to
 * @param nodes                 nodes of the move tree
 * @param edges                 edges of the move tree (each carrying a {@link RewriteMove})
 * @param successfulPathMoves   the moves applied along the successful path
 * @param depth1Candidates      the first depth-1 candidate moves for the start expression
 * @param macroMoves            macro moves available at the start expression
 * @param unresolvedParameters  moves whose parameters are still unresolved
 */
public record MoveTreeReport(
        String scenarioId,
        List<MoveNode> nodes,
        List<MoveEdge> edges,
        List<RewriteMove> successfulPathMoves,
        List<Depth1MoveEnumerator.CandidateMove> depth1Candidates,
        List<RewriteMove> macroMoves,
        List<UnresolvedParameterEntry> unresolvedParameters) {

    public MoveTreeReport {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        successfulPathMoves = successfulPathMoves == null ? List.of() : List.copyOf(successfulPathMoves);
        depth1Candidates = depth1Candidates == null ? List.of() : List.copyOf(depth1Candidates);
        macroMoves = macroMoves == null ? List.of() : List.copyOf(macroMoves);
        unresolvedParameters = unresolvedParameters == null ? List.of() : List.copyOf(unresolvedParameters);
    }

    /**
     * A node in the move tree.
     *
     * @param nodeId               stable node id (canonical expression)
     * @param expression           raw expression
     * @param canonicalExpression  canonicalised expression
     * @param depth                node depth in the tree
     * @param score                heuristic score of the node
     */
    public record MoveNode(String nodeId, String expression, String canonicalExpression, int depth, double score) {
    }

    /**
     * An edge in the move tree.
     *
     * @param fromNodeId    source node id
     * @param toNodeId      target node id
     * @param rewriteMove   the rewrite move applied along this edge
     * @param ordinalPath   the chain of move ordinals from the root to this edge
     * @param selectedPath  whether this edge is part of the successful selected path
     * @param macroExpanded whether the edge represents an expanded macro move
     * @param prunedReason  reason the edge was pruned, when known
     */
    public record MoveEdge(
            String fromNodeId,
            String toNodeId,
            RewriteMove rewriteMove,
            List<MoveOrdinal> ordinalPath,
            boolean selectedPath,
            boolean macroExpanded,
            String prunedReason) {
        public MoveEdge {
            ordinalPath = ordinalPath == null ? List.of() : List.copyOf(ordinalPath);
            prunedReason = prunedReason == null ? "" : prunedReason;
        }
    }

    /**
     * An entry describing a move whose parameters are still unresolved.
     *
     * @param moveId the move id
     * @param kind   the move kind name
     * @param ruleId the rule id
     */
    public record UnresolvedParameterEntry(String moveId, String kind, String ruleId) {
    }
}
