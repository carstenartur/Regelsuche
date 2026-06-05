package de.regelsuche.moves;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.moves.enumerate.Depth1MoveEnumerator;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchState;
import java.util.List;
import org.junit.jupiter.api.Test;

class MoveCandidateTransformationEngineTest {
    private final MoveCandidateTransformationEngine engine = new MoveCandidateTransformationEngine(
        MoveCandidateTransformationEngine.defaultClassicEngine(),
        new Depth1MoveEnumerator()
    );

    @Test
    void sameInputProducesStableMoveIds() {
        List<String> first = engine.moveCandidates("x^2 + 6*x + 5").stream()
            .map(candidate -> candidate.move().moveId())
            .toList();
        List<String> second = engine.moveCandidates("x^2 + 6*x + 5").stream()
            .map(candidate -> candidate.move().moveId())
            .toList();

        assertEquals(first, second);
        assertFalse(first.isEmpty());
    }

    @Test
    void cancellationCandidateAppearsInSearchAtDepthOne() {
        SearchProblem problem = new SearchProblem(
            "x - 1 = 0",
            engine,
            new ExpressionScorer(),
            new ExpressionCanonicalizer(),
            new SearchHeuristic(1, 20, 1, 2, 20, 10)
        );

        SearchState state = new BestFirstSearchStrategy().search(problem).stream()
            .filter(candidate -> candidate.depth() == 1)
            .filter(candidate -> candidate.expression().contains("+ 1 = 0 + 1"))
            .findFirst()
            .orElseThrow();

        assertTrue(state.assumptions().stream().anyMatch(assumption -> assumption.equals("move.parameter.cancel=+1")));
        assertTrue(state.appliedRuleIds().contains("equation_add_both_sides"));
    }

    @Test
    void completeSquareCandidateCarriesShiftAndResidueMetadata() {
        MoveCandidateTransformationEngine.MoveBackedTransformation candidate = engine.moveCandidates("x^2 + 6*x + 5")
            .stream()
            .filter(move -> move.transformation().transformedExpression().equals("(x + 3) ^ 2 - 4"))
            .findFirst()
            .orElseThrow();

        assertEquals("complete_square_bridge", candidate.move().ruleId());
        assertEquals("complete_square_bridge", candidate.move().operatorId());
        assertEquals(List.of("shift=3", "residue=-4"), candidate.move().parameters().stream()
            .map(parameter -> parameter.name() + "=" + parameter.value())
            .toList());
    }

    @Test
    void repeatedSubexpressionCandidatesIncludeCommonSubexpressionAndSubstitutionCases() {
        List<MoveCandidateTransformationEngine.MoveBackedTransformation> repeated =
            engine.moveCandidates("(x + 1)^2 - (x + 1)");
        List<MoveCandidateTransformationEngine.MoveBackedTransformation> common =
            engine.moveCandidates("x*(y+1) + z*(y+1)");

        assertTrue(repeated.stream().anyMatch(candidate ->
            candidate.move().parameters().stream().anyMatch(parameter -> parameter.value().equals("x + 1"))));
        assertTrue(common.stream().anyMatch(candidate ->
            candidate.move().parameters().stream().anyMatch(parameter -> parameter.value().equals("y + 1"))));
    }
}
