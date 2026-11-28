package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.NativeNodeCountObjective;
import de.regelsuche.search.moves.TypedMoveSearch;
import de.regelsuche.search.moves.TypedSourceOnlySearch;
import de.regelsuche.search.program.AstExpressionValidation;
import de.regelsuche.transform.AstRewriteTransport;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SubtreeNodeCountsTest {
    private static final ExpressionParser PARSER = new ExpressionParser();

    @Test void identicalChildrenReuseCountsAndEachOccurrenceContributesItsFullSize() {
        String formula = "a*(b+c*d)+e";
        var shared = PARSER.parseExactTerm(formula).expression();
        var equalCopy = PARSER.parseExactTerm(formula).expression();
        var cache = new SubtreeNodeCounts();
        var firstRoot = state(new FunctionExpr("f", List.of(shared, shared)));
        var fresh = new SubtreeNodeCounts().evaluate(state(new FunctionExpr("f", List.of(shared, equalCopy))));
        var first = cache.evaluate(firstRoot);
        assertEquals(NativeNodeCountObjective.INSTANCE.evaluate(firstRoot).value(), first.value());
        assertTrue(first.work() < fresh.work());
        assertEquals(new TypedSourceOnlySearch.Score(first.value(), 1), cache.evaluate(firstRoot));

        var reused = state(new FunctionExpr("g", shared));
        var copy = state(new FunctionExpr("g", equalCopy));
        var reusedScore = cache.evaluate(reused);
        var copyScore = cache.evaluate(copy);
        assertEquals(NativeNodeCountObjective.INSTANCE.evaluate(copy).value(), copyScore.value());
        assertEquals(reusedScore.value(), copyScore.value());
        assertTrue(reusedScore.work() < copyScore.work());
    }

    @Test void occurrenceLimitSurvivesSharedDeepAndWideInputs() {
        var leaf = PARSER.parseExactTerm("x").expression();
        var cache = new SubtreeNodeCounts();
        var atLimit = state(new FunctionExpr("f", Collections.nCopies(AstRewriteTransport.MAXIMUM_NODES - 1, leaf)));
        assertEquals(AstRewriteTransport.MAXIMUM_NODES, cache.evaluate(atLimit).value());
        var tooWide = state(new FunctionExpr("f", Collections.nCopies(AstRewriteTransport.MAXIMUM_NODES, leaf)));
        assertThrows(AstExpressionValidation.InvalidExpression.class, () -> cache.evaluate(tooWide));
        Expr repeated = leaf;
        for (int i = 0; i < 14; i++) repeated = new BinaryExpr(repeated, BinaryOperator.ADD, repeated);
        var tooLarge = state(new FunctionExpr("f", repeated));
        assertThrows(AstExpressionValidation.InvalidExpression.class, () -> cache.evaluate(tooLarge));
        Expr deep = leaf;
        for (int i = 1; i < AstRewriteTransport.MAXIMUM_NODES; i++) deep = new FunctionExpr("f", deep);
        assertEquals(AstRewriteTransport.MAXIMUM_NODES, cache.evaluate(state(deep)).value());
    }

    private static TypedMoveSearch.State state(Expr expression) {
        return new TypedMoveSearch.State(expression, 0, 0, "", List.of(), Set.of(), 0);
    }
}
