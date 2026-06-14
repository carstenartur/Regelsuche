package de.regelsuche.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.Expr;
import de.regelsuche.moves.RewriteMoveKind;
import de.regelsuche.moves.enumerate.Depth1MoveEnumerator.CandidateMove;
import de.regelsuche.moves.enumerate.TreeLocalMoveEnumerator;
import de.regelsuche.moves.enumerate.TreePosition;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class LocalRewriteApplierTest {

    private final TreeLocalMoveEnumerator enumerator = new TreeLocalMoveEnumerator();
    private final LocalRewriteApplier applier = new LocalRewriteApplier();
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void appliesRootRewriteToFullExpression() {
        LocalRewriteApplier.LocalRewriteResult result = applyCompleteSquare("x^2 + 6*x + 5", "root");

        assertTrue(result.success(), result.failureReason());
        assertEquals("x ^ 2 + 6 * x + 5", result.subtreeBefore());
        assertEquals("(x + 3) ^ 2 - 4", result.subtreeAfter());
        assertEquals("(x + 3) ^ 2 - 4", result.expressionAfter());
        assertEquals("COMPLETE_SQUARE", result.kind());
        assertEquals(2, result.bindings().size());
        assertRoundTrips(result);
    }

    @Test
    void appliesNestedRewriteInsideSinToFullExpression() {
        LocalRewriteApplier.LocalRewriteResult result = applyCompleteSquare(
                "sin(x^2 + 6*x + 5)",
                "000");

        assertTrue(result.success(), result.failureReason());
        assertEquals("x ^ 2 + 6 * x + 5", result.subtreeBefore());
        assertEquals("(x + 3) ^ 2 - 4", result.subtreeAfter());
        assertEquals("sin((x + 3) ^ 2 - 4)", result.expressionAfter());
        assertRoundTrips(result);
    }

    @Test
    void appliesRewriteOnLeftSideOfBinaryExpression() {
        LocalRewriteApplier.LocalRewriteResult result = applyCompleteSquare(
                "(x^2 + 6*x + 5) + y",
                "000");

        assertTrue(result.success(), result.failureReason());
        assertEquals("(x + 3) ^ 2 - 4 + y", result.expressionAfter());
        assertRoundTrips(result);
    }

    @Test
    void appliesRewriteOnRightSideOfBinaryExpression() {
        LocalRewriteApplier.LocalRewriteResult result = applyCompleteSquare(
                "y + (x^2 + 6*x + 5)",
                "001");

        assertTrue(result.success(), result.failureReason());
        assertEquals("y + (x + 3) ^ 2 - 4", result.expressionAfter());
        assertRoundTrips(result);
    }

    @Test
    void stalePositionReturnsFailure() {
        List<TreeLocalMoveEnumerator.LocalCandidateMove> moves = enumerator.enumerate("sin(x^2 + 6*x + 5)");
        TreeLocalMoveEnumerator.LocalCandidateMove nested = moves.stream()
                .filter(candidate -> "000".equals(candidate.position().pathKey()))
                .filter(candidate -> candidate.move().kind() == RewriteMoveKind.COMPLETE_SQUARE)
                .findFirst()
                .orElseThrow();
        TreePosition stale = new TreePosition(List.of(1), nested.position().text());

        LocalRewriteApplier.LocalRewriteResult result = applier.apply(
                "sin(x^2 + 6*x + 5)",
                stale,
                nested.move());

        assertFalse(result.success());
        assertNotNull(result.failureReason());
        assertTrue(result.expressionAfter() == null || result.expressionAfter().isBlank());
    }

    private LocalRewriteApplier.LocalRewriteResult applyCompleteSquare(String expression, String pathKey) {
        List<TreeLocalMoveEnumerator.LocalCandidateMove> matches = enumerator.enumerate(expression).stream()
                .filter(candidate -> pathKey.equals(candidate.position().pathKey()))
                .filter(candidate -> candidate.move().kind() == RewriteMoveKind.COMPLETE_SQUARE)
                .toList();
        assertFalse(matches.isEmpty(), () -> "no COMPLETE_SQUARE matches at " + pathKey);
        TreePosition position = matches.getFirst().position();
        List<CandidateMove> candidates = matches.stream()
                .map(TreeLocalMoveEnumerator.LocalCandidateMove::move)
                .toList();
        return applier.apply(expression, position, candidates);
    }

    private void assertRoundTrips(LocalRewriteApplier.LocalRewriteResult result) {
        assertTrue(result.success(), result.failureReason());
        assertNotNull(result.expressionAfter());

        Expr parsedAfter = parser.parseTerm(result.expressionAfter());
        String renderedAfter = ExpressionFormatter.format(parsedAfter);
        Expr reparsedAfter = parser.parseTerm(renderedAfter);

        assertEquals(parsedAfter, reparsedAfter);
        assertEquals(renderedAfter, ExpressionFormatter.format(reparsedAfter));
    }
}
