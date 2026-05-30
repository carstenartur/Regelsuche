package de.regelsuche.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.mining.GoalAwareMacroMoveSelector;
import de.regelsuche.mining.MacroMoveExpansion;
import de.regelsuche.mining.MacroMoveTransformationEngine;
import de.regelsuche.mining.SuccessfulTransformationPath;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.Transformation;
import de.regelsuche.equivalence.SymPyEquivalenceService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LearnedMacroRuleReuseTest {

    @Test
    void telescopingMacroIsLearnedPromotedReusedAndValidatedFromReplay() {
        InMemoryRuleInventoryRepository inventory = new InMemoryRuleInventoryRepository();
        MacroLearningPipeline pipeline = new MacroLearningPipeline(inventory);

        MacroLearningResult result = pipeline.learn(List.of(path(
            "telescoping-source",
            "1 / (n * (n + 1))",
            "1 / n - 1 / (n + 1)",
            List.of()
        )));

        assertFalse(result.newlyActivated().isEmpty(), "macro must be promoted after generated validation");
        ReusableRule learned = result.newlyActivated().getFirst();
        assertEquals("1 / (A * (A + 1))", learned.leftPattern());
        assertEquals("1 / A - 1 / (A + 1)", learned.rightPattern());
        assertTrue(learned.supportingPathIds().contains("telescoping-source"));
        assertTrue(inventory.isEnabled(learned.id()));
        assertTrue(result.validationExamples().stream().allMatch(MacroValidationExample::equivalent));

        MacroMoveTransformationEngine engine = new MacroMoveTransformationEngine(
            new AstRewriteTransformationEngine(List.of(), 0, 0),
            new GoalAwareMacroMoveSelector(inventory),
            null,
            Map.of()
        );
        List<Transformation> reused = engine.transform("1 / ((x + 2) * (x + 3))").stream()
            .filter(transformation -> transformation.rule().equals(learned.id()))
            .toList();

        assertEquals(1, reused.size(), "learned macro must be reused as one macro edge");
        assertEquals("1 / (x + 2) - 1 / (x + 3)", reused.getFirst().transformedExpression());
        MacroMoveExpansion expansion = engine.expansionFor(
            "1 / ((x + 2) * (x + 3))",
            reused.getFirst().transformedExpression(),
            learned.id()
        ).orElseThrow();
        assertTrue(expansion.supportingPathIds().contains("telescoping-source"));
        assertTrue(new SymPyEquivalenceService().areEquivalent(
            "1 / ((x + 2) * (x + 3))",
            reused.getFirst().transformedExpression()
        ));
        assertTrue(engine.transform("1 / (n * (n + 2))").stream()
            .noneMatch(transformation -> transformation.rule().equals(learned.id())),
            "false-positive corpus must reject non-unit-step products");
    }

    @Test
    void rationalizationMacroCarriesAssumptionsIntoReplayExpansion() {
        InMemoryRuleInventoryRepository inventory = new InMemoryRuleInventoryRepository();
        MacroLearningResult result = new MacroLearningPipeline(inventory).learn(List.of(path(
            "rationalization-source",
            "1 / (sqrt(x) + 1)",
            "(sqrt(x) - 1) / (x - 1)",
            List.of("x != 1")
        )));

        ReusableRule learned = result.newlyActivated().getFirst();
        assertTrue(learned.assumptions().contains("A != 1"));

        MacroMoveTransformationEngine engine = new MacroMoveTransformationEngine(
            new AstRewriteTransformationEngine(List.of(), 0, 0),
            new GoalAwareMacroMoveSelector(inventory),
            null,
            Map.of(),
            List.of("A != 1")
        );
        Transformation reused = engine.transform("1 / (sqrt(y + 2) + 1)").stream()
            .filter(transformation -> transformation.rule().equals(learned.id()))
            .findFirst()
            .orElseThrow();
        assertEquals("(sqrt(y + 2) - 1) / (y + 2 - 1)", reused.transformedExpression());
        MacroMoveExpansion expansion = engine.expansionFor(
            "1 / (sqrt(y + 2) + 1)",
            reused.transformedExpression(),
            learned.id()
        ).orElseThrow();
        assertTrue(expansion.assumptions().contains("A != 1"));
        assertTrue(new SymPyEquivalenceService().areEquivalent(
            "1 / (sqrt(y + 2) + 1)",
            reused.transformedExpression()
        ));
    }

    private static SuccessfulTransformationPath path(String id, String left, String right, List<String> assumptions) {
        return new SuccessfulTransformationPath(
            id,
            left,
            right,
            List.of(left, right),
            List.of("actual_replay_step_1", "actual_replay_step_2"),
            new ExpressionScore(left.length() + 8, 0, 0, 0, 0),
            new ExpressionScore(right.length(), 0, 0, 0, 0),
            true,
            "test",
            Map.of("source", "replay")
        ).withAssumptions(assumptions);
    }
}
