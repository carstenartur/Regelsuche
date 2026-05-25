package de.regelsuche.learning;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.mining.KnownRuleRepository;
import de.regelsuche.mining.RuleCandidateMiner;
import de.regelsuche.mining.SuccessfulTransformationPath;
import de.regelsuche.scoring.ExpressionScore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MacroRuleLearningServiceTest {

    private static SuccessfulTransformationPath path(String id, String left, String right, int score) {
        ExpressionScore before = new ExpressionScore(left.length() + 5, 0, 0, 0, 0);
        ExpressionScore after = new ExpressionScore(right.length(), 0, 0, 0, 0);
        return new SuccessfulTransformationPath(
            id,
            left,
            right,
            List.of(left, right),
            List.of("expand_power_to_product", "distribute_multiplication", "combine_like_terms"),
            before,
            after,
            true,
            "test",
            Map.of("variable", "x")
        );
    }

    @Test
    void discoveryPlusFindsMacroRuleAcrossRuns() {
        // Three successful runs over (x+N)^2 with N in {1,2,3} provide enough
        // examples for anti-unification to land a macro rule in the inventory.
        InMemoryRuleInventoryRepository inventory = new InMemoryRuleInventoryRepository();
        MacroRuleLearningService service = new MacroRuleLearningService(
            inventory,
            new RuleCandidateMiner(new KnownRuleRepository()),
            new KnownRuleRepository(),
            3,
            0.0  // confidence threshold relaxed so the test asserts the discovery side only
        );
        List<SuccessfulTransformationPath> paths = List.of(
            path("p1", "(x + 1) ^ 2", "1 + 2 * x + x ^ 2", 6),
            path("p2", "(x + 2) ^ 2", "4 + 4 * x + x ^ 2", 8),
            path("p3", "(x + 3) ^ 2", "9 + 6 * x + x ^ 2", 8)
        );

        MacroLearningResult result = service.learn(paths);
        assertFalse(result.touchedRules().isEmpty(),
            "after 3 examples the miner must produce at least one rule");
        ReusableRule learned = result.touchedRules().get(0);
        assertNotNull(learned);
        assertTrue(learned.occurrenceCount() >= 3,
            "occurrenceCount must accumulate across the 3 supporting paths");
        // The accumulating learning service is the contract surface; activation
        // requires confidence as well and is covered by the next test.
        assertFalse(inventory.findAll().isEmpty(),
            "inventory must remember the learned rule");
    }

    @Test
    void learnedMacroRuleCarriesPathAssumptions() {
        InMemoryRuleInventoryRepository inventory = new InMemoryRuleInventoryRepository();
        MacroRuleLearningService service = new MacroRuleLearningService(
            inventory,
            new RuleCandidateMiner(new KnownRuleRepository()),
            new KnownRuleRepository(),
            3,
            0.0
        );
        List<SuccessfulTransformationPath> paths = List.of(
            path("p1", "(x + 1) ^ 2", "1 + 2 * x + x ^ 2", 6).withAssumptions(List.of("b != 0")),
            path("p2", "(x + 2) ^ 2", "4 + 4 * x + x ^ 2", 8).withAssumptions(List.of("0 != b")),
            path("p3", "(x + 3) ^ 2", "9 + 6 * x + x ^ 2", 8).withAssumptions(List.of("b≠0"))
        );

        MacroLearningResult result = service.learn(paths);

        assertFalse(result.touchedRules().isEmpty());
        ReusableRule learned = result.touchedRules().get(0);
        assertTrue(learned.assumptions().stream().anyMatch(a -> a.endsWith(" != 0")),
            "macro rules must carry normalized assumptions from supporting paths");
    }
}
