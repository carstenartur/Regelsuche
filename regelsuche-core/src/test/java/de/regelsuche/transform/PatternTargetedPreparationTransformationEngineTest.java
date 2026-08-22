package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.knowledge.KnowledgePackSelection;
import de.regelsuche.knowledge.RuleProfile;
import java.util.List;
import org.junit.jupiter.api.Test;

class PatternTargetedPreparationTransformationEngineTest {
    private static final String HIDDEN_PYTHAGOREAN =
        "((sin(x) * a) / a)^2 + ((cos(x) * b) / b)^2";

    @Test
    void safePilotAddsCompositeSymPyRuleWithoutRemovingDirectMoves() {
        PatternTargetedPreparationTransformationEngine engine =
            pilot();

        var execution = engine.transformWithEvidence(HIDDEN_PYTHAGOREAN);

        assertTrue(execution.transformations().stream().anyMatch(value ->
            "ast_cancel_division_factor".equals(value.rule())));
        Transformation amplified = execution.transformations().stream()
            .filter(value ->
                PatternTargetedPreparationTransformationEngine
                    .PYTHAGOREAN_RULE_ID.equals(value.rule()))
            .findFirst()
            .orElseThrow();
        assertEquals("1", amplified.transformedExpression());
        assertEquals(List.of("a != 0", "b != 0"),
            amplified.assumptions());
        assertEquals(List.of(
            "ast_cancel_division_factor",
            "ast_cancel_division_factor",
            "sympy.trig.pythagorean"),
            amplified.primitiveRuleIds());
        assertTrue(amplified.applicationKey().startsWith(
            "pattern-prepared:"));
    }

    @Test
    void alreadyVisiblePrincipalRuleRemainsOnePrimitiveDirectMove() {
        PatternTargetedPreparationTransformationEngine engine = pilot();

        List<Transformation> transformations = engine.transform(
            "sin(x)^2 + cos(x)^2");

        List<Transformation> pythagorean = transformations.stream()
            .filter(value ->
                PatternTargetedPreparationTransformationEngine
                    .PYTHAGOREAN_RULE_ID.equals(value.rule()))
            .toList();
        assertEquals(1, pythagorean.size());
        assertEquals(1, pythagorean.getFirst().primitiveStepCount());
        assertFalse(pythagorean.getFirst().applicationKey().startsWith(
            "pattern-prepared:"));
    }

    @Test
    void nearMissWithDifferentArgumentsIsNotAmplified() {
        PatternTargetedPreparationTransformationEngine engine = pilot();

        List<Transformation> transformations = engine.transform(
            "sin(x)^2 + cos(y)^2");

        assertFalse(transformations.stream().anyMatch(value ->
            PatternTargetedPreparationTransformationEngine
                .PYTHAGOREAN_RULE_ID.equals(value.rule())));
    }

    @Test
    void localBridgeCanBeAppliedInsideLargerExpression() {
        PatternTargetedPreparationTransformationEngine engine = pilot();

        List<Transformation> transformations = engine.transform(
            "y * (" + HIDDEN_PYTHAGOREAN + ")");

        assertTrue(transformations.stream().anyMatch(value ->
            PatternTargetedPreparationTransformationEngine
                .PYTHAGOREAN_RULE_ID.equals(value.rule())
                && "y * 1".equals(value.transformedExpression())));
    }

    private static PatternTargetedPreparationTransformationEngine pilot() {
        return PatternTargetedPreparationTransformationEngine
            .symPyPythagoreanPilot(
                KnowledgePackSelection.profile(RuleProfile.ALL));
    }
}
