package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.TypedMoveSearch;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CheckedDomainSizeReceiptsTest {
    private final CheckedDomainSizeReceipts receipts = new CheckedDomainSizeReceipts(CheckedLearnedSchemaModel.Bounds.defaults());

    @Test void onlyTheIdenticalSuccessfullyCheckedExpressionGetsACheapCount() {
        var source = state("x+y");
        var copy = state("x+y");
        var first = receipts.evaluate(source);
        assertEquals(3, first.value());
        assertEquals(3, receipts.evaluate(copy).value());
        assertTrue(receipts.evaluate(source).work() < first.work());
        assertNull(receipts.size(parse("x+y"), new CheckedSchemaSupport.Work()));
        assertNull(new CheckedDomainSizeReceipts(CheckedLearnedSchemaModel.Bounds.defaults())
            .size(source.expression(), new CheckedSchemaSupport.Work()));
    }

    @Test void rejectedDomainsPayTheAttemptAndTheOrdinaryNodeCount() {
        var input = state("x/y");
        var score = receipts.evaluate(input);
        assertEquals(3, score.value());
        assertTrue(score.work() > 3);
        assertNull(receipts.size(input.expression(), new CheckedSchemaSupport.Work()));
    }

    @Test void retainingMoreThanTheBoundEvictsAndPaysForClearing() {
        var first = state("v0");
        receipts.evaluate(first);
        long beforeEviction = 0;
        for (int i = 1; i < 64; i++) beforeEviction = receipts.evaluate(state("v" + i)).work();
        assertTrue(receipts.evaluate(state("v64")).work() > beforeEviction + 63);
        assertNull(receipts.size(first.expression(), new CheckedSchemaSupport.Work()));
    }

    private static TypedMoveSearch.State state(String expression) {
        return new TypedMoveSearch.State(parse(expression), 0, 0, "", List.of(), Set.of(), 0);
    }

    private static Expr parse(String text) { return new ExpressionParser().parseExactTerm(text).expression(); }
}
