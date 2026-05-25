package de.regelsuche.analyze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.api.searchgraph.SearchGraphDto;
import de.regelsuche.api.searchgraph.SearchGraphEdgeDto;
import de.regelsuche.api.searchgraph.SearchGraphNodeDto;
import de.regelsuche.api.searchgraph.SearchGraphStatsDto;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.transform.RewriteKind;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MoveAnalysisServiceTest {

    @Test
    void picksMoveWithLargestScoreReductionAsBest() {
        SearchGraphNodeDto start = node("n0", "x*(x+1)", 10, false, false);
        SearchGraphNodeDto better = node("n1", "x^2+x", 4, true, false);
        SearchGraphNodeDto deadEnd = node("n2", "x*(x+1)*1", 12, false, true);
        SearchGraphEdgeDto distribute = edge("n0", "n1", "distribute", -6, RewriteKind.EXPAND, true);
        SearchGraphEdgeDto multiplyOne = edge("n0", "n2", "multiply_one", 2, RewriteKind.NORMALIZE, true);
        SearchGraphDto graph = new SearchGraphDto(
            List.of(start, better, deadEnd),
            List.of(distribute, multiplyOne),
            List.of(),
            new SearchGraphStatsDto(3, 2, 1, 4, 1.0, 1, Map.of(), List.of(), 0, 0)
        );

        MoveAnalysisDto result = new MoveAnalysisService().analyze(graph, "x*(x+1)");

        assertNotNull(result.bestMove());
        assertEquals("distribute", result.bestMove().ruleId());
        assertEquals(-6, result.bestMove().scoreDelta());
        assertEquals(1, result.alternatives().size());
        assertEquals("multiply_one", result.alternatives().get(0).ruleId());
        assertEquals(1, result.deadEnds().size());
        assertEquals("multiply_one", result.deadEnds().get(0).ruleId());
        assertFalse(result.reason().isBlank());
        assertEquals("distribute", result.mostUsefulRule());
    }

    @Test
    void leafExpressionHasNoBestMove() {
        SearchGraphNodeDto leaf = node("leaf", "x", 1, true, false);
        SearchGraphDto graph = new SearchGraphDto(
            List.of(leaf), List.of(), List.of(),
            new SearchGraphStatsDto(1, 0, 0, 1, 0.0, 0, Map.of(), List.of(), 0, 0));
        MoveAnalysisDto result = new MoveAnalysisService().analyze(graph, "x");
        assertTrue(result.alternatives().isEmpty());
        assertTrue(result.reason().contains("Blatt"));
    }

    @Test
    void unknownExpressionReturnsClearMessage() {
        SearchGraphDto graph = new SearchGraphDto(List.of(), List.of(), List.of(),
            new SearchGraphStatsDto(0, 0, 0, 0, 0.0, 0, Map.of(), List.of(), 0, 0));
        MoveAnalysisDto result = new MoveAnalysisService().analyze(graph, "nope");
        assertTrue(result.reason().contains("nicht im Suchgraph"));
    }

    private static SearchGraphNodeDto node(String id, String expr, int score, boolean best, boolean deadEnd) {
        return new SearchGraphNodeDto(id, expr, expr, score, 0, 1, best, deadEnd,
            CandidateProofStatus.OBSERVED, null);
    }

    private static SearchGraphEdgeDto edge(String from, String to, String ruleId, int scoreDelta,
                                           RewriteKind kind, boolean equiv) {
        return new SearchGraphEdgeDto(from, to, ruleId, kind, scoreDelta, List.of(), List.of("p1"), equiv);
    }
}
