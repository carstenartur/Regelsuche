package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchState;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.validation.CandidateProofStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MacroMoveTransformationEngineTest {

    @Test
    void macroMoveReducesSearchDepthVersusAtomicOnlySearch() {
        String root = "(x + 3) ^ 2";
        String target = "x ^ 2 + 2 * 3 * x + 3 ^ 2";
        ExpressionScorer scorer = new ExpressionScorer();
        ExpressionCanonicalizer canonicalizer = new ExpressionCanonicalizer();

        SearchProblem atomicProblem = new SearchProblem(
            root,
            new AstRewriteTransformationEngine(),
            scorer,
            canonicalizer,
            new SearchHeuristic(1, 120, 1, 4, 80, 20)
        );
        boolean atomicFoundAtDepthOne = new BestFirstSearchStrategy().search(atomicProblem).stream()
            .anyMatch(state -> state.expression().equals(target));
        assertFalse(atomicFoundAtDepthOne, "atomic-only search must not reach the expanded target in one edge");

        InMemoryRuleInventoryRepository inventory = new InMemoryRuleInventoryRepository();
        ReusableRule macro = macroRule(
            "binomial_square",
            "(x + A) ^ 2",
            "x ^ 2 + 2 * A * x + A ^ 2"
        );
        inventory.save(macro);
        inventory.setEnabled(macro.id(), true);
        GoalAwareMacroMoveSelector selector = new GoalAwareMacroMoveSelector(inventory);
        MacroMoveTransformationEngine macroEngine = new MacroMoveTransformationEngine(
            new AstRewriteTransformationEngine(),
            selector,
            target,
            Map.of(macro.id(), atomicSteps())
        );
        SearchProblem macroProblem = new SearchProblem(
            root,
            macroEngine,
            scorer,
            canonicalizer,
            new SearchHeuristic(1, 120, 1, 4, 80, 20)
        );
        SearchState reachedByMacro = new BestFirstSearchStrategy().search(macroProblem).stream()
            .filter(state -> state.expression().equals(target))
            .findFirst()
            .orElseThrow();

        assertEquals(1, reachedByMacro.depth(), "macro target must be reached by one search edge");
        assertTrue(reachedByMacro.appliedRuleId().contains("macro_binomial_square"));
        assertTrue(macroEngine.expansionFor(root, target, reachedByMacro.appliedRuleId()).isPresent(),
            "macro edge must retain replay expansion metadata");
        assertEquals(3, macroEngine.expansionFor(root, target, reachedByMacro.appliedRuleId()).orElseThrow().atomicSteps().size());
    }

    @Test
    void highConfidenceButGoalIrrelevantMacroIsRejected() {
        InMemoryRuleInventoryRepository inventory = new InMemoryRuleInventoryRepository();
        ReusableRule trig = macroRule("trig_identity", "sin(B) ^ 2 + cos(B) ^ 2", "1");
        inventory.save(trig);
        inventory.setEnabled(trig.id(), true);

        GoalAwareMacroMoveSelector selector = new GoalAwareMacroMoveSelector(inventory);
        List<ReusableRule> selected = selector.selectFor("x ^ 2 + 2*x + 1", "(x + 1) ^ 2");

        assertTrue(selected.isEmpty(), "goal-irrelevant high-confidence macro must be rejected");
    }

    private static ReusableRule macroRule(String id, String left, String right) {
        return new ReusableRule(
            id,
            left,
            right,
            List.of(),
            CandidateProofStatus.VALIDATED_BY_EXAMPLES,
            RuleStatus.NEW,
            3,
            10.0,
            Instant.now(),
            "hash-" + id,
            null,
            0,
            3,
            List.of("p1", "p2", "p3"),
            0.95
        );
    }

    private static List<TransformationStep> atomicSteps() {
        return List.of(
            new TransformationStep(0, "(x + 3) ^ 2", "(x + 3)*(x + 3)",
                "ast_power_two_to_product", RewriteKind.EXPAND, 10, 12, true, ""),
            new TransformationStep(1, "(x + 3)*(x + 3)", "x*x + x*3 + 3*x + 3*3",
                "ast_distribute", RewriteKind.EXPAND, 12, 14, true, ""),
            new TransformationStep(2, "x*x + x*3 + 3*x + 3*3", "x ^ 2 + 2 * 3 * x + 3 ^ 2",
                "ast_canonical_normalize", RewriteKind.NORMALIZE, 14, 8, true, "")
        );
    }
}
