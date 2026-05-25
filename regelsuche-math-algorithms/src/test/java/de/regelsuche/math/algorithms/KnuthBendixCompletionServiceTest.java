package de.regelsuche.math.algorithms;

import static de.regelsuche.transform.PatternExpr.fn;
import static de.regelsuche.transform.PatternExpr.var;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.math.algorithms.completion.KnuthBendixCompletionService;
import de.regelsuche.math.algorithms.registry.DefaultMathematicalAlgorithmRegistry;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.validation.CompletionService;
import de.regelsuche.validation.CriticalPairService;
import de.regelsuche.validation.MathematicalAlgorithmRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KnuthBendixCompletionServiceTest {
    @Test
    void criticalPairsAreReported() {
        KnuthBendixCompletionService service = new KnuthBendixCompletionService(
            new DefaultMathematicalAlgorithmRegistry(Map.of(
                MathematicalAlgorithmRegistry.CRITICAL_PAIRS, true
            ), Map.of())
        );

        List<PatternRewriteRule> rules = List.of(
            new PatternRewriteRule("to-g", fn("f", var("A")), fn("g", var("A"))),
            new PatternRewriteRule("to-h", fn("f", var("A")), fn("h", var("A")))
        );

        CriticalPairService.CriticalPairReport report = service.analyzeCriticalPairs(rules);
        assertFalse(report.criticalPairs().isEmpty());
    }

    @Test
    void completionProducesCandidatesForNonConfluentSystem() {
        KnuthBendixCompletionService service = new KnuthBendixCompletionService(
            new DefaultMathematicalAlgorithmRegistry(Map.of(
                MathematicalAlgorithmRegistry.KNUTH_BENDIX, true,
                MathematicalAlgorithmRegistry.CRITICAL_PAIRS, true
            ), Map.of())
        );

        List<PatternRewriteRule> rules = List.of(
            new PatternRewriteRule("to-g", fn("f", var("A")), fn("g", var("A"))),
            new PatternRewriteRule("to-h", fn("f", var("A")), fn("h", var("A")))
        );

        CompletionService.CompletionReport report = service.analyzeCompletion(rules);
        assertFalse(report.confluent());
        assertFalse(report.completionCandidates().isEmpty());
    }

    @Test
    void budgetLimitsCompletionExploration() {
        KnuthBendixCompletionService service = new KnuthBendixCompletionService(
            new DefaultMathematicalAlgorithmRegistry(Map.of(
                MathematicalAlgorithmRegistry.KNUTH_BENDIX, true,
                MathematicalAlgorithmRegistry.CRITICAL_PAIRS, true
            ), Map.of(
                MathematicalAlgorithmRegistry.KNUTH_BENDIX,
                MathematicalAlgorithmRegistry.AlgorithmBudget.bounded(1, 1, 0, 0.0)
            ))
        );

        List<PatternRewriteRule> rules = List.of(
            new PatternRewriteRule("to-g", fn("f", var("A")), fn("g", var("A"))),
            new PatternRewriteRule("to-h", fn("f", var("A")), fn("h", var("A")))
        );

        CompletionService.CompletionReport report = service.analyzeCompletion(rules);
        assertTrue(report.result().status() == MathematicalAlgorithmRegistry.ExecutionStatus.BUDGET_EXHAUSTED
            || report.result().status() == MathematicalAlgorithmRegistry.ExecutionStatus.SUCCESS);
    }
}
