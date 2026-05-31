package de.regelsuche.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RewriteKind;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleConflictDetectorTest {
    private static PatternExpr differenceOfSquares(String left, String right) {
        return PatternExpr.op(
            BinaryOperator.SUB,
            PatternExpr.op(BinaryOperator.POW, PatternExpr.var(left), PatternExpr.num(2)),
            PatternExpr.op(BinaryOperator.POW, PatternExpr.var(right), PatternExpr.num(2))
        );
    }

    @Test
    void detectsCompetingRulesWithStructurallyIdenticalSourcePatterns() {
        PatternRewriteRule first = new PatternRewriteRule(
            "first", differenceOfSquares("A", "B"), PatternExpr.var("A"));
        PatternRewriteRule second = new PatternRewriteRule(
            "second", differenceOfSquares("X", "Y"), PatternExpr.var("X"));

        List<RuleConflictDetector.RuleConflict> conflicts = RuleConflictDetector.detect(List.of(
            new RuleConflictDetector.ConflictCandidate("first", first),
            new RuleConflictDetector.ConflictCandidate("second", second)
        ));

        assertEquals(1, conflicts.size());
        assertTrue(conflicts.get(0).ruleIds().containsAll(List.of("first", "second")));
    }

    @Test
    void doesNotReportDistinctSourcePatternsAsConflicts() {
        PatternRewriteRule difference = new PatternRewriteRule(
            "difference", differenceOfSquares("A", "B"), PatternExpr.var("A"));
        PatternRewriteRule square = new PatternRewriteRule(
            "square",
            PatternExpr.op(BinaryOperator.POW,
                PatternExpr.op(BinaryOperator.ADD, PatternExpr.var("A"), PatternExpr.var("B")),
                PatternExpr.num(2)),
            PatternExpr.var("A"),
            RewriteKind.EXPAND, true, 0, true);

        List<RuleConflictDetector.RuleConflict> conflicts = RuleConflictDetector.detect(List.of(
            new RuleConflictDetector.ConflictCandidate("difference", difference),
            new RuleConflictDetector.ConflictCandidate("square", square)
        ));

        assertTrue(conflicts.isEmpty());
    }
}
