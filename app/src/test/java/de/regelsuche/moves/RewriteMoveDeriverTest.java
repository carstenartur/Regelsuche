package de.regelsuche.moves;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RewriteMoveDeriverTest {

    private final RewriteMoveDeriver deriver = new RewriteMoveDeriver();

    @Test
    void completeSquareBridgeMapsToCompleteSquare() {
        RewriteMove move = deriver.derive(new RewriteMoveDeriver.MoveDerivationRequest(
                "x^2 + 6*x + 5", "(x + 3)^2 - 4", "complete_square_bridge", "", List.of()));
        assertEquals(RewriteMoveKind.COMPLETE_SQUARE, move.kind());
        assertEquals("complete_square_bridge", move.ruleId());
        assertFalse(move.sourceExpression().isBlank());
        assertFalse(move.targetExpression().isBlank());
        assertFalse(move.canonicalBefore().isBlank());
        assertFalse(move.canonicalAfter().isBlank());
    }

    @Test
    void substitutionIntroductionProducesPlaceholderParameter() {
        RewriteMove move = deriver.derive(new RewriteMoveDeriver.MoveDerivationRequest(
                "sin(x)^2 + 2*sin(x) + 1",
                "A^2 + 2*A + 1",
                "substitution_introduction",
                "",
                List.of(
                        "substitution.placeholder.A=sin(x)",
                        "substitution.occurrences.A=2",
                        "substitution.substituted=A^2 + 2*A + 1")));
        assertEquals(RewriteMoveKind.SUBSTITUTE_INTRODUCE, move.kind());
        assertTrue(move.parameters().stream()
                .anyMatch(parameter -> parameter.kind() == MoveParameterKind.PLACEHOLDER
                        && parameter.name().equals("A")
                        && parameter.value().equals("sin(x)")));
        assertFalse(move.hasUnresolvedParameters());
    }

    @Test
    void commonSubexpressionDiscoveryMapsToCommonSubexpression() {
        RewriteMove move = deriver.derive(new RewriteMoveDeriver.MoveDerivationRequest(
                "x*(y+1) + z*(y+1)", "(y+1)*(x+z)", "common_subexpression_discovery", "", List.of()));
        assertEquals(RewriteMoveKind.COMMON_SUBEXPRESSION, move.kind());
    }

    @Test
    void sophieGermainBridgeMapsToSophieGermain() {
        RewriteMove move = deriver.derive(new RewriteMoveDeriver.MoveDerivationRequest(
                "x^4 + 4*y^4", "(x^2 + 2*y^2 - 2*x*y)*(x^2 + 2*y^2 + 2*x*y)",
                "sophie_germain_bridge", "", List.of()));
        assertEquals(RewriteMoveKind.SOPHIE_GERMAIN, move.kind());
    }

    @Test
    void differenceOfSquaresPreparationMapsToDifferenceOfSquares() {
        RewriteMove move = deriver.derive(new RewriteMoveDeriver.MoveDerivationRequest(
                "x^4 + 4", "(x^2 - 2*x + 2)*(x^2 + 2*x + 2)",
                "difference_of_squares_preparation", "", List.of()));
        assertEquals(RewriteMoveKind.DIFFERENCE_OF_SQUARES, move.kind());
    }

    @Test
    void unknownRuleIdYieldsUnknownAndUnresolvedTag() {
        RewriteMove move = deriver.derive(new RewriteMoveDeriver.MoveDerivationRequest(
                "a + b", "b + a", "totally_made_up_rule", "", List.of()));
        assertEquals(RewriteMoveKind.UNKNOWN, move.kind());
        assertTrue(move.parameters().isEmpty());
        assertTrue(move.hasUnresolvedParameters());
    }

    @Test
    void linearOffsetSimplifyMapsToNormalize() {
        RewriteMove move = deriver.derive(new RewriteMoveDeriver.MoveDerivationRequest(
                "x + 1 - 1", "x", "ast_linear_offset_simplify", "", List.of()));
        assertEquals(RewriteMoveKind.NORMALIZE, move.kind());
    }
}
