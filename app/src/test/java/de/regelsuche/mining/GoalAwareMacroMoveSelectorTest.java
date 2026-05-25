package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.mining.RuleStatus;
import de.regelsuche.validation.CandidateProofStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link GoalAwareMacroMoveSelector}. */
class GoalAwareMacroMoveSelectorTest {

    private static ReusableRule rule(
        String id,
        String left,
        String right,
        double confidence,
        double improvement,
        int occurrences
    ) {
        return new ReusableRule(
            id, left, right, List.of(),
            CandidateProofStatus.VALIDATED_BY_EXAMPLES,
            RuleStatus.NEW,
            3, improvement, Instant.now(), id + "-hash",
            null, 0, occurrences, List.of(), confidence
        );
    }

    @Test
    void rulesWithSufficientConfidenceAndImprovementAreSelected() {
        InMemoryRuleInventoryRepository inventory = new InMemoryRuleInventoryRepository();
        ReusableRule good = rule("binomial", "(x + A)^2", "x^2 + 2*A*x + A^2", 0.9, 5.0, 3);
        inventory.save(good);
        inventory.setEnabled("binomial", true);

        GoalAwareMacroMoveSelector selector = new GoalAwareMacroMoveSelector(inventory);
        List<ReusableRule> selected = selector.selectFor("(x + 3)^2");

        assertFalse(selected.isEmpty(), "high-confidence rule must be selected for matching expression");
    }

    @Test
    void disabledRulesAreNotSelected() {
        InMemoryRuleInventoryRepository inventory = new InMemoryRuleInventoryRepository();
        ReusableRule rule = rule("disabled-rule", "x + A", "A + x", 0.9, 3.0, 3);
        inventory.save(rule);
        inventory.setEnabled("disabled-rule", false);

        GoalAwareMacroMoveSelector selector = new GoalAwareMacroMoveSelector(inventory);
        List<ReusableRule> selected = selector.selectFor("x + 5");

        assertTrue(selected.isEmpty(), "disabled rule must not be selected");
    }

    @Test
    void lowConfidenceRulesAreFilteredOut() {
        InMemoryRuleInventoryRepository inventory = new InMemoryRuleInventoryRepository();
        ReusableRule lowConf = rule("low-conf", "x^2 + A", "x^2 + A", 0.1, 3.0, 3);
        inventory.save(lowConf);
        inventory.setEnabled("low-conf", true);

        GoalAwareMacroMoveSelector selector = new GoalAwareMacroMoveSelector(inventory,
            0.5, 0.0, 1);
        List<ReusableRule> selected = selector.selectFor("x^2 + 5");

        assertTrue(selected.isEmpty(), "low-confidence rule must be filtered out");
    }

    @Test
    void nonImprovingRulesAreFilteredOut() {
        InMemoryRuleInventoryRepository inventory = new InMemoryRuleInventoryRepository();
        ReusableRule noImprove = rule("no-improve", "x + A", "A + x", 0.9, -1.0, 3);
        inventory.save(noImprove);
        inventory.setEnabled("no-improve", true);

        GoalAwareMacroMoveSelector selector = new GoalAwareMacroMoveSelector(inventory);
        List<ReusableRule> selected = selector.selectFor("x + 5");

        assertTrue(selected.isEmpty(), "non-improving rule must be filtered out");
    }

    @Test
    void unrelatedExpressionDoesNotMatchRule() {
        InMemoryRuleInventoryRepository inventory = new InMemoryRuleInventoryRepository();
        // Rule about sin/cos — unrelated to binomial x
        ReusableRule trig = rule("trig", "sin(B)^2 + cos(B)^2", "1", 0.9, 5.0, 3);
        inventory.save(trig);
        inventory.setEnabled("trig", true);

        GoalAwareMacroMoveSelector selector = new GoalAwareMacroMoveSelector(inventory);
        // An expression without 'sin' or 'cos' should not match the trig rule
        List<ReusableRule> selected = selector.selectFor("x^2 + 2*x + 1");

        assertTrue(selected.isEmpty(), "trig rule must not be goal-aligned with polynomial expression");
    }

    @Test
    void selectorReturnsAllEligibleRules() {
        InMemoryRuleInventoryRepository inventory = new InMemoryRuleInventoryRepository();
        ReusableRule r1 = rule("r1", "x^2 + 2*A*x + A^2", "(x + A)^2", 0.9, 5.0, 3);
        ReusableRule r2 = rule("r2", "x^2 + A^2", "(x + A)*(x - A)", 0.8, 3.0, 3);
        inventory.save(r1);
        inventory.save(r2);
        inventory.setEnabled("r1", true);
        inventory.setEnabled("r2", true);

        GoalAwareMacroMoveSelector selector = new GoalAwareMacroMoveSelector(inventory);
        List<ReusableRule> selected = selector.selectFor("x^2 + 6*x + 9");

        assertFalse(selected.isEmpty(), "at least one rule should be selected");
        assertTrue(selected.size() >= 1);
    }

    @Test
    void emptyExpressionReturnsEmpty() {
        InMemoryRuleInventoryRepository inventory = new InMemoryRuleInventoryRepository();
        GoalAwareMacroMoveSelector selector = new GoalAwareMacroMoveSelector(inventory);
        assertTrue(selector.selectFor("").isEmpty());
        assertTrue(selector.selectFor(null).isEmpty());
    }

    @Test
    void macroWithAssumptionsRequiresMatchingCarriedContext() {
        InMemoryRuleInventoryRepository inventory = new InMemoryRuleInventoryRepository();
        ReusableRule rationalCancel = rule("cancel", "(a * b) / b", "a", 0.9, 5.0, 3)
            .withAssumptions(List.of("b != 0"));
        inventory.save(rationalCancel);
        inventory.setEnabled("cancel", true);

        GoalAwareMacroMoveSelector selector = new GoalAwareMacroMoveSelector(inventory);

        assertTrue(selector.selectFor("(a * b) / b").isEmpty(),
            "macro must not apply when its assumption context is absent");
        assertEquals(1, selector.selectFor("(a * b) / b", null, List.of("0 != b")).size(),
            "normalized carried assumption should satisfy the macro precondition");
    }
}
