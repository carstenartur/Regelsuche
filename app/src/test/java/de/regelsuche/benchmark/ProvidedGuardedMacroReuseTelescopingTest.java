package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.mining.GoalAwareMacroMoveSelector;
import de.regelsuche.mining.MacroMoveTransformationEngine;
import de.regelsuche.mining.RuleStatus;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.validation.CandidateProofStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProvidedGuardedMacroReuseTelescopingTest {
    @Test
    void providedTelescopingMacroReusesOnlyOnUnitStepProducts() {
        ReusableRule learned = new ReusableRule(
            "telescoping_fraction_learned",
            "1 / (A * B)",
            "1 / A - 1 / B",
            List.of("B = A + 1"),
            CandidateProofStatus.VALIDATED_BY_EXAMPLES,
            RuleStatus.NEW,
            1,
            2.0,
            Instant.now(),
            "hash-telescoping",
            null,
            0,
            1,
            List.of("telescoping-source"),
            0.95
        );
        InMemoryRuleInventoryRepository inventory = new InMemoryRuleInventoryRepository();
        inventory.save(learned);
        inventory.setEnabled(learned.id(), true);

        MacroMoveTransformationEngine engine = new MacroMoveTransformationEngine(
            new AstRewriteTransformationEngine(List.of(), 0, 0),
            new GoalAwareMacroMoveSelector(inventory),
            null,
            Map.of()
        );

        List<de.regelsuche.transform.Transformation> reused = engine.transform("1 / ((x + 2) * (x + 3))").stream()
            .filter(transformation -> transformation.rule().equals("macro_telescoping_fraction_learned"))
            .toList();

        assertEquals(1, reused.size());
        assertEquals("1 / (x + 2) - 1 / (x + 3)", reused.getFirst().transformedExpression());
        assertTrue(engine.transform("1 / (n * (n + 2))").stream()
            .noneMatch(transformation -> transformation.rule().equals("macro_telescoping_fraction_learned")));
    }
}
