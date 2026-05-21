package de.regelsuche.analyze;

import java.util.List;

/**
 * Result of {@link MoveAnalysisService#analyze}. Modelled after a chess-style
 * analysis: a "best move", an ordered list of "alternative moves", a list of
 * the moves that lead to dead-ends, a short human-readable reason for the
 * recommendation and the rule that contributed the most score reduction
 * across the whole graph.
 */
public record MoveAnalysisDto(
    String expression,
    Move bestMove,
    List<Move> alternatives,
    List<Move> deadEnds,
    String reason,
    String mostUsefulRule
) {
    public MoveAnalysisDto {
        alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        deadEnds = deadEnds == null ? List.of() : List.copyOf(deadEnds);
        reason = reason == null ? "" : reason;
        mostUsefulRule = mostUsefulRule == null ? "" : mostUsefulRule;
    }

    public record Move(
        String ruleId,
        String ruleKind,
        String toExpression,
        String toLatex,
        int scoreDelta,
        boolean deadEnd,
        boolean isBest,
        boolean equivalencePreserving,
        List<String> assumptions,
        List<String> pathIds
    ) {
        public Move {
            assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
            pathIds = pathIds == null ? List.of() : List.copyOf(pathIds);
        }
    }
}
