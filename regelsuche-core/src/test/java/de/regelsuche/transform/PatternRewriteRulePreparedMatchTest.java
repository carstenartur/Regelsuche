package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PatternRewriteRulePreparedMatchTest {
    private final PatternRewriteRule rule = new PatternRewriteRule(
        "remove_additive_zero",
        PatternExpr.op(BinaryOperator.ADD, PatternExpr.var("A"), PatternExpr.num(0)),
        PatternExpr.var("A"),
        RewriteKind.NORMALIZE,
        false,
        -1,
        true);

    @Test
    void matchingThenApplyingTheSameImmutableSubtreePreservesSemantics() {
        Expr expression = new ExpressionParser().parseTerm("x + 0");

        assertTrue(rule.matches(expression));
        assertEquals("x", ExpressionFormatter.format(rule.apply(expression)));
    }

    @Test
    void applyingAnotherSubtreeCannotReuseThePreviousBinding() {
        ExpressionParser parser = new ExpressionParser();
        Expr first = parser.parseTerm("x + 0");
        Expr second = parser.parseTerm("y + 0");

        assertTrue(rule.matches(first));
        assertEquals("y", ExpressionFormatter.format(rule.apply(second)));
    }

    @Test
    void failedMatchClearsAnEarlierPreparedBinding() {
        ExpressionParser parser = new ExpressionParser();
        Expr matching = parser.parseTerm("x + 0");
        Expr nonMatching = parser.parseTerm("y * 1");

        assertTrue(rule.matches(matching));
        assertFalse(rule.matches(nonMatching));
        assertThrows(IllegalArgumentException.class, () -> rule.apply(nonMatching));
    }

    @Test
    void preparedBindingsAreIsolatedAcrossConcurrentThreads() throws Exception {
        CountDownLatch bothMatched = new CountDownLatch(2);
        CountDownLatch applyNow = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<String> first = executor.submit(() ->
                matchThenAwaitAndApply("x + 0", bothMatched, applyNow));
            Future<String> second = executor.submit(() ->
                matchThenAwaitAndApply("y + 0", bothMatched, applyNow));

            assertTrue(bothMatched.await(5, TimeUnit.SECONDS));
            applyNow.countDown();
            assertEquals("x", first.get(5, TimeUnit.SECONDS));
            assertEquals("y", second.get(5, TimeUnit.SECONDS));
        }
    }

    private String matchThenAwaitAndApply(
        String source,
        CountDownLatch bothMatched,
        CountDownLatch applyNow
    ) throws Exception {
        Expr expression = new ExpressionParser().parseTerm(source);
        assertTrue(rule.matches(expression));
        bothMatched.countDown();
        assertTrue(applyNow.await(5, TimeUnit.SECONDS));
        return ExpressionFormatter.format(rule.apply(expression));
    }
}
