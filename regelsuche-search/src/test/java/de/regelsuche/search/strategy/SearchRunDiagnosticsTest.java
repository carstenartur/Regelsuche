package de.regelsuche.search.strategy;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.program.RewriteCandidate;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy.Budget;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy.Problem;
import de.regelsuche.transform.MeasuredTransformationEngines;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SearchRunDiagnosticsTest {
    private static Transformation step(String id, String target) {
        return new Transformation(id, target, RewriteKind.NORMALIZE, false, 0, true, id + ":" + target);
    }
    private static WorkBudgetBestFirstSearchStrategy.Result run(TransformationEngine engine, String target, int candidates, long work) {
        return new WorkBudgetBestFirstSearchStrategy().search(new Problem("a", target,
            new SearchExpansionSource.Measured(MeasuredTransformationEngines.counting(engine)),
            new ExpressionScorer(), new ExpressionCanonicalizer(), Budget.primitive(2, 12, candidates, 2, work)));
    }

    @Test
    void generatedButNeverConsumedBranchesAreRetainedWithoutChangingTheReplay() {
        var result = run(expression -> expression.equals("a")
            ? List.of(step("good", "b"), step("other", "c"), step("other2", "d")) : List.of(), "b", 1, 1000);
        String before = result.toCanonicalJson();
        var diagnostic = SearchRunDiagnostics.observe(result, id -> id.startsWith("other") ? "other" : id, Set.of(), 1);
        assertTrue(result.reached());
        assertEquals(3, diagnostic.generatedSuccessors());
        assertEquals(1, diagnostic.consumedSuccessors());
        assertEquals(2, diagnostic.discardedSuccessors());
        assertEquals(2, diagnostic.unconsumedSuccessors());
        assertEquals(2L, diagnostic.matchesByRuleFamily().get("other"));
        assertEquals(1, diagnostic.firstHitDepth());
        assertEquals(result.metrics().chargedSearchWorkUnits() + 1, diagnostic.totalWork());
        assertEquals(before, result.toCanonicalJson());
        assertTrue(diagnostic.toCanonicalJson().contains("NOT_INTERNAL_MATCH_ATTEMPTS"));
    }

    @Test
    void naiveAdditionalBranchesCanSpendTheBudgetBeforeTheUsefulCandidateIsConsumed() {
        TransformationEngine base = expression -> expression.equals("a") ? List.of(step("good", "b")) : List.of();
        TransformationEngine naive = expression -> expression.equals("a") ? java.util.stream.Stream.concat(
            base.transform(expression).stream(), java.util.stream.IntStream.range(0, 100)
                .mapToObj(i -> step("learned-useless-" + i, "unused" + i))).toList() : List.of();
        assertTrue(run(base, "b", 32, 64).reached());
        var failed = run(naive, "b", 32, 64);
        var diagnostic = SearchRunDiagnostics.observe(failed, id -> id, Set.of(), 0);
        assertFalse(failed.reached());
        assertEquals(101, diagnostic.generatedSuccessors());
        assertEquals(0, diagnostic.consumedSuccessors());
        assertEquals(101, diagnostic.discardedSuccessors());
        assertEquals(0, diagnostic.deadEnds(), "work exhaustion is not a fully inspected dead end");
        assertEquals(-1, diagnostic.firstHitDepth());
    }

    @Test
    void duplicatesAndFullyInspectedDeadEndsAreDistinctFromBudgetPrunes() {
        var result = run(expression -> expression.equals("a")
            ? List.of(step("one", "b"), step("two", "b")) : List.of(), "c", 10, 1000);
        var diagnostic = SearchRunDiagnostics.observe(result, id -> "family", Set.of(), 1);
        assertEquals(1, diagnostic.duplicateSuccessors());
        assertEquals(1, diagnostic.deadEnds());
        assertEquals(1, diagnostic.discardedSuccessors());
        assertEquals(0, diagnostic.unconsumedSuccessors());
        assertEquals(1L, diagnostic.rejectionsByReason().get("DUPLICATE"));
    }

    @Test
    void learnedWitnessAttributionKeepsTwoPrimitiveEdgesBehindOneSearchEdge() {
        var macro = new RewriteCandidate("frozen-thought", "a", "c", List.of(step("first", "b"), step("second", "c")))
            .toTransformation();
        var result = run(expression -> expression.equals("a") ? List.of(macro) : List.of(), "c", 10, 1000);
        var diagnostic = SearchRunDiagnostics.observe(result, id -> "learned", Set.of(macro.rule()), 2);
        assertTrue(result.reached());
        assertEquals(1, diagnostic.firstHitDepth());
        assertEquals(2, diagnostic.firstHitPrimitiveDepth());
        assertEquals(2, diagnostic.verificationWork());
        assertEquals(2, diagnostic.learnedContributions().getFirst().primitiveSteps());
        assertEquals("a", diagnostic.learnedContributions().getFirst().from());
        assertEquals("c", diagnostic.learnedContributions().getFirst().to());
        assertThrows(UnsupportedOperationException.class, () -> diagnostic.matchesByRuleFamily().put("forged", 9L));
    }
}
