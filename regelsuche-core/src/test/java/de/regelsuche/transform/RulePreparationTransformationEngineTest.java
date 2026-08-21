package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RulePreparationTransformationEngineTest {
    private final ExpressionCanonicalizer canonicalizer =
        new ExpressionCanonicalizer();

    @Test
    void exposesThePreparedCancellationAsACompositeTargetFreeMove() {
        RulePreparationTransformationEngine engine =
            new RulePreparationTransformationEngine();

        Transformation candidate = engine.transform(
                "(x^3 - 1) / (x - 1)")
            .stream()
            .filter(transformation -> transformation.primitiveRuleIds()
                .contains(RulePreparationPlanner.PREPARATION_RULE_ID))
            .findFirst()
            .orElseThrow();

        assertEquals(
            RulePreparationPlanner.PRINCIPAL_RULE_ID,
            candidate.rule());
        assertEquals(
            canonicalizer.stableHash("x^2 + x + 1"),
            canonicalizer.stableHash(
                candidate.transformedExpression()));
        assertEquals(List.of("x - 1 != 0"), candidate.assumptions());
        assertEquals(
            List.of(
                RulePreparationPlanner.PREPARATION_RULE_ID,
                RulePreparationPlanner.PRINCIPAL_RULE_ID),
            candidate.primitiveRuleIds());
        assertEquals(2, candidate.primitiveStepCount());
    }

    @Test
    void preparesANestedAstNodeRatherThanOnlyTheRoot() {
        RulePreparationTransformationEngine engine =
            new RulePreparationTransformationEngine();

        boolean reached = engine.transform(
                "1 + (x^3 - 1) / (x - 1)")
            .stream()
            .filter(transformation -> transformation.primitiveRuleIds()
                .contains(RulePreparationPlanner.PREPARATION_RULE_ID))
            .anyMatch(transformation -> canonicalizer.stableHash(
                    transformation.transformedExpression())
                .equals(canonicalizer.stableHash(
                    "1 + (x^2 + x + 1)")));

        assertTrue(reached);
    }

    @Test
    void preparesAFunctionArgumentAtItsExactAstPosition() {
        RulePreparationTransformationEngine engine =
            new RulePreparationTransformationEngine();

        boolean reached = engine.transform(
                "sin((x^3 - 1) / (x - 1))")
            .stream()
            .filter(transformation -> transformation.primitiveRuleIds()
                .contains(RulePreparationPlanner.PREPARATION_RULE_ID))
            .anyMatch(transformation -> canonicalizer.stableHash(
                    transformation.transformedExpression())
                .equals(canonicalizer.stableHash(
                    "sin(x^2 + x + 1)")));

        assertTrue(reached);
    }

    @Test
    void disabledPlanningReturnsTheDirectEngineResultsByteForByte() {
        AstRewriteTransformationEngine direct =
            new AstRewriteTransformationEngine();
        RulePreparationTransformationEngine disabled =
            new RulePreparationTransformationEngine(
                direct,
                direct.rules().stream().map(RewriteRule::id)
                    .collect(java.util.stream.Collectors.toSet()),
                new RulePreparationPlanner(),
                0);
        String expression = "(x^3 - 1) / (x - 1)";

        assertEquals(
            direct.transform(expression),
            disabled.transform(expression));
    }

    @Test
    void hiddenPrincipalRulePreventsPreparation() {
        AstRewriteTransformationEngine direct =
            new AstRewriteTransformationEngine();
        RulePreparationTransformationEngine engine =
            new RulePreparationTransformationEngine(
                direct,
                Set.of(),
                new RulePreparationPlanner(),
                16);

        assertEquals(
            direct.transform("(x^3 - 1) / (x - 1)"),
            engine.transform("(x^3 - 1) / (x - 1)"));
    }

    @Test
    void preparedCandidateRequiresReplayThroughThePrincipalRule() {
        TransformationEngine emptyDirectEngine = expression -> List.of();
        RulePreparationTransformationEngine engine =
            new RulePreparationTransformationEngine(
                emptyDirectEngine,
                Set.of(RulePreparationPlanner.PRINCIPAL_RULE_ID),
                new RulePreparationPlanner(),
                16);

        assertTrue(engine.transform("(x^3 - 1) / (x - 1)").isEmpty());
    }

    @Test
    void nonExactDivisionNeverProducesAPreparedMove() {
        RulePreparationTransformationEngine engine =
            new RulePreparationTransformationEngine();

        assertTrue(engine.transform("(x^3 + 1) / (x - 1)")
            .stream()
            .noneMatch(transformation -> transformation.primitiveRuleIds()
                .contains(RulePreparationPlanner.PREPARATION_RULE_ID)));
    }
}
